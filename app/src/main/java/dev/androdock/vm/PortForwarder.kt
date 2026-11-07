package dev.androdock.vm

import android.util.Log
import dev.androdock.docker.DockerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Redirection automatique des ports publiés (à la manière d'OrbStack) :
 * dès qu'un conteneur publie « -p 8080:80 », le port 8080 de la VM est
 * redirigé vers 127.0.0.1:8080 sur Android (ou 0.0.0.0 si l'option LAN est
 * activée), via la commande hostfwd_add du moniteur QEMU.
 */
class PortForwarder(
    private val docker: DockerClient,
    private val monitor: QemuMonitor,
    private val settings: EngineSettings,
    private val onChange: (List<String>) -> Unit,
) {
    private data class Fwd(val proto: String, val addr: String, val port: Int)

    private val active = mutableSetOf<Fwd>()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                runCatching { sync() }.onFailure { Log.w(DockerVm.TAG, "Synchronisation des ports : ${it.message}") }
                delay(3000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        active.clear()
    }

    private suspend fun sync() {
        val addr = if (settings.config.value.exposeOnLan) "0.0.0.0" else "127.0.0.1"
        val wanted = docker.containers()
            .filter { it.running }
            .flatMap { c -> c.ports.mapNotNull { p -> p.publicPort?.let { Fwd(p.type, addr, it) } } }
            .filter { it.port != DockerVm.DOCKER_PORT }
            .toSet()

        for (f in active - wanted) {
            monitor.removeForward(f.proto, f.addr, f.port)
            active -= f
        }
        for (f in wanted - active) {
            val out = monitor.addForward(f.proto, f.addr, f.port, f.port)
            if (out.contains("Could not", ignoreCase = true) || out.contains("error", ignoreCase = true)) {
                Log.w(DockerVm.TAG, "hostfwd ${f.port} : $out")
            } else {
                active += f
            }
        }
        onChange(active.sortedBy { it.port }.map { "${it.addr}:${it.port}/${it.proto}" })
    }
}
