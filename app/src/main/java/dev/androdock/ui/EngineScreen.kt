package dev.androdock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androdock.runtime.FileUtils
import dev.androdock.terminal.TerminalActivity
import dev.androdock.vm.EngineState
import kotlin.math.roundToInt

@Composable
fun EngineScreen(vm: AppViewModel) {
    val status by vm.engine.collectAsState()
    val config by vm.config.collectAsState()
    val console by vm.console.collectAsState()
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    val running = status.state == EngineState.RUNNING
    val stopped = status.state == EngineState.STOPPED || status.state == EngineState.ERROR

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!vm.nativeBundled) {
            Card {
                Text(
                    "Cet APK a été compilé sans les binaires natifs (QEMU, proot, docker). " +
                        "Lancez scripts/fetch-native.py avant le build, ou utilisez l'APK produit par la CI.",
                    Modifier.padding(16.dp), color = Failed,
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        when (status.state) {
                            EngineState.RUNNING -> Running
                            EngineState.ERROR -> Failed
                            EngineState.STOPPED -> Stopped
                            else -> Accent
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Moteur Docker", style = MaterialTheme.typography.titleMedium)
                }
                Text(status.message, style = MaterialTheme.typography.bodyMedium)
                status.progress?.let { p ->
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                status.accel?.let { a ->
                    Text("Accélération : ${a.label}" + (status.accelInfo?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                if (status.forwardedPorts.isNotEmpty()) {
                    Text("Ports redirigés : " + status.forwardedPorts.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stopped) {
                        Button(onClick = { vm.startEngine() }, enabled = vm.nativeBundled) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Démarrer")
                        }
                    } else {
                        Button(onClick = { vm.stopEngine() }, enabled = status.state != EngineState.STOPPING) {
                            Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Arrêter")
                        }
                    }
                    OutlinedButton(
                        onClick = { context.startActivity(TerminalActivity.intent(context, TerminalActivity.KIND_DOCKER, title = "docker")) },
                        enabled = vm.nativeBundled,
                    ) {
                        Icon(Icons.Filled.Terminal, null); Spacer(Modifier.width(6.dp)); Text("Terminal")
                    }
                }
                Text(
                    "API Docker : tcp://127.0.0.1:2375 — utilisable depuis Termux ou toute app locale.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ressources de la VM", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Appliquées au prochain démarrage.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val maxCpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                LabeledSlider("Processeurs", "${config.cpus}", config.cpus.toFloat(), 1f..maxCpu.toFloat(), maxCpu - 2) {
                    vm.updateConfig { c -> c.copy(cpus = it.roundToInt()) }
                }
                LabeledSlider("Mémoire", "${config.memoryMb} Mo", config.memoryMb.toFloat(), 512f..8192f, 14) {
                    vm.updateConfig { c -> c.copy(memoryMb = (it / 512).roundToInt() * 512) }
                }
                LabeledSlider("Disque (max)", "${config.diskGb} Go", config.diskGb.toFloat(), 8f..128f, 14) {
                    vm.updateConfig { c -> c.copy(diskGb = (it / 8).roundToInt() * 8) }
                }
                Text(
                    "Espace réellement utilisé : ${FileUtils.humanSize(vm.diskUsage())}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ToggleRow("Démarrer avec l'application", config.autoStart) { v -> vm.updateConfig { it.copy(autoStart = v) } }
                ToggleRow("Empêcher la mise en veille du processeur", config.keepAwake) { v -> vm.updateConfig { it.copy(keepAwake = v) } }
                ToggleRow("Exposer les ports sur le réseau local", config.exposeOnLan) { v -> vm.updateConfig { it.copy(exposeOnLan = v) } }
                ToggleRow("Accélération matérielle (KVM) si possible", config.hwAccel) { v -> vm.updateConfig { it.copy(hwAccel = v) } }
                ToggleRow("API de contrôle (127.0.0.1:${dev.androdock.BuildConfig.CONTROL_PORT})", config.controlApi) { v ->
                    vm.updateConfig { it.copy(controlApi = v) }
                }
                if (config.controlApi) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Jeton : " + vm.controlToken.take(8) + "…",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        TextButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(vm.controlToken))
                        }) { Text("Copier") }
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Console de la VM", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showConsole = !showConsole }) { Text(if (showConsole) "Masquer" else "Afficher") }
                }
                if (showConsole) {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp)
                            .background(Color.Black, RoundedCornerShape(8.dp))
                            .verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(8.dp)
                    ) {
                        Text(
                            console.takeLast(300).joinToString("\n").ifEmpty { "(vide)" },
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFB0B8C8),
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = { vm.prune() }, enabled = running, modifier = Modifier.fillMaxWidth()) {
            Text("Nettoyer (conteneurs arrêtés, images orphelines)")
        }
        TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Réinitialiser les données Docker", color = Failed)
        }
        TextButton(
            onClick = { context.startActivity(android.content.Intent(context, dev.androdock.legal.LicensesActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Licences et mentions") }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReset) {
        ConfirmDialog(
            "Réinitialiser Docker ?",
            "Tous les conteneurs, images et volumes seront définitivement supprimés.",
            confirm = "Réinitialiser",
            onConfirm = { vm.resetEngineData() },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(current) { mutableStateOf(current) }
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) },
            valueRange = range, steps = steps.coerceAtLeast(0),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
