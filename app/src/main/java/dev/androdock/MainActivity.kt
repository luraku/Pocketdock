package dev.androdock

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.androdock.ui.AndrodockTheme
import dev.androdock.ui.AppViewModel
import dev.androdock.ui.ContainersScreen
import dev.androdock.ui.EmptyState
import dev.androdock.ui.EngineScreen
import dev.androdock.ui.ImagesScreen
import dev.androdock.ui.MachinesScreen
import dev.androdock.ui.Running
import dev.androdock.ui.StatusDot
import dev.androdock.ui.Stopped
import dev.androdock.ui.Accent
import dev.androdock.ui.Failed
import dev.androdock.vm.EngineState

private enum class Tab(val label: String, val icon: ImageVector) {
    Containers("Conteneurs", Icons.Filled.ViewInAr),
    Images("Images", Icons.Filled.Layers),
    Machines("Machines", Icons.Filled.Computer),
    Engine("Moteur", Icons.Filled.Settings);

    companion object {
        fun from(name: String?): Tab? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Écran principal. Pilotable par intent pour les tests et l'automatisation :
 * `adb shell am start -n dev.androdock/.MainActivity --es tab images`
 * (onglets : containers, images, machines, engine).
 */
class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val requestedTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedTab.value = intent?.getStringExtra(EXTRA_TAB)
        intent?.let { openDashboardIfAsked(it) }
        val skipPermission = intent?.getBooleanExtra(EXTRA_NO_PERMISSION_PROMPT, false) == true
        if (Build.VERSION.SDK_INT >= 33 && !skipPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AndrodockTheme { Surface(Modifier.fillMaxSize()) { App(requestedTab = requestedTab.value) } } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { requestedTab.value = it }
        openDashboardIfAsked(intent)
    }

    /** `am start -n dev.androdock/.MainActivity --es dashboard pods` ouvre le tableau de bord web sur cette vue. */
    private fun openDashboardIfAsked(intent: android.content.Intent) {
        val view = intent.getStringExtra(EXTRA_DASHBOARD) ?: return
        startActivity(
            android.content.Intent(this, dev.androdock.dashboard.DashboardActivity::class.java)
                .putExtra(dev.androdock.dashboard.DashboardActivity.EXTRA_VIEW, view)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_NO_PERMISSION_PROMPT = "noPermissionPrompt"
        const val EXTRA_DASHBOARD = "dashboard"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: AppViewModel = viewModel(), requestedTab: String? = null) {
    var tab by rememberSaveable { mutableStateOf(Tab.Containers) }
    LaunchedEffect(requestedTab) { Tab.from(requestedTab)?.let { tab = it } }
    var dialog by remember { mutableStateOf(false) }
    val status by vm.engine.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.messages.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val running = status.state == EngineState.RUNNING

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pocketdock")
                        Row2(status.state, status.message)
                    }
                },
                actions = {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    IconButton(onClick = {
                        ctx.startActivity(android.content.Intent(ctx, dev.androdock.dashboard.DashboardActivity::class.java))
                    }) { Icon(Icons.Filled.Dashboard, "Tableau de bord") }
                    when (status.state) {
                        EngineState.STOPPED, EngineState.ERROR -> IconButton(onClick = { vm.startEngine() }, enabled = vm.nativeBundled) {
                            Icon(Icons.Filled.PlayArrow, "Démarrer le moteur")
                        }
                        EngineState.STOPPING -> {}
                        else -> IconButton(onClick = { vm.stopEngine() }) { Icon(Icons.Filled.Stop, "Arrêter le moteur") }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                busy?.let { b ->
                    Surface(tonalElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(b, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t; dialog = false },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            val show = when (tab) {
                Tab.Containers, Tab.Images -> running
                Tab.Machines -> vm.nativeBundled
                Tab.Engine -> false
            }
            if (show) {
                FloatingActionButton(onClick = { dialog = true }) {
                    Icon(if (tab == Tab.Images) Icons.Filled.CloudDownload else Icons.Filled.Add, "Ajouter")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val needsEngine = tab == Tab.Containers || tab == Tab.Images
            if (needsEngine && !running) {
                EmptyState(
                    tab.icon,
                    if (status.state == EngineState.STOPPED || status.state == EngineState.ERROR) "Moteur Docker arrêté" else "Démarrage du moteur…",
                    status.message + if (status.state == EngineState.BOOTING)
                        "\nLe premier démarrage télécharge et installe Docker dans la VM : comptez quelques minutes." else "",
                    action = if (status.state == EngineState.STOPPED || status.state == EngineState.ERROR)
                        "Démarrer" to { vm.startEngine() } else null,
                )
            } else when (tab) {
                Tab.Containers -> ContainersScreen(vm, dialog) { dialog = false }
                Tab.Images -> ImagesScreen(vm, dialog) { dialog = false }
                Tab.Machines -> MachinesScreen(vm, dialog) { dialog = false }
                Tab.Engine -> EngineScreen(vm)
            }
        }
    }
}

@Composable
private fun Row2(state: EngineState, message: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        StatusDot(
            when (state) {
                EngineState.RUNNING -> Running
                EngineState.ERROR -> Failed
                EngineState.STOPPED -> Stopped
                else -> Accent
            }
        )
        Text(
            "  $message", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
