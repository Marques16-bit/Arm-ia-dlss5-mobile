@file:OptIn(ExperimentalMaterial3Api::class)

package com.armia.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.armia.app.data.FilterStyle
import com.armia.app.data.GameProfile
import com.armia.app.data.InstalledApp
import com.armia.app.data.PerformanceMode
import com.armia.app.system.Permissions

@Composable
fun DashboardScreen(vm: DashboardViewModel, onRequestNotifications: () -> Unit) {
    val context = LocalContext.current
    val permissions by vm.permissions.collectAsState()
    val running by vm.serviceRunning.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val available by vm.availableApps.collectAsState()
    val query by vm.query.collectAsState()
    val loading by vm.loading.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Arm-IA", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "Filtros visuais em tempo real para os seus jogos.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- status e permissões
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Serviço", fontWeight = FontWeight.Bold)
                        PermissionRow("Sobrepor a outros apps", permissions.overlay) {
                            context.startActivity(Permissions.overlaySettingsIntent(context))
                        }
                        PermissionRow("Acesso ao uso (detecta o jogo aberto)", permissions.usageAccess) {
                            context.startActivity(Permissions.usageAccessSettingsIntent())
                        }
                        PermissionRow("Notificações", permissions.notifications, onRequestNotifications)

                        if (running) {
                            OutlinedButton(onClick = vm::stopService, modifier = Modifier.fillMaxWidth()) {
                                Text("Parar serviço")
                            }
                            Text(
                                "Ativo. Abra um jogo da lista abaixo e procure a bolinha.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                            )
                        } else {
                            Button(
                                onClick = vm::startService,
                                enabled = permissions.canStart,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Ativar serviço") }
                            if (!permissions.canStart) {
                                Text(
                                    "Libere as duas primeiras permissões para ativar.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            // ---- jogos do perfil
            item { SectionTitle("Meus jogos") }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "Nenhum jogo ainda. Adicione um na lista abaixo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(profiles, key = { it.packageName }) { profile ->
                ProfileCard(
                    profile = profile,
                    onChange = { change -> vm.updateProfile(profile.packageName, change) },
                    onRemove = { vm.removeGame(profile.packageName) }
                )
            }

            // ---- adicionar
            item { SectionTitle("Adicionar jogo") }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    label = { Text("Buscar app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (loading) {
                item { Text("Carregando apps…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(available, key = { it.packageName }) { app ->
                AppRow(app) { vm.addGame(app) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (granted) "✓" else "✗",
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (!granted) TextButton(onClick = onFix) { Text("Liberar") }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIcon(app.packageName)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.label, maxLines = 1)
            if (app.isLikelyGame) {
                Text("jogo", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedButton(onClick = onAdd) { Text("Adicionar") }
    }
}

@Composable
private fun ProfileCard(
    profile: GameProfile,
    onChange: ((GameProfile) -> GameProfile) -> Unit,
    onRemove: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(profile.packageName)
                Text(
                    profile.label, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
                Switch(checked = profile.enabled, onCheckedChange = { v -> onChange { it.copy(enabled = v) } })
            }

            Text("Estilo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterStyle.values().filter { it != FilterStyle.OFF }.forEach { style ->
                    FilterChip(
                        selected = profile.style == style,
                        onClick = { onChange { it.copy(style = style) } },
                        label = { Text(style.label) }
                    )
                }
            }
            if (profile.style.needsCapture) {
                Text(
                    "Este estilo só fica completo no Laboratório de captura (pela bolinha, dentro do jogo).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            var intensity by remember(profile.intensity) { mutableStateOf(profile.intensity) }
            Text("Intensidade: ${(intensity * 100).toInt()}%", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = intensity,
                onValueChange = { intensity = it },
                onValueChangeFinished = { onChange { it.copy(intensity = intensity) } }
            )

            Text("Modo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PerformanceMode.values().forEach { mode ->
                    FilterChip(
                        selected = profile.mode == mode,
                        onClick = { onChange { it.copy(mode = mode) } },
                        label = { Text(mode.label) }
                    )
                }
            }

            TextButton(onClick = onRemove) { Text("Remover do Arm-IA") }
        }
    }
}

@Composable
private fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap, contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp))
        )
    } else {
        Spacer(Modifier.size(size))
    }
}
