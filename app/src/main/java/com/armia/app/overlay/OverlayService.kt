package com.armia.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.armia.app.ArmIAApp
import com.armia.app.NativeEngine
import com.armia.app.R
import com.armia.app.capture.CaptureConsentActivity
import com.armia.app.capture.CaptureLabService
import com.armia.app.data.FilterStyle
import com.armia.app.data.GameProfile
import com.armia.app.data.PackManager
import com.armia.app.data.ProfileRepository
import com.armia.app.system.ForegroundAppWatcher
import com.armia.app.system.Permissions
import com.armia.app.system.ThermalMonitor
import com.armia.app.system.realScreenSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Serviço principal. Enquanto estiver ligado:
 *  1. descobre qual app está na frente (ForegroundAppWatcher);
 *  2. se for um jogo do perfil Arm-IA, mostra a bolinha e (se ativo) a camada de filtro;
 *  3. a bolinha abre o painel de ajustes; mudanças vão direto para o motor nativo.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var repo: ProfileRepository

    private var engine: NativeEngine? = null
    private var watcher: ForegroundAppWatcher? = null
    private var thermal: ThermalMonitor? = null
    private var statsJob: Job? = null

    private var activePackage: String? = null

    private var filterView: FilterSurfaceView? = null
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: SettingsPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = ProfileRepository.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()

        // Sem o pacote de efeitos o serviço não liga (o visual vem dele).
        PackManager.load(applicationContext)
        if (!PackManager.isReady()) {
            Toast.makeText(this, "Escolha o pacote de efeitos no Arm-IA antes de ativar.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Permissions.canDrawOverlays(this) || !Permissions.hasUsageAccess(this)) {
            Toast.makeText(this, "Faltam permissões. Abra o Arm-IA e libere as duas.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) startWatching()
        _running.value = true
        return START_NOT_STICKY   // se o sistema matar, o usuário religa (evita crash de serviço em 2º plano)
    }

    override fun onDestroy() {
        _running.value = false
        watcher?.stop()
        thermal?.stop()
        statsJob?.cancel()
        hideAll()
        scope.cancel()
        engine?.close()
        engine = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ início

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ArmIAApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun startWatching() {
        val e = NativeEngine()
        engine = e
        PackManager.applyTo(e)
        thermal = ThermalMonitor(this) { status -> engine?.setThermalStatus(status) }.also { it.start() }

        watcher = ForegroundAppWatcher(this, scope) { pkg ->
            activePackage = pkg
            refresh()
        }.also { it.start() }

        // Mudanças de perfil (feitas no app ou no painel) e do laboratório reavaliam tudo.
        scope.launch { repo.profiles.collect { refresh() } }
        // Trocou o pacote de efeitos com o serviço ligado: aplica os números novos.
        scope.launch {
            PackManager.state.collect {
                engine?.let { en -> PackManager.applyTo(en) }
                refresh()
            }
        }
        scope.launch {
            var last = CaptureLabService.running.value
            CaptureLabService.running.collect { running ->
                refresh()
                panel?.setLabRunning(running)
                if (running && !last) bringBubbleToFront()
                last = running
            }
        }
    }

    // ------------------------------------------------------------------ decisão central

    private fun refresh() {
        val pkg = activePackage
        val profile = pkg?.let { repo.get(it) }
        if (profile == null) {
            hideAll()
            return
        }
        val showFilter = profile.enabled && !CaptureLabService.running.value
        if (showFilter) ensureFilter() else removeFilter()
        ensureBubble()
        applyToEngine(profile)
        if (panelShown) panel?.bind(profile)
    }

    private fun applyToEngine(profile: GameProfile) {
        val e = engine ?: return
        val style = if (profile.enabled) profile.style else FilterStyle.OFF
        e.setStyle(style.id, profile.intensity, profile.mode.id)
        e.setTargetFps(profile.mode.overlayFps)
        filterView?.setScale(profile.mode.overlayScale)
    }

    // ------------------------------------------------------------------ camada de filtro

    private fun ensureFilter() {
        if (filterView != null) return
        val e = engine ?: return
        val view = FilterSurfaceView(this, e)
        // Android 12+: janela com FLAG_NOT_TOUCHABLE e opacidade acima de 0,8 tem os toques
        // bloqueados para o app de baixo. Por isso alpha = 0,8 (o sistema faria isso sozinho).
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = 0.8f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            setTitle("ArmIA-filtro")
        }
        try {
            wm.addView(view, params)
            filterView = view
            bringBubbleToFront()   // a bolinha precisa ficar acima do filtro
        } catch (ex: Exception) {
            Toast.makeText(this, "Não foi possível criar o filtro: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeFilter() {
        val view = filterView ?: return
        filterView = null
        runCatching { wm.removeViewImmediate(view) }
    }

    // ------------------------------------------------------------------ bolinha

    private fun ensureBubble() {
        if (bubble != null) return
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble_logo)
            contentDescription = "Arm-IA"
        }
        val size = dp(46)
        val screen = realScreenSize(this)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screen.x - size - dp(8)
            y = screen.y / 3
            setTitle("ArmIA-bolinha")
        }
        attachDrag(view, params) { togglePanel() }
        try {
            wm.addView(view, params)
            bubble = view
            bubbleParams = params
        } catch (ex: Exception) {
            Toast.makeText(this, "Não foi possível criar a bolinha: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeBubble() {
        val view = bubble ?: return
        bubble = null
        bubbleParams = null
        runCatching { wm.removeViewImmediate(view) }
    }

    /** Remove e adiciona de novo: janelas adicionadas por último ficam por cima. */
    private fun bringBubbleToFront() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        runCatching {
            wm.removeViewImmediate(view)
            wm.addView(view, params)
        }
        if (panelShown) {
            val p = panel?.root
            val pp = panelParams
            if (p != null && pp != null) runCatching {
                wm.removeViewImmediate(p)
                wm.addView(p, pp)
            }
        }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams, onClick: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ------------------------------------------------------------------ painel

    private fun togglePanel() {
        if (panelShown) hidePanel() else showPanel()
    }

    private fun showPanel() {
        val pkg = activePackage ?: return
        val profile = repo.get(pkg) ?: return
        val screen = realScreenSize(this)

        val p = panel ?: SettingsPanel(
            context = this,
            maxHeightPx = (screen.y * 0.85f).toInt(),
            onChange = { updated, save -> onPanelChange(updated, save) },
            onClose = { hidePanel() },
            onLab = { fullscreen -> startLab(fullscreen) },
            onStopLab = { startService(CaptureLabService.stopIntent(this)) }
        ).also { panel = it }

        p.bind(profile)
        p.setLabRunning(CaptureLabService.running.value)

        val params = WindowManager.LayoutParams(
            minOf(dp(330), (screen.x * 0.92f).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            setTitle("ArmIA-painel")
        }
        try {
            wm.addView(p.root, params)
            panelParams = params
            panelShown = true
        } catch (ex: Exception) {
            Toast.makeText(this, "Não foi possível abrir o painel: ${ex.message}", Toast.LENGTH_LONG).show()
            return
        }

        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && panelShown) {
                engine?.stats()?.let { panel?.setStats(it[0], it[1]) }
                delay(1_000)
            }
        }
    }

    private fun hidePanel() {
        if (!panelShown) return
        panelShown = false
        statsJob?.cancel()
        panel?.root?.let { runCatching { wm.removeViewImmediate(it) } }
        panelParams = null
    }

    private fun onPanelChange(updated: GameProfile, save: Boolean) {
        applyToEngine(updated)               // efeito imediato, sem esperar o disco
        if (save) repo.upsert(updated)       // refresh() cuida de criar/remover a camada
    }

    private fun startLab(fullscreen: Boolean) {
        val pkg = activePackage ?: return
        hidePanel()
        startActivity(CaptureConsentActivity.intent(this, pkg, fullscreen))
    }

    private fun hideAll() {
        hidePanel()
        removeBubble()
        removeFilter()
    }

    // ------------------------------------------------------------------ estado e constantes

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.armia.app.action.STOP_OVERLAY"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }
}
