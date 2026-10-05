package com.armia.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda o perfil de cada jogo (estilo, intensidade, modo) em SharedPreferences, como JSON.
 * É um singleton simples: o app e o serviço de overlay enxergam a mesma lista.
 */
class ProfileRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _profiles = MutableStateFlow(load())

    val profiles: StateFlow<List<GameProfile>> = _profiles.asStateFlow()

    fun get(packageName: String): GameProfile? =
        _profiles.value.firstOrNull { it.packageName == packageName }

    fun upsert(profile: GameProfile) {
        synchronized(lock) {
            val list = _profiles.value.toMutableList()
            val index = list.indexOfFirst { it.packageName == profile.packageName }
            if (index >= 0) list[index] = profile else list.add(profile)
            commit(list)
        }
    }

    fun update(packageName: String, change: (GameProfile) -> GameProfile) {
        synchronized(lock) {
            val list = _profiles.value.toMutableList()
            val index = list.indexOfFirst { it.packageName == packageName }
            if (index < 0) return@synchronized
            list[index] = change(list[index])
            commit(list)
        }
    }

    fun remove(packageName: String) {
        synchronized(lock) {
            commit(_profiles.value.filterNot { it.packageName == packageName })
        }
    }

    private fun commit(list: List<GameProfile>) {
        _profiles.value = list
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun load(): List<GameProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it).toProfile() }
        } catch (e: Exception) {
            emptyList()   // JSON corrompido: começa do zero em vez de travar o app
        }
    }

    private fun GameProfile.toJson(): JSONObject = JSONObject()
        .put("pkg", packageName)
        .put("label", label)
        .put("enabled", enabled)
        .put("style", style.id)
        .put("intensity", intensity.toDouble())
        .put("mode", mode.id)

    private fun JSONObject.toProfile(): GameProfile = GameProfile(
        packageName = getString("pkg"),
        label = optString("label", getString("pkg")),
        enabled = optBoolean("enabled", true),
        style = FilterStyle.fromId(optInt("style", FilterStyle.CINEMA.id)),
        intensity = optDouble("intensity", 0.6).toFloat().coerceIn(0f, 1f),
        mode = PerformanceMode.fromId(optInt("mode", PerformanceMode.BALANCED.id))
    )

    companion object {
        private const val PREFS_NAME = "armia_profiles"
        private const val KEY_PROFILES = "profiles"

        @Volatile
        private var instance: ProfileRepository? = null

        fun get(context: Context): ProfileRepository =
            instance ?: synchronized(this) {
                instance ?: ProfileRepository(context).also { instance = it }
            }
    }
}
