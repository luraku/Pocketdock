package dev.androdock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.androdock.docker.ImageSummary
import dev.androdock.docker.RunSpec
import dev.androdock.docker.VolumeSummary
import dev.androdock.runtime.FileUtils
import java.text.DateFormat
import java.util.Date

@Composable
fun ImagesScreen(vm: AppViewModel, showPullDialog: Boolean, onPullDialogDismiss: () -> Unit) {
    val images by vm.images.collectAsState()
    val volumes by vm.volumes.collectAsState()
    var confirmImage by remember { mutableStateOf<ImageSummary?>(null) }
    var confirmVolume by remember { mutableStateOf<VolumeSummary?>(null) }

    if (images.isEmpty() && volumes.isEmpty()) {
        EmptyState(Icons.Filled.Layers, "Aucune image", "Téléchargez une image depuis Docker Hub avec le bouton +.")
    } else {
        LazyColumn(Modifier.fillMaxWidth()) {
            item { SectionHeader("Images (${images.size})") }
            items(images, key = { it.id }) { img ->
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Layers, null) },
                    headlineContent = { Text(img.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            FileUtils.humanSize(img.size) + " · " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(img.created * 1000)),
                        )
                    },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { vm.run(RunSpec(image = img.displayName)) }) {
                                Icon(Icons.Filled.PlayArrow, "Lancer")
                            }
                            IconButton(onClick = { confirmImage = img }) { Icon(Icons.Filled.Delete, "Supprimer") }
                        }
                    },
                )
            }
            if (volumes.isNotEmpty()) {
                item { SectionHeader("Volumes (${volumes.size})") }
                items(volumes, key = { "v-" + it.name }) { v ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Storage, null) },
                        headlineContent = { Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(v.driver) },
                        trailingContent = {
                            IconButton(onClick = { confirmVolume = v }) { Icon(Icons.Filled.Delete, "Supprimer") }
                        },
                    )
                }
            }
        }
    }

    confirmImage?.let { img ->
        ConfirmDialog("Supprimer ${img.displayName} ?", "L'image sera supprimée de la VM.",
            onConfirm = { vm.removeImage(img) }, onDismiss = { confirmImage = null })
    }
    confirmVolume?.let { v ->
        ConfirmDialog("Supprimer le volume ${v.name} ?", "Toutes ses données seront perdues.",
            onConfirm = { vm.removeVolume(v) }, onDismiss = { confirmVolume = null })
    }
    if (showPullDialog) {
        var ref by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onPullDialogDismiss,
            title = { Text("Télécharger une image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ref, { ref = it }, label = { Text("Référence") },
                        placeholder = { Text("postgres:16-alpine") }, singleLine = true)
                    Text("Les images doivent exister pour linux/arm64.")
                }
            },
            confirmButton = {
                TextButton(enabled = ref.isNotBlank(), onClick = { vm.pull(ref.trim()); onPullDialogDismiss() }) {
                    Text("Télécharger")
                }
            },
            dismissButton = { TextButton(onClick = onPullDialogDismiss) { Text("Annuler") } },
        )
    }
}
