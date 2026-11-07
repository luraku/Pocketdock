package dev.androdock.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androdock.docker.ContainerSummary
import dev.androdock.docker.RunSpec
import dev.androdock.terminal.TerminalActivity

@Composable
fun ContainersScreen(vm: AppViewModel, showRunDialog: Boolean, onRunDialogDismiss: () -> Unit) {
    val containers by vm.containers.collectAsState()
    val context = LocalContext.current
    var logsFor by remember { mutableStateOf<ContainerSummary?>(null) }
    var confirmDelete by remember { mutableStateOf<ContainerSummary?>(null) }

    if (containers.isEmpty()) {
        EmptyState(
            Icons.Filled.ViewInAr,
            "Aucun conteneur",
            "Lancez un conteneur avec le bouton +, ou depuis le terminal Docker :\ndocker run -d -p 8080:80 nginx",
        )
    } else {
        val groups = containers.groupBy { it.composeProject }
        LazyColumn(Modifier.fillMaxWidth()) {
            for ((project, list) in groups.entries.sortedBy { it.key ?: "" }) {
                if (project != null) item(key = "h-$project") { SectionHeader("Compose · $project") }
                items(list, key = { it.id }) { c ->
                    ContainerRow(
                        c,
                        onStartStop = { if (c.running) vm.stop(c) else vm.start(c) },
                        onRestart = { vm.restart(c) },
                        onLogs = { logsFor = c },
                        onShell = {
                            context.startActivity(TerminalActivity.intent(context, TerminalActivity.KIND_EXEC, c.id, c.name))
                        },
                        onDelete = { confirmDelete = c },
                        onOpenPort = { port ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port")))
                        },
                    )
                }
            }
        }
    }

    logsFor?.let { c -> LogsDialog(vm, c) { logsFor = null } }
    confirmDelete?.let { c ->
        ConfirmDialog(
            "Supprimer ${c.name} ?",
            "Le conteneur sera arrêté puis supprimé, ainsi que ses volumes anonymes.",
            onConfirm = { vm.remove(c) },
            onDismiss = { confirmDelete = null },
        )
    }
    if (showRunDialog) RunDialog(onDismiss = onRunDialogDismiss, onRun = { vm.run(it); onRunDialogDismiss() })
}

@Composable
private fun ContainerRow(
    c: ContainerSummary,
    onStartStop: () -> Unit,
    onRestart: () -> Unit,
    onLogs: () -> Unit,
    onShell: () -> Unit,
    onDelete: () -> Unit,
    onOpenPort: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { StatusDot(if (c.running) Running else if (c.state == "exited") Stopped else Failed) },
        headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text("${c.image} · ${c.status}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                val published = c.ports.mapNotNull { it.publicPort }.distinct()
                if (published.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        published.forEach { p ->
                            AssistChip(onClick = { onOpenPort(p) }, label = { Text("localhost:$p") })
                        }
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onStartStop) {
                    Icon(if (c.running) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (c.running) "Arrêter" else "Démarrer")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Journaux") }, leadingIcon = { Icon(Icons.Filled.Description, null) },
                            onClick = { menu = false; onLogs() },
                        )
                        if (c.running) {
                            DropdownMenuItem(
                                text = { Text("Terminal") }, leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                                onClick = { menu = false; onShell() },
                            )
                            DropdownMenuItem(
                                text = { Text("Redémarrer") }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                                onClick = { menu = false; onRestart() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onLogs),
    )
}

@Composable
private fun LogsDialog(vm: AppViewModel, c: ContainerSummary, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("Chargement…") }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(c.id, reload) { text = vm.logs(c).ifBlank { "(aucune sortie)" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Journaux · ${c.name}") },
        text = {
            Box(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = {
            TextButton(onClick = { reload++ }) { Text("Actualiser") }
        },
    )
}

@Composable
private fun RunDialog(onDismiss: () -> Unit, onRun: (RunSpec) -> Unit) {
    var image by remember { mutableStateOf("nginx:alpine") }
    var name by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("8080:80") }
    var env by remember { mutableStateOf("") }
    var volumes by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var restart by remember { mutableStateOf(false) }

    fun split(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lancer un conteneur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(image, { image = it }, label = { Text("Image") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Nom (facultatif)") }, singleLine = true)
                OutlinedTextField(
                    ports, { ports = it }, label = { Text("Ports hôte:conteneur") },
                    placeholder = { Text("8080:80, 5432:5432") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(env, { env = it }, label = { Text("Variables (CLE=valeur, …)") })
                OutlinedTextField(volumes, { volumes = it }, label = { Text("Volumes (nom:/chemin, …)") })
                OutlinedTextField(command, { command = it }, label = { Text("Commande (facultatif)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(restart, { restart = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Redémarrer automatiquement", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = image.isNotBlank(),
                onClick = {
                    onRun(
                        RunSpec(
                            image = image.trim(),
                            name = name.trim().ifEmpty { null },
                            ports = split(ports),
                            env = split(env),
                            volumes = split(volumes),
                            command = command.trim().ifEmpty { null },
                            restartAlways = restart,
                        )
                    )
                },
            ) { Text("Lancer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
