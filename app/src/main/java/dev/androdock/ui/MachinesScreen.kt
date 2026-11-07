package dev.androdock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.androdock.machines.DistroCatalog
import dev.androdock.machines.Machine
import dev.androdock.terminal.TerminalActivity

@Composable
fun MachinesScreen(vm: AppViewModel, showCreateDialog: Boolean, onCreateDialogDismiss: () -> Unit) {
    val machines by vm.machines.collectAsState()
    val install by vm.machineInstall.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<Machine?>(null) }

    Column(Modifier.fillMaxWidth()) {
        install?.let { inst ->
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("${inst.name} — ${inst.message}", style = MaterialTheme.typography.bodyMedium)
                val p = inst.progress
                if (p != null && p >= 0f) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 8.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        if (machines.isEmpty() && install == null) {
            EmptyState(
                Icons.Filled.Computer,
                "Aucune machine Linux",
                "Créez une machine Alpine ou Ubuntu : elle démarre instantanément, partage le stockage de " +
                    "l'application et peut piloter le moteur Docker (commande docker préinstallée).",
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(machines, key = { it.name }) { m ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Computer, null) },
                        headlineContent = { Text(m.name) },
                        supportingContent = { Text(m.distroLabel) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    context.startActivity(TerminalActivity.intent(context, TerminalActivity.KIND_MACHINE, m.name, m.name))
                                }) { Icon(Icons.Filled.Terminal, "Ouvrir un terminal") }
                                IconButton(onClick = { confirmDelete = m }) { Icon(Icons.Filled.Delete, "Supprimer") }
                            }
                        },
                        modifier = Modifier.clickable {
                            context.startActivity(TerminalActivity.intent(context, TerminalActivity.KIND_MACHINE, m.name, m.name))
                        },
                    )
                }
            }
        }
    }

    confirmDelete?.let { m ->
        ConfirmDialog("Supprimer ${m.name} ?", "Le système de fichiers de la machine sera entièrement effacé.",
            onConfirm = { vm.deleteMachine(m.name) }, onDismiss = { confirmDelete = null })
    }
    if (showCreateDialog) CreateMachineDialog(vm, onCreateDialogDismiss)
}

@Composable
private fun CreateMachineDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val choices = DistroCatalog.all + DistroCatalog.custom
    var distro by remember { mutableStateOf(choices.first()) }
    var name by remember { mutableStateOf(choices.first().id) }
    var url by remember { mutableStateOf("") }
    val nameOk = vm.isValidMachineName(name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle machine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = d == distro, onClick = {
                            if (name == distro.id || name.isBlank()) name = d.id
                            distro = d
                        }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = d == distro, onClick = null)
                        Text(d.label, Modifier.padding(start = 8.dp))
                    }
                }
                if (distro == DistroCatalog.custom) {
                    OutlinedTextField(url, { url = it }, label = { Text("URL du rootfs arm64 (.tar.gz/.tar.xz)") }, singleLine = true)
                }
                OutlinedTextField(
                    name, { name = it.lowercase() }, label = { Text("Nom") }, singleLine = true,
                    isError = !nameOk,
                    supportingText = { if (!nameOk) Text("Minuscules, chiffres et tirets ; nom unique") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameOk && (distro != DistroCatalog.custom || url.startsWith("https://")),
                onClick = { vm.createMachine(name, distro, url.takeIf { distro == DistroCatalog.custom }); onDismiss() },
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
