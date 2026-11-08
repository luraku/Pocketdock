package dev.androdock.vm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EngineConfig(
    val cpus: Int = 4,
    val memoryMb: Int = 2048,
    val diskGb: Int = 32,
    /** Exposer les ports des conteneurs sur le réseau local (0.0.0.0) et non sur 127.0.0.1 seulement. */
    val exposeOnLan: Boolean = false,
    /** Garder le processeur éveillé tant que le moteur tourne. */
    val keepAwake: Boolean = true,
    /** Démarrer le moteur Docker à l'ouverture de l'application. */
    val autoStart: Boolean = false,
    /** API de contrôle locale (http://127.0.0.1:7777). Active par défaut sur les builds debug. */
    val controlApi: Boolean = false,
    /** Accélération matérielle (KVM) si /dev/kvm est utilisable, avec repli logiciel automatique. */
    val hwAccel: Boolean = true,
)

class EngineSettings(context: Context) {
    private val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<EngineConfig> = _config

    private fun load() = EngineConfig(
        cpus = prefs.getInt("cpus", minOf(4, Runtime.getRuntime().availableProcessors())),
        memoryMb = prefs.getInt("memoryMb", 2048),
        diskGb = prefs.getInt("diskGb", 32),
        exposeOnLan = prefs.getBoolean("exposeOnLan", false),
        keepAwake = prefs.getBoolean("keepAwake", true),
        autoStart = prefs.getBoolean("autoStart", false),
        controlApi = prefs.getBoolean("controlApi", dev.androdock.BuildConfig.DEBUG),
        hwAccel = prefs.getBoolean("hwAccel", true),
    )

    fun update(transform: (EngineConfig) -> EngineConfig) {
        val c = transform(_config.value)
        prefs.edit()
            .putInt("cpus", c.cpus).putInt("memoryMb", c.memoryMb).putInt("diskGb", c.diskGb)
            .putBoolean("exposeOnLan", c.exposeOnLan).putBoolean("keepAwake", c.keepAwake)
            .putBoolean("autoStart", c.autoStart)
            .putBoolean("controlApi", c.controlApi)
            .putBoolean("hwAccel", c.hwAccel)
            .apply()
        _config.value = c
    }
}
