package com.armia.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.armia.app.data.PackManager
import com.armia.app.ui.ArmIATheme
import com.armia.app.ui.DashboardScreen
import com.armia.app.ui.DashboardViewModel
import com.armia.app.ui.PackBar
import com.armia.app.ui.PackGateScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels()
    private val importing = MutableStateFlow(false)

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissions()
        }

    private val packPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPack(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PackManager.load(applicationContext)
        setContent {
            ArmIATheme {
                val packState by PackManager.state.collectAsState()
                val busy by importing.collectAsState()
                val pack = packState.pack

                if (pack == null) {
                    // Sem pacote de efeitos: só esta tela.
                    PackGateScreen(error = packState.error, busy = busy, onPick = ::pickPack)
                } else {
                    Column(Modifier.fillMaxSize()) {
                        val label = if (pack.version.isBlank()) pack.name else "${pack.name} v${pack.version}"
                        PackBar(name = label, error = packState.error, onChange = ::pickPack)
                        Box(Modifier.weight(1f)) {
                            DashboardScreen(
                                vm = viewModel,
                                onRequestNotifications = {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Volta das telas de configuração: confere as permissões de novo.
    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    private fun pickPack() {
        packPicker.launch(arrayOf("*/*"))
    }

    private fun importPack(uri: Uri) {
        importing.value = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { PackManager.importFrom(applicationContext, uri) }
            importing.value = false
        }
    }
}
