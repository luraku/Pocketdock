package dev.androdock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.androdock.AndrodockApp
import dev.androdock.docker.ContainerSummary
import dev.androdock.docker.ImageSummary
import dev.androdock.docker.RunSpec
import dev.androdock.docker.VolumeSummary
import dev.androdock.machines.Distro
import dev.androdock.service.EngineService
import dev.androdock.vm.EngineConfig
import dev.androdock.vm.EngineState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndrodockApp
    private val docker = app.vm.docker

    val engine = app.vm.status
    val console = app.vm.console
    val config: StateFlow<EngineConfig> = app.settings.config
    val machines = app.machines.machines
    val machineInstall = app.machines.installing
    val nativeBundled: Boolean = app.runtime.isBundled

    private val _containers = MutableStateFlow<List<ContainerSummary>>(emptyList())
    val containers = _containers.asStateFlow()
    private val _images = MutableStateFlow<List<ImageSummary>>(emptyList())
    val images = _images.asStateFlow()
    private val _volumes = MutableStateFlow<List<VolumeSummary>>(emptyList())
    val volumes = _volumes.asStateFlow()

    /** Opération en cours (téléchargement d'image, etc.) affichée en bas de l'écran. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)
    val messages = _messages.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            engine.distinctUntilChangedBy { it.state }.collect { st ->
                if (st.state == EngineState.RUNNING) startPolling() else {
                    pollJob?.cancel()
                    _containers.value = emptyList(); _images.value = emptyList(); _volumes.value = emptyList()
                }
            }
        }
        if (config.value.autoStart && engine.value.state == EngineState.STOPPED && nativeBundled) startEngine()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                runCatching { _containers.value = docker.containers().sortedBy { it.name } }
                if (tick % 5 == 0) refreshImagesAndVolumes()
                tick++
                delay(3000)
            }
        }
    }

    private suspend fun refreshImagesAndVolumes() {
        runCatching { _images.value = docker.images().sortedBy { it.displayName } }
        runCatching { _volumes.value = docker.volumes().sortedBy { it.name } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { _containers.value = docker.containers().sortedBy { it.name } }
        refreshImagesAndVolumes()
    }

    fun consumeMessage() { _messages.value = null }

    private fun action(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = label
        try {
            block()
            refresh()
        } catch (e: Exception) {
            _messages.value = e.message ?: e.javaClass.simpleName
        } finally {
            _busy.value = null
        }
    }

    // --- Moteur ---
    fun startEngine() = EngineService.start(app)
    fun stopEngine() = app.vm.stop()
    fun updateConfig(t: (EngineConfig) -> EngineConfig) {
        app.settings.update(t)
        if (app.settings.config.value.controlApi) app.control.start() else app.control.stop()
    }
    val controlToken: String get() = app.control.token
    fun resetEngineData() = action("Réinitialisation des données Docker…") { app.vm.resetData() }
    fun diskUsage(): Long = app.vm.diskUsageBytes()

    // --- Conteneurs ---
    fun start(c: ContainerSummary) = action("Démarrage de ${c.name}…") { docker.start(c.id) }
    fun stop(c: ContainerSummary) = action("Arrêt de ${c.name}…") { docker.stop(c.id) }
    fun restart(c: ContainerSummary) = action("Redémarrage de ${c.name}…") { docker.restart(c.id) }
    fun remove(c: ContainerSummary) = action("Suppression de ${c.name}…") { docker.removeContainer(c.id) }
    suspend fun logs(c: ContainerSummary): String = runCatching { docker.logs(c.id) }.getOrElse { "Erreur : ${it.message}" }

    fun run(spec: RunSpec) = action("Lancement de ${spec.image}…") {
        docker.run(spec) { p -> _busy.value = listOfNotNull(p.status, p.progress).joinToString(" ") }
    }

    // --- Images & volumes ---
    fun pull(reference: String) = action("Téléchargement de $reference…") {
        docker.pull(reference) { p -> _busy.value = "$reference : " + listOfNotNull(p.status, p.progress).joinToString(" ") }
        _messages.value = "$reference téléchargée"
    }
    fun removeImage(i: ImageSummary) = action("Suppression de ${i.displayName}…") { docker.removeImage(i.id) }
    fun removeVolume(v: VolumeSummary) = action("Suppression du volume ${v.name}…") { docker.removeVolume(v.name) }
    fun prune() = action("Nettoyage…") { docker.prune() }

    // --- Machines ---
    fun createMachine(name: String, distro: Distro, customUrl: String?) = viewModelScope.launch {
        try {
            app.machines.create(name, distro, customUrl)
            _messages.value = "Machine $name créée"
        } catch (e: Exception) {
            _messages.value = "Échec : ${e.message}"
        }
    }

    fun deleteMachine(name: String) = action("Suppression de la machine $name…") { app.machines.delete(name) }
    fun isValidMachineName(name: String) = app.machines.isValidName(name)
}
