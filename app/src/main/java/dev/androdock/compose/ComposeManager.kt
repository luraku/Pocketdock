package dev.androdock.compose

import android.util.Log
import dev.androdock.docker.DockerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Projets Docker Compose exécutés DANS la VM.
 *
 * Les fichiers du projet sont déposés sur le disque de données de la VM
 * (/mnt/data/projects/<nom>), puis `docker compose` (paquet docker-cli-compose de la
 * VM) y est lancé via un conteneur utilitaire qui entre dans l'espace de noms de
 * la VM (`--pid=host` + chroot /proc/1/root). Ainsi les bind mounts relatifs
 * (`./data:/…`), les `build:` et le fichier `.env` fonctionnent comme sur un poste
 * Linux, sans partage de fichiers Android ↔ VM.
 */
class ComposeManager(
    private val docker: DockerClient,
    private val localDir: File,
) {
    companion object {
        const val TAG = "Compose"
        const val HELPER_IMAGE = "alpine:3.22"
        /** Configuration résolue et adaptée à la VM (healthchecks), écrite dans le dossier du projet. */
        const val ADAPTED_FILE = ".androdock-compose.json"
        const val VM_PROJECTS = "/mnt/data/projects"
        private const val MAX_OUTPUT = 256 * 1024
        private val NAME = Regex("^[a-z0-9][a-z0-9_-]{0,62}$")
        fun validName(n: String) = NAME.matches(n)

        /** Échappement pour sh -c. */
        fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }

    enum class State { RUNNING, SUCCEEDED, FAILED }

    class ComposeJob(val id: String, val project: String, val action: String, val command: String) {
        @Volatile var state = State.RUNNING
        @Volatile var exitCode: Int? = null
        @Volatile var output: String = ""
        @Volatile var error: String? = null
        val startedAt = System.currentTimeMillis()
        @Volatile var endedAt: Long? = null
        internal var runner: ComposeManager? = null

        internal suspend fun runStep(script: String, stderr: Boolean = true, record: Boolean = true) =
            runner!!.execInVm(this, script, stderr, record)

        internal fun note(line: String) { output = (output + line + "\n").takeLast(MAX_OUTPUT) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, ComposeJob>()
    private val seq = AtomicInteger(0)
    private val running = ConcurrentHashMap<String, Job>()

    fun job(id: String) = jobs[id]
    fun recentJobs(limit: Int = 20) = jobs.values.sortedByDescending { it.startedAt }.take(limit)

    /** Projets connus localement (dernière archive envoyée). */
    fun projects(): List<File> = (localDir.listFiles { f -> f.name.endsWith(".tar.gz") } ?: emptyArray())
        .sortedBy { it.name }

    /** Enregistre l'archive (.tar.gz du dossier du projet) et la dépose dans la VM. */
    suspend fun upload(name: String, archive: ByteArray) {
        require(validName(name)) { "nom de projet invalide (minuscules, chiffres, - et _)" }
        require(archive.size > 20 && archive[0] == 0x1f.toByte() && archive[1] == 0x8b.toByte()) { "archive .tar.gz attendue" }
        localDir.mkdirs()
        File(localDir, "$name.tar.gz").writeBytes(archive)
        docker.ensureImage(HELPER_IMAGE)
        // Conteneur utilitaire qui voit le dossier des projets de la VM : on y vide puis remplit le projet.
        val id = docker.createContainer(buildJsonObject {
            put("Image", HELPER_IMAGE)
            putJsonArray("Cmd") { listOf("sh", "-c", "rm -rf /projects/$name && mkdir -p /projects/$name").forEach { add(JsonPrimitive(it)) } }
            putJsonObject("HostConfig") { put("Binds", JsonArray(listOf(JsonPrimitive("$VM_PROJECTS:/projects")))) }
            putJsonObject("Labels") { put("dev.androdock.helper", "1") }
        })
        try {
            docker.start(id)
            val code = docker.waitContainer(id)
            check(code == 0) { "préparation du dossier du projet : code $code" }
            docker.putArchive(id, "/projects/$name", archive)
        } finally {
            runCatching { docker.removeContainer(id) }
        }
    }

    /** Lance `docker compose -p <nom> <args>` dans le dossier du projet, dans la VM. */
    fun run(project: String, action: String, args: List<String>): ComposeJob {
        require(validName(project)) { "nom de projet invalide" }
        require(running[project]?.isActive != true) { "une opération est déjà en cours sur $project" }
        val command = "docker compose -p $project " + args.joinToString(" ") { shq(it) }
        return launchJob(project, action, command) { job -> job.runStep("cd $VM_PROJECTS/$project && $command").first }
    }

    /**
     * `up` adapté à la VM émulée : configuration résolue par Compose, healthchecks allongés
     * ([HealthcheckAdapter]), puis démarrage depuis ce fichier. Le docker-compose.yml de
     * l'utilisateur n'est pas modifié.
     */
    fun up(project: String, build: Boolean, services: List<String>, adaptHealthchecks: Boolean = true): ComposeJob {
        require(validName(project)) { "nom de projet invalide" }
        require(running[project]?.isActive != true) { "une opération est déjà en cours sur $project" }
        val upArgs = listOf("up", "-d", "--remove-orphans") + (if (build) listOf("--build") else emptyList()) + services
        val dir = "$VM_PROJECTS/$project"
        return launchJob(project, "up", "docker compose up") { job ->
            var files = ""
            if (adaptHealthchecks) {
                val (code, config) = job.runStep("cd $dir && docker compose -p $project config --format json", stderr = false, record = false)
                if (code != 0) return@launchJob code
                val (adapted, changed) = HealthcheckAdapter.adapt(config)
                if (changed.isNotEmpty()) {
                    writeProjectFile(project, ADAPTED_FILE, adapted.toByteArray())
                    files = "-f $ADAPTED_FILE "
                    job.note("Pocketdock : healthchecks allongés pour la VM émulée (start_period ≥ 30 min) : ${changed.joinToString(", ")}")
                }
            }
            job.runStep("cd $dir && docker compose -p $project $files" + upArgs.joinToString(" ") { shq(it) }).first
        }
    }

    /** Supprime le projet : `down` (volumes compris si demandé) puis effacement des fichiers. */
    fun remove(project: String, volumes: Boolean): ComposeJob {
        require(validName(project)) { "nom de projet invalide" }
        File(localDir, "$project.tar.gz").delete()
        val down = "docker compose -p $project down --remove-orphans" + if (volumes) " --volumes" else ""
        val dir = "$VM_PROJECTS/$project"
        return launchJob(project, "remove", down) { job ->
            job.runStep("if [ -d $dir ]; then cd $dir && $down; cd /; fi; rm -rf $dir").first
        }
    }

    /** Écrit un fichier à la racine du projet, dans la VM (via un conteneur utilitaire et l'API archive). */
    private suspend fun writeProjectFile(project: String, name: String, content: ByteArray) {
        val tar = java.io.ByteArrayOutputStream()
        dev.androdock.runtime.Archives.TarGzWriter(tar).use { it.file(name, content.decodeToString()) }
        val id = docker.createContainer(buildJsonObject {
            put("Image", HELPER_IMAGE)
            putJsonArray("Cmd") { add(JsonPrimitive("true")) }
            putJsonObject("HostConfig") { put("Binds", JsonArray(listOf(JsonPrimitive("$VM_PROJECTS:/projects")))) }
            putJsonObject("Labels") { put("dev.androdock.helper", "1") }
        })
        try {
            docker.putArchive(id, "/projects/$project", tar.toByteArray())
        } finally {
            runCatching { docker.removeContainer(id) }
        }
    }

    private fun launchJob(project: String, action: String, command: String, body: suspend (ComposeJob) -> Int): ComposeJob {
        val job = ComposeJob("job-${seq.incrementAndGet()}", project, action, command)
        jobs[job.id] = job
        if (jobs.size > 50) recentJobs(50).map { it.id }.toSet().let { keep -> jobs.keys.retainAll(keep) }
        job.runner = this
        running[project] = scope.launch {
            try {
                docker.ensureImage(HELPER_IMAGE)
                val code = body(job)
                job.exitCode = code
                job.state = if (code == 0) State.SUCCEEDED else State.FAILED
            } catch (e: Exception) {
                Log.e(TAG, "tâche ${job.id}", e)
                job.error = e.message ?: e.javaClass.simpleName
                job.state = State.FAILED
            } finally {
                job.endedAt = System.currentTimeMillis()
            }
        }
        return job
    }

    /**
     * Exécute un script shell dans la racine de la VM. Conteneur utilitaire : chroot dans
     * /proc/1/root (visible grâce à --pid=host), où se trouvent docker, compose, le socket et /mnt/data.
     * Renvoie le code de sortie et la sortie ; si [record], la sortie est ajoutée à celle de la tâche.
     */
    internal suspend fun execInVm(job: ComposeJob, script: String, stderr: Boolean, record: Boolean): Pair<Int, String> = coroutineScope {
        val id = docker.createContainer(buildJsonObject {
            put("Image", HELPER_IMAGE)
            putJsonArray("Cmd") {
                val sh = if (stderr) "exec 2>&1; $script" else script
                listOf("chroot", "/proc/1/root", "/bin/sh", "-c", sh).forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("Env") { add(JsonPrimitive("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")); add(JsonPrimitive("HOME=/root")) }
            putJsonObject("HostConfig") { put("PidMode", "host"); put("Privileged", true) }
            putJsonObject("Labels") { put("dev.androdock.helper", "1"); put("dev.androdock.job", job.id) }
        })
        try {
            docker.start(id)
            val base = job.output
            val poller = launch {
                while (isActive) {
                    if (record) runCatching { job.output = (base + docker.logsSince(id, 0, stderr)).takeLast(MAX_OUTPUT) }
                    delay(2000)
                }
            }
            val code = docker.waitContainer(id)
            poller.cancel()
            val out = runCatching { docker.logsSince(id, 0, stderr) }.getOrDefault("")
            if (record) job.output = (base + out).takeLast(MAX_OUTPUT)
            code to out
        } finally {
            runCatching { docker.removeContainer(id) }
        }
    }
}
