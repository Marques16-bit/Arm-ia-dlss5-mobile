package com.armia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Primeira tela: sem o pacote de efeitos o app não segue para o painel nem liga o serviço. */
@Composable
fun PackGateScreen(error: String?, busy: Boolean, onPick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Arm-IA", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("Primeiro passo: pacote de efeitos", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "O visual dos filtros (nitidez, cor, brilho, sombras) vem de um arquivo de pacote " +
                    "(.zip com um pack.json). Escolha o arquivo para continuar. Sem ele o serviço não liga.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Lendo o arquivo…" else "Escolher arquivo do pacote")
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Pacote padrão: Arm-IA-pacote-padrao.zip (pasta pacote/ do repositório ou o arquivo enviado no chat).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Faixa fina no topo do painel: mostra o pacote em uso e deixa trocar. */
@Composable
fun PackBar(name: String, error: String?, onChange: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp)
        ) {
            Text(
                "Pacote: $name", modifier = Modifier.weight(1f), maxLines = 1, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onChange) { Text("Trocar") }
        }
        if (error != null) {
            Text(
                error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            )
        }
    }
}
