package com.armia.app.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.armia.app.ArmIAApp
import com.armia.app.NativeEngine
import com.armia.app.R
import com.armia.app.data.FilterStyle
import com.armia.app.data.GameProfile
import com.armia.app.data.PerformanceMode
import com.armia.app.data.ProfileRepository
import com.armia.app.overlay.FilterSurfaceView
import com.armia.app.system.ThermalMonitor
import com.armia.app.system.realScreenSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Laboratório de captura (EXPERIMENTAL).
 *
 * MediaProjection -> VirtualDisplay -> SurfaceTexture -> motor nativo (denoise, nitidez, cor)
 * -> janela de sobreposição (prévia pequena ou tela cheia).
 *
 * Latência esperada: 1 a 3 quadros. Jogos que bloqueiam captura (FLAG_SECURE) aparecem pretos.
 */
class CaptureLabService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameThread: HandlerThread? = null

    private lateinit var wm: WindowManager
    private lateinit var repo: ProfileRepository

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var engine: NativeEngine? = null
    private var view: FilterSurfaceView? = null
    private var thermal: ThermalMonitor? = null

    private var targetPackage = ""
    private var fullscreen = false
    private var mode = PerformanceMode.BALANCED
    private var capWidth = 0
    private var capHeight = 0
    private var densityDpi = 320
    private var started = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopSelf() }
        }

        // Android 14+: avisa o tamanho real do que está sendo capturado (um app ou a tela toda).
        override fun onCapturedContentResize(width: Int, height: Int) {
            mainHandler.post { resizeCapture(width, height) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = ProfileRepository.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // O Android 14 exige o serviço em primeiro plano ANTES de pegar a MediaProjection.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (started || data == null) {
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        fullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)

        try {
            startCapture(resultCode, data)
            started = true
            _running.value = true
        } catch (ex: Exception) {
            Toast.makeText(this, "Laboratório não iniciou: ${ex.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        thermal?.stop()
        // Ordem importa: primeiro a janela (solta a superfície), depois a fonte de captura,
        // depois o que alimenta a captura, e só no fim o motor.
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        engine?.setCaptureSource(null, 0, 0)
        virtualDisplay?.release()
        virtualDisplay = null
        surface?.release()
        surface = null
        texture?.release()
        texture = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        engine?.close()
        engine = null
        frameThread?.quitSafely()
        frameThread = null
        super.onDestroy()
    }

    // Abaixo do Android 14 não existe onCapturedContentResize: acompanhamos a rotação por aqui.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!started) return
        mainHandler.post {
            if (!fullscreen) updatePreviewLayout()
            if (android.os.Build.VERSION.SDK_INT < 34) {
                val screen = realScreenSize(this)
                resizeCapture(screen.x, screen.y)
            }
        }
    }

    // ------------------------------------------------------------------ captura

    private fun startCapture(resultCode: Int, data: Intent) {
        val profile = repo.get(targetPackage)
        mode = profile?.mode ?: PerformanceMode.BALANCED

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("sem permissão de captura")
        // O Android 14 exige registrar o callback antes de criar o VirtualDisplay.
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp

        val screen = realScreenSize(this)
        densityDpi = resources.displayMetrics.densityDpi
        capWidth = scaled(screen.x)
        capHeight = scaled(screen.y)

        val e = NativeEngine()
        engine = e
        thermal = ThermalMonitor(this) { status -> engine?.setThermalStatus(status) }.also { it.start() }

        val thread = HandlerThread("armia-frames").also { it.start() }
        frameThread = thread

        // SurfaceTexture "solta" (sem contexto GL): o motor nativo a conecta na thread dele.
        val tex = SurfaceTexture(false)
        tex.setDefaultBufferSize(capWidth, capHeight)
        tex.setOnFrameAvailableListener({ engine?.notifyFrame() }, Handler(thread.looper))
        texture = tex

        val surf = Surface(tex)
        surface = surf
        e.setCaptureSource(tex, capWidth, capHeight)

        // Janela de saída
        val v = FilterSurfaceView(this, e)
        v.setScale(mode.captureOutputScale)
        val params = if (fullscreen) fullscreenParams() else previewParams(screen.x, screen.y)
        wm.addView(v, params)
        view = v

        virtualDisplay = mp.createVirtualDisplay(
            "ArmIA-captura", capWidth, capHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null
        )

        // Segue o perfil do jogo enquanto o laboratório está ligado.
        scope.launch {
            repo.profiles.collect { list ->
                list.firstOrNull { it.packageName == targetPackage }?.let { applyProfile(it) }
            }
        }
    }

    private fun applyProfile(profile: GameProfile) {
        val e = engine ?: return
        val style = if (profile.enabled) profile.style else FilterStyle.OFF
        e.setStyle(style.id, profile.intensity, profile.mode.id)
        e.setTargetFps(profile.mode.captureFps)
    }

    private fun scaled(value: Int): Int = ((value * mode.captureScale).toInt() and 1.inv()).coerceAtLeast(16)

    private fun resizeCapture(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val w = scaled(width)
        val h = scaled(height)
        if (w == capWidth && h == capHeight) return
        capWidth = w
        capHeight = h
        texture?.setDefaultBufferSize(w, h)
        virtualDisplay?.resize(w, h, densityDpi)
        engine?.setCaptureSource(texture, w, h)   // recria os alvos de render no tamanho novo
    }

    // ------------------------------------------------------------------ janela de saída

    // Mesma regra da camada de filtro: NOT_TOUCHABLE + alpha acima de 0,8 bloquearia os toques.
    private fun baseParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { alpha = 0.8f }

    private fun fullscreenParams() = baseParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        setTitle("ArmIA-lab-tela-cheia")
    }

    private fun previewParams(screenW: Int, screenH: Int): WindowManager.LayoutParams {
        val w = screenW / 2
        val h = (w.toFloat() * screenH / screenW).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        return baseParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = margin
            setTitle("ArmIA-lab-previa")
        }
    }

    private fun updatePreviewLayout() {
        val v = view ?: return
        val screen = realScreenSize(this)
        runCatching { wm.updateViewLayout(v, previewParams(screen.x, screen.y)) }
    }

    // ------------------------------------------------------------------ notificação

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ArmIAApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Arm-IA: laboratório de captura")
            .setContentText("Capturando a tela para aplicar os filtros.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    // ------------------------------------------------------------------ estado e intents

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.armia.app.action.STOP_LAB"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_FULLSCREEN = "fullscreen"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            packageName: String,
            fullscreen: Boolean
        ): Intent = Intent(context, CaptureLabService::class.java)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_DATA, data)
            .putExtra(EXTRA_PACKAGE, packageName)
            .putExtra(EXTRA_FULLSCREEN, fullscreen)

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureLabService::class.java).setAction(ACTION_STOP)
    }
}
