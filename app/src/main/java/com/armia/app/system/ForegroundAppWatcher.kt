package com.armia.app.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Descobre qual app está na frente lendo os eventos de uso (permissão "Acesso ao uso").
 * Consulta 1x por segundo; só chama [onChanged] quando o app da frente muda.
 */
class ForegroundAppWatcher(
    context: Context,
    private val scope: CoroutineScope,
    private val onChanged: (String?) -> Unit
) {
    private val usageStats =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var job: Job? = null
    private var current: String? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var from = System.currentTimeMillis() - 2 * 60_000L
            while (isActive) {
                val now = System.currentTimeMillis()
                val before = current
                val after = withContext(Dispatchers.Default) { poll(from, now) }
                if (after != before) onChanged(after)
                // Sobrepõe 3 s para não perder eventos que o sistema grava com atraso.
                // Reprocessar é seguro: os eventos são aplicados em ordem e o resultado é o mesmo.
                from = now - 3_000L
                delay(1_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        current = null
    }

    private fun poll(from: Long, to: Long): String? {
        val events = usageStats.queryEvents(from, to)
        val event = UsageEvents.Event()
        var result = current
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> result = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    if (result == event.packageName) result = null
            }
        }
        current = result
        return result
    }
}
