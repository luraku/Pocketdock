package dev.androdock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.androdock.compose.ComposeManager
import dev.androdock.control.ControlApi
import dev.androdock.machines.MachineManager
import dev.androdock.runtime.NativeRuntime
import dev.androdock.vm.DockerVm
import dev.androdock.vm.EngineSettings

class AndrodockApp : Application() {
    lateinit var runtime: NativeRuntime private set
    lateinit var settings: EngineSettings private set
    lateinit var vm: DockerVm private set
    lateinit var machines: MachineManager private set
    lateinit var control: ControlApi private set
    lateinit var compose: ComposeManager private set
    lateinit var puller: dev.androdock.registry.ImagePuller private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        runtime = NativeRuntime(this)
        settings = EngineSettings(this)
        vm = DockerVm(this, runtime, settings)
        machines = MachineManager(this, runtime)
        puller = dev.androdock.registry.ImagePuller(vm.docker, cacheDir)
        // Pré-téléchargement natif des images Compose désactivé : plus lent que docker pull (TSK-030, run 58).
        compose = ComposeManager(vm.docker, java.io.File(filesDir, "compose"), puller = null)
        control = ControlApi(this)
        if (settings.config.value.controlApi) control.start()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, "Moteur", NotificationManager.IMPORTANCE_LOW).apply {
                description = "État du moteur Docker et des machines"
            }
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ACCESS, "Demandes d'accès", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Applications qui demandent à piloter Pocketdock"
            }
        )
    }

    companion object {
        const val CHANNEL_ENGINE = "engine"
        const val CHANNEL_ACCESS = "access"
        lateinit var instance: AndrodockApp private set
    }
}
