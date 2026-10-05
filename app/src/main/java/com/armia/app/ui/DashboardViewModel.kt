package com.armia.app.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armia.app.capture.CaptureLabService
import com.armia.app.data.GameProfile
import com.armia.app.data.InstalledApp
import com.armia.app.data.InstalledApps
import com.armia.app.data.ProfileRepository
import com.armia.app.overlay.OverlayService
import com.armia.app.system.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionState(
    val overlay: Boolean,
    val usageAccess: Boolean,
    val notifications: Boolean
) {
    val canStart: Boolean get() = overlay && usageAccess
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository.get(app)
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val _loading = MutableStateFlow(true)
    private val _permissions = MutableStateFlow(readPermissions())

    val query = MutableStateFlow("")
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()
    val profiles: StateFlow<List<GameProfile>> = repo.profiles
    val serviceRunning: StateFlow<Boolean> = OverlayService.running
    val labRunning: StateFlow<Boolean> = CaptureLabService.running

    /** Apps que ainda não estão no Arm-IA, filtrados pela busca. */
    val availableApps: StateFlow<List<InstalledApp>> =
        combine(installed, repo.profiles, query) { apps, profiles, q ->
            val added = profiles.map { it.packageName }.toSet()
            apps.filter { it.packageName !in added && (q.isBlank() || it.label.contains(q, ignoreCase = true)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            installed.value = withContext(Dispatchers.Default) { InstalledApps.load(getApplication<Application>()) }
            _loading.value = false
        }
    }

    fun refreshPermissions() {
        _permissions.value = readPermissions()
    }

    fun addGame(app: InstalledApp) {
        repo.upsert(GameProfile(packageName = app.packageName, label = app.label))
    }

    fun removeGame(packageName: String) {
        repo.remove(packageName)
    }

    fun updateProfile(packageName: String, change: (GameProfile) -> GameProfile) {
        repo.update(packageName, change)
    }

    fun startService() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
    }

    fun stopService() {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
        )
    }

    private fun readPermissions(): PermissionState {
        val context = getApplication<Application>()
        return PermissionState(
            overlay = Permissions.canDrawOverlays(context),
            usageAccess = Permissions.hasUsageAccess(context),
            notifications = Permissions.hasNotificationPermission(context)
        )
    }
}
