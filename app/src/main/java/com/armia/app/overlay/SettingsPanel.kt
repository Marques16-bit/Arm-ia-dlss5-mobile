package com.armia.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.armia.app.data.FilterStyle
import com.armia.app.data.GameProfile
import com.armia.app.data.PerformanceMode

/**
 * Painel de ajustes que abre ao tocar na bolinha (estilo painel do ReShade/DLSS).
 * Feito em código (sem XML) para não depender de AppCompat dentro de um Service.
 *
 * [onChange] recebe o perfil novo e se deve ser salvo (false enquanto o dedo arrasta o controle).
 */
class SettingsPanel(
    private val context: Context,
    maxHeightPx: Int,
    private val onChange: (GameProfile, Boolean) -> Unit,
    private val onClose: () -> Unit,
    private val onLab: (fullscreen: Boolean) -> Unit,
    private val onStopLab: () -> Unit
) {
    val root: View

    private var profile: GameProfile? = null
    private var binding = false

    private val title = text("Arm-IA", 16f, bold = true, color = PRIMARY)
    private val enabledSwitch = Switch(context)
    private val styleChips = LinkedHashMap<FilterStyle, TextView>()
    private val modeChips = LinkedHashMap<PerformanceMode, TextView>()
    private val hint = text("", 11f, color = MUTED)
    private val intensityLabel = text("Intensidade", 13f)
    private val seek = SeekBar(context)
    private val stats = text("", 11f, color = MUTED)
    private val labStart = button("Laboratório: prévia") { onLab(false) }
    private val labFull = button("Laboratório: tela cheia") { onLab(true) }
    private val labStop = button("Parar laboratório") { onStopLab() }

    init {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
        }

        // Cabeçalho
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("✕", 18f, color = Color.WHITE).apply {
                setPadding(context.dp(10), context.dp(4), context.dp(4), context.dp(4))
                setOnClickListener { onClose() }
            })
        })

        // Liga/desliga
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(6), 0, context.dp(6))
            addView(text("Filtro ligado", 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(enabledSwitch)
        })
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) update(true) { it.copy(enabled = checked) }
        }

        // Estilos
        content.addView(sectionLabel("Estilo"))
        val styles = listOf(
            FilterStyle.PHOTOREAL, FilterStyle.CINEMA, FilterStyle.REAL_LIFE,
            FilterStyle.VIBRANT_HDR, FilterStyle.FILM
        )
        styles.chunked(3).forEach { rowStyles ->
            content.addView(chipRow().also { row ->
                rowStyles.forEach { style ->
                    val chip = chip(style.label) { update(true) { it.copy(style = style) } }
                    styleChips[style] = chip
                    row.addView(chip, chipParams())
                }
            })
        }
        content.addView(hint)

        // Intensidade
        content.addView(sectionLabel(""))   // espaçamento
        content.addView(intensityLabel)
        seek.max = 100
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (binding || !fromUser) return
                intensityLabel.text = "Intensidade: $progress%"
                update(false) { it.copy(intensity = progress / 100f) }
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                update(true) { it.copy(intensity = bar.progress / 100f) }
            }
        })
        content.addView(seek)

        // Modo de desempenho
        content.addView(sectionLabel("Modo"))
        content.addView(chipRow().also { row ->
            PerformanceMode.values().forEach { mode ->
                val chip = chip(mode.label) { update(true) { it.copy(mode = mode) } }
                modeChips[mode] = chip
                row.addView(chip, chipParams())
            }
        })

        // Laboratório de captura
        content.addView(sectionLabel("Captura (experimental)"))
        content.addView(chipRow().also { row ->
            row.addView(labStart, chipParams())
            row.addView(labFull, chipParams())
        })
        content.addView(labStop, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        labStop.visibility = View.GONE

        content.addView(stats)

        val scroll = MaxHeightScrollView(context, maxHeightPx).apply { addView(content) }
        root = scroll.apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2171C24"))
                cornerRadius = context.dp(18).toFloat()
                setStroke(context.dp(1), Color.parseColor("#334DD0E1"))
            }
            // Toque fora do painel fecha (a janela avisa com ACTION_OUTSIDE)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) onClose()
                false
            }
        }
    }

    /** Mostra o perfil atual sem disparar os callbacks. */
    fun bind(p: GameProfile) {
        binding = true
        profile = p
        title.text = "Arm-IA · ${p.label}"
        enabledSwitch.isChecked = p.enabled
        styleChips.forEach { (style, chip) -> paintChip(chip, style == p.style) }
        modeChips.forEach { (mode, chip) -> paintChip(chip, mode == p.mode) }
        seek.progress = (p.intensity * 100f).toInt()
        intensityLabel.text = "Intensidade: ${seek.progress}%"
        hint.visibility = if (p.style.needsCapture) View.VISIBLE else View.GONE
        hint.text = "Este estilo só fica completo no Laboratório de captura. " +
            "Na sobreposição aparece uma versão leve (vinheta e grão)."
        binding = false
    }

    fun setLabRunning(running: Boolean) {
        labStart.visibility = if (running) View.GONE else View.VISIBLE
        labFull.visibility = if (running) View.GONE else View.VISIBLE
        labStop.visibility = if (running) View.VISIBLE else View.GONE
    }

    fun setStats(fps: Float, cpuMs: Float) {
        stats.text = String.format("%.0f quadros/s · %.1f ms de CPU por quadro", fps, cpuMs)
    }

    private fun update(save: Boolean, change: (GameProfile) -> GameProfile) {
        val current = profile ?: return
        val next = change(current)
        profile = next
        if (save) bind(next)
        onChange(next, save)
    }

    // ------------------------------------------------------------------ construção da UI

    private fun text(value: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(context).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun sectionLabel(value: String) = text(value, 12f, bold = true, color = MUTED).apply {
        setPadding(0, context.dp(10), 0, context.dp(4))
    }

    private fun chipRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun chipParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(context.dp(4), context.dp(9), context.dp(4), context.dp(9))
        setOnClickListener { onClick() }
        paintChip(this, false)
    }

    private fun button(label: String, onClick: () -> Unit) = chip(label, onClick)

    private fun paintChip(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Color.parseColor("#0E1116") else Color.WHITE)
        view.background = GradientDrawable().apply {
            cornerRadius = context.dp(10).toFloat()
            setColor(if (selected) PRIMARY else Color.parseColor("#2A3441"))
        }
    }

    /** ScrollView que nunca passa de [maxHeightPx] (janela com altura WRAP_CONTENT). */
    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) :
        ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            )
        }
    }

    private companion object {
        val PRIMARY: Int = Color.parseColor("#4DD0E1")
        val MUTED: Int = Color.parseColor("#9AA7B5")
    }
}
