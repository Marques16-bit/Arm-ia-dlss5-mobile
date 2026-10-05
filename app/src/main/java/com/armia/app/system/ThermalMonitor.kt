package com.armia.app.system

import android.content.Context
import android.os.PowerManager

/** Avisa quando o aparelho esquenta (PowerManager.THERMAL_STATUS_*), para o motor reduzir o ritmo. */
class ThermalMonitor(context: Context, private val onStatus: (Int) -> Unit) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val listener = PowerManager.OnThermalStatusChangedListener { onStatus(it) }
    private var started = false

    fun start() {
        if (started) return
        started = true
        powerManager.addThermalStatusListener(listener)
        onStatus(powerManager.currentThermalStatus)
    }

    fun stop() {
        if (!started) return
        started = false
        powerManager.removeThermalStatusListener(listener)
    }
}
