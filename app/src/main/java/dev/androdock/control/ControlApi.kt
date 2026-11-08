package dev.androdock.control

import android.content.Context
import android.os.Build
import android.util.Log
import dev.androdock.AndrodockApp
import dev.androdock.BuildConfig
import dev.androdock.machines.DistroCatalog
import dev.androdock.runtime.ProcessRunner
import dev.androdock.service.EngineService
import dev.androdock.vm.DockerVm
import dev.androdock.vm.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * API de contrôle locale de Pocketdock (http://127.0.0.1:7777/v1/...).
 *
 * Permet de piloter l'application sans interface : scripts de test e2e (via
 * `adb forward tcp:7777 tcp:7777`), agents, outils tiers. Toutes les routes
 * sauf `/v1/health` exigent `Authorization: Bearer <jeton>` ; le jeton est dans
 * `files/control/token` (lisible par `adb shell run-as dev.androdock cat files/control/token`
 * sur un build debug). Référence complète : docs/control-api.md.
 */
class ControlApi(private val context: Context) {
    private val app = context.applicationContext as AndrodockApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var server: HttpServer? = null
    private val dockerProxy = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(15, TimeUnit.MINUTES).build()

    @Volatile var lastMachineError: String? = null
        private set

    val tokenFile = File(context.filesDir, "control/token")

    val token: String by lazy {
        tokenFile.parentFile?.mkdirs()
        if (tokenFile.isFile && tokenFile.length() > 0) tokenFile.readText().trim()
        else ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { tokenFile.writeText(it) }
    }

    /** Applications tierces autorisées (jeton et droits propres), après accord de l'utilisateur. */
    val clients = AppClients(File(context.filesDir, "control/clients.json")).also {
        it.onRequest = { r -> ConsentActivity.notify(context, r) }
    }

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start() {
        if (server != null) return
        token // génère le jeton
        server = runCatching { HttpServer(BuildConfig.CONTROL_PORT) { handle(it) } }
            .onFailure { Log.e(TAG, "Impossible d'ouvrir le port ${BuildConfig.CONTROL_PORT}", it) }
            .getOrNull()
        if (server != null) Log.i(TAG, "API de contrôle sur 127.0.0.1:${BuildConfig.CONTROL_PORT}")
    }

    @Synchronized
    fun stop() {
        server?.close(); server = null
    }

    // ------------------------------------------------------------------ routage

    private suspend fun handle(req: HttpRequest): HttpResponse {
        if (req.path == "/v1/health") return ok(buildJsonObject { put("ok", true) })
        if (req.method == "GET" && !req.path.startsWith("/v1/")) return staticFile(req.path)
        val seg = req.path.trim('/').split('/')
        if (seg.firstOrNull() != "v1") return error(404, "route inconnue")
        // Demande d'accès d'une application : seule route sans jeton (l'utilisateur doit l'accepter).
        if (seg.getOrNull(1) == "pair") return try { pair(req, seg.drop(2)) } catch (e: IllegalArgumentException) { error(400, e.message ?: "requête invalide") }

        val auth = req.header("authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        if (auth != token) {
            val client = clients.authenticate(auth) ?: return error(401, "jeton invalide ou absent")
            val need = AppClients.requiredScope(req.method, seg.drop(1))
            if (need == null || !client.has(need)) {
                return HttpResponse.json(403, buildJsonObject {
                    put("error", if (need == null) "réservé au tableau de bord de Pocketdock" else "droit « ${need.id} » requis")
                    need?.let { put("required", it.id) }
                }.toString())
            }
        }
        return try {
            route(req, seg.drop(1))
        } catch (e: IllegalArgumentException) {
            error(400, e.message ?: "requête invalide")
        } catch (e: IllegalStateException) {
            error(503, e.message ?: "indisponible")
        } catch (e: dev.androdock.docker.DockerException) {
            error(if (e.code in 400..599) e.code else 502, e.message ?: "erreur Docker")
        }
    }

    private suspend fun route(req: HttpRequest, s: List<String>): HttpResponse {
        val m = req.method
        return when {
            m == "GET" && s == listOf("status") -> ok(status())

            m == "POST" && s == listOf("engine", "start") -> { startEngine(); accepted("démarrage demandé") }
            m == "POST" && s == listOf("engine", "stop") -> { app.vm.stop(); accepted("arrêt demandé") }
            m == "GET" && s == listOf("engine", "console") -> {
                val tail = req.query["tail"]?.toIntOrNull() ?: 200
                ok(buildJsonObject { putJsonArray("lines") { app.vm.console.value.takeLast(tail).forEach { add(JsonPrimitive(it)) } } })
            }
            m == "POST" && s == listOf("engine", "reset") -> {
                scope.launch { app.vm.resetData() }; accepted("réinitialisation demandée")
            }

            s.firstOrNull() == "docker" -> proxyDocker(req, s.drop(1))

            m == "POST" && s == listOf("exec") -> {
                val body = obj(req)
                val cmd = body.str("cmd") ?: throw IllegalArgumentException("champ cmd requis")
                val shell = app.machines.dockerShell()
                execResponse(shell.copy(args = listOf("sh", "-c", cmd)), body)
            }

            m == "POST" && s == listOf("containers", "run") -> {
                val b = obj(req)
                fun list(k: String) = (b[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                val spec = dev.androdock.docker.RunSpec(
                    image = b.str("image") ?: throw IllegalArgumentException("champ image requis"),
                    name = b.str("name"), ports = list("ports"), env = list("env"), volumes = list("volumes"),
                    command = b.str("command"), restartAlways = b.bool("restartAlways") == true,
                )
                requireEngine()
                app.puller.ensure(spec.image)
                val id = app.vm.docker.run(spec)
                HttpResponse.json(201, buildJsonObject { put("id", id) }.toString())
            }
            m == "POST" && s == listOf("images", "pull") -> {
                val b = obj(req)
                val ref = b.str("ref") ?: throw IllegalArgumentException("champ ref requis")
                requireEngine()
                // Téléchargement natif (Android) par défaut, repli sur docker pull ; "native": false pour forcer docker pull.
                val o = app.puller.pull(ref, preferNative = b.bool("native") != false)
                ok(buildJsonObject { put("pulled", ref); put("method", o.method); put("ms", o.ms); put("detail", o.detail) })
            }

            // ---- Docker Compose (projets exécutés dans la VM)
            m == "GET" && s == listOf("compose") -> ok(composeList())
            m == "PUT" && s.size == 2 && s[0] == "compose" -> {
                requireEngine()
                app.compose.upload(s[1], req.body)
                HttpResponse.json(201, buildJsonObject { put("uploaded", s[1]); put("bytes", req.body.size) }.toString())
            }
            m == "POST" && s.size == 3 && s[0] == "compose" -> {
                requireEngine()
                val b = obj(req)
                fun list(k: String) = (b[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                if (s[2] == "up") {
                    val job = app.compose.up(s[1], b.bool("build") == true, list("services"), b.bool("adaptHealthchecks") != false)
                    return HttpResponse.json(202, jobJson(job, 4000).toString())
                }
                val args = when (s[2]) {
                    "down" -> listOf("down", "--remove-orphans") + (if (b.bool("volumes") == true) listOf("--volumes") else emptyList())
                    "restart", "stop", "start", "pull" -> listOf(s[2]) + list("services")
                    "cmd" -> list("args").ifEmpty { throw IllegalArgumentException("champ args requis") }
                    else -> throw IllegalArgumentException("action inconnue : ${s[2]}")
                }
                HttpResponse.json(202, jobJson(app.compose.run(s[1], s[2], args), 4000).toString())
            }
            m == "DELETE" && s.size == 2 && s[0] == "compose" -> {
                requireEngine()
                HttpResponse.json(202, jobJson(app.compose.remove(s[1], req.query["volumes"] == "1"), 4000).toString())
            }
            m == "GET" && s == listOf("jobs") -> ok(buildJsonObject {
                putJsonArray("items") { app.compose.recentJobs().forEach { add(jobJson(it, 0)) } }
            })
            m == "GET" && s.size == 2 && s[0] == "jobs" -> {
                val j = app.compose.job(s[1]) ?: return error(404, "tâche inconnue")
                ok(jobJson(j, req.query["tail"]?.toIntOrNull() ?: 64_000))
            }

            m == "GET" && s == listOf("machines") -> ok(machines())
            m == "POST" && s == listOf("machines") -> createMachine(obj(req))
            m == "DELETE" && s.size == 2 && s[0] == "machines" -> {
                requireMachine(s[1]); app.machines.delete(s[1]); ok(buildJsonObject { put("deleted", s[1]) })
            }
            m == "POST" && s.size == 3 && s[0] == "machines" && s[2] == "exec" -> {
                requireMachine(s[1])
                val body = obj(req)
                val cmd = body.str("cmd") ?: throw IllegalArgumentException("champ cmd requis")
                execResponse(app.machines.shell(s[1], cmd), body)
            }

            // ---- Applications autorisées (jeton principal uniquement, voir requiredScope)
            m == "GET" && s == listOf("clients") -> ok(clientsJson())
            m == "POST" && s.size == 4 && s[0] == "clients" && s[1] == "pending" && s[3] == "approve" -> {
                val ids = (obj(req)["scopes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                val r = clients.pairing(s[2]) ?: return error(404, "demande inconnue ou expirée")
                try { clients.approve(s[2], ids?.let { AppClients.parseScopes(it) } ?: r.scopes) } catch (e: NoSuchElementException) { return error(404, e.message ?: "demande inconnue") }
                ok(clientsJson())
            }
            m == "POST" && s.size == 4 && s[0] == "clients" && s[1] == "pending" && s[3] == "deny" -> {
                try { clients.deny(s[2]) } catch (e: NoSuchElementException) { return error(404, e.message ?: "demande inconnue") }
                ok(clientsJson())
            }
            m == "DELETE" && s.size == 2 && s[0] == "clients" ->
                if (clients.revoke(s[1])) ok(clientsJson()) else error(404, "application inconnue")

            m == "GET" && s == listOf("settings") -> ok(settings())
            (m == "PUT" || m == "PATCH") && s == listOf("settings") -> {
                updateSettings(obj(req))
                // Désactiver l'API depuis l'API : on répond d'abord, on ferme ensuite.
                if (!app.settings.config.value.controlApi) scope.launch { kotlinx.coroutines.delay(500); stop() }
                ok(settings())
            }

            else -> error(404, "route inconnue : $m /v1/${s.joinToString("/")}")
        }
    }

    // ------------------------------------------------------------------ applications tierces

    /**
     * POST /v1/pair {"name","scopes":[…]} → demande en attente de l'accord de l'utilisateur ;
     * GET /v1/pair/<id> → PENDING, puis APPROVED avec le jeton (remis une seule fois), DENIED ou EXPIRED.
     */
    private fun pair(req: HttpRequest, s: List<String>): HttpResponse = when {
        req.method == "POST" && s.isEmpty() -> {
            val b = obj(req)
            val ids = (b["scopes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            try {
                val r = clients.request(b.str("name") ?: throw IllegalArgumentException("champ name requis"), AppClients.parseScopes(ids))
                HttpResponse.json(202, buildJsonObject {
                    put("id", r.id); put("state", r.state.name); put("poll", "/v1/pair/${r.id}")
                    put("message", "Demande envoyée : l'utilisateur doit l'accepter sur le téléphone (5 minutes).")
                }.toString())
            } catch (e: IllegalStateException) { error(429, e.message ?: "trop de demandes") }
        }
        req.method == "GET" && s.size == 1 -> {
            val r = clients.collect(s[0]) ?: return error(404, "demande inconnue ou déjà relevée")
            ok(buildJsonObject {
                put("id", r.id); put("state", r.state.name)
                r.token?.let { put("token", it) }
                if (r.state == PairRequest.State.APPROVED) putJsonArray("scopes") { r.grantedScopes.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.id)) } }
            })
        }
        else -> error(404, "route inconnue")
    }

    private fun clientsJson() = buildJsonObject {
        putJsonArray("clients") {
            clients.list().forEach { c ->
                add(buildJsonObject {
                    put("id", c.id); put("name", c.name); put("createdAt", c.createdAt); put("lastUsedAt", c.lastUsedAt)
                    putJsonArray("scopes") { c.scopes.forEach { add(JsonPrimitive(it)) } }
                })
            }
        }
        putJsonArray("pending") {
            clients.pendingRequests().forEach { r ->
                add(buildJsonObject {
                    put("id", r.id); put("name", r.name); put("createdAt", r.createdAt)
                    putJsonArray("scopes") { r.scopes.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.id)) } }
                })
            }
        }
        putJsonArray("scopes") {
            Scope.entries.forEach { sc -> add(buildJsonObject { put("id", sc.id); put("label", sc.label) }) }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun startEngine() {
        try {
            EngineService.start(context)
        } catch (e: Exception) {
            // Démarrage de service de premier plan refusé (app en arrière-plan) : VM sans service.
            Log.w(TAG, "Service de premier plan refusé, démarrage direct de la VM", e)
            app.vm.start()
        }
    }

    private fun status(): JsonObject {
        val st = app.vm.status.value
        return buildJsonObject {
            put("version", BuildConfig.VERSION_NAME)
            put("abi", app.runtime.abi ?: "aucune")
            put("deviceAbis", Build.SUPPORTED_ABIS.joinToString(","))
            put("nativeBundled", app.runtime.isBundled)
            put("android", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("engine", buildJsonObject {
                put("state", st.state.name)
                put("message", st.message)
                st.progress?.let { put("progress", it) }
                st.dockerVersion?.let { put("dockerVersion", it) }
                putJsonArray("forwardedPorts") { st.forwardedPorts.forEach { add(JsonPrimitive(it)) } }
                put("dockerHost", "tcp://127.0.0.1:${DockerVm.DOCKER_PORT}")
                st.accel?.let { put("accel", it.name.lowercase()) }
                st.accelInfo?.let { put("accelInfo", it) }
                put("diskUsageBytes", app.vm.diskUsageBytes())
                put("startedAt", app.vm.startedAtMs)
                app.vm.qemuMetrics()?.let { (cpu, rss) -> put("cpuPercent", cpu); put("rssBytes", rss) }
                val cfg = app.settings.config.value
                put("cpus", cfg.cpus); put("memoryMb", cfg.memoryMb); put("diskGb", cfg.diskGb)
            })
            put("machines", machines())
        }
    }

    private fun machines(): JsonObject = buildJsonObject {
        putJsonArray("items") {
            app.machines.machines.value.forEach { mc ->
                add(buildJsonObject {
                    put("name", mc.name); put("distro", mc.distroId); put("label", mc.distroLabel); put("createdAt", mc.createdAt)
                })
            }
        }
        app.machines.installing.value?.let { i ->
            put("installing", buildJsonObject {
                put("name", i.name); put("message", i.message); i.progress?.let { put("progress", it) }
            })
        }
        lastMachineError?.let { put("lastError", it) }
        putJsonArray("distros") {
            (DistroCatalog.all + DistroCatalog.custom).forEach { d ->
                add(buildJsonObject { put("id", d.id); put("label", d.label) })
            }
        }
    }

    private fun createMachine(body: JsonObject): HttpResponse {
        val name = body.str("name") ?: throw IllegalArgumentException("champ name requis")
        val distro = DistroCatalog.byId(body.str("distro") ?: "alpine")
            ?: throw IllegalArgumentException("distribution inconnue")
        if (!app.machines.isValidName(name)) return error(409, "nom invalide ou déjà utilisé")
        if (app.machines.installing.value != null) return error(409, "une installation est déjà en cours")
        lastMachineError = null
        scope.launch {
            runCatching { app.machines.create(name, distro, body.str("url")) }
                .onFailure { lastMachineError = "$name : ${it.message}"; Log.e(TAG, "création de $name", it) }
        }
        return accepted("création de $name lancée (suivre GET /v1/machines)")
    }

    private fun jobJson(j: dev.androdock.compose.ComposeManager.ComposeJob, tail: Int) = buildJsonObject {
        put("id", j.id); put("project", j.project); put("action", j.action); put("state", j.state.name)
        j.exitCode?.let { put("exitCode", it) }
        j.error?.let { put("error", it) }
        put("startedAt", j.startedAt); j.endedAt?.let { put("endedAt", it) }
        if (tail > 0) put("output", j.output.takeLast(tail))
    }

    private fun composeList() = buildJsonObject {
        putJsonArray("projects") {
            app.compose.projects().forEach { f ->
                add(buildJsonObject {
                    put("name", f.name.removeSuffix(".tar.gz")); put("uploadedAt", f.lastModified()); put("archiveBytes", f.length())
                })
            }
        }
        putJsonArray("jobs") { app.compose.recentJobs(10).forEach { add(jobJson(it, 0)) } }
    }

    private fun requireEngine() {
        if (app.vm.status.value.state != EngineState.RUNNING) throw IllegalStateException("moteur Docker non démarré")
    }

    /** Fichiers du tableau de bord web (assets/web), servis sans jeton : ils ne contiennent aucune donnée. */
    private fun staticFile(path: String): HttpResponse {
        val rel = path.trimStart('/').ifEmpty { "index.html" }
        if (rel.contains("..")) return error(404, "introuvable")
        val bytes = runCatching { context.assets.open("web/$rel").use { it.readBytes() } }.getOrNull()
            ?: return error(404, "introuvable : $rel")
        val type = when (rel.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"; "svg" -> "image/svg+xml"; "json" -> "application/json"
            else -> "application/octet-stream"
        }
        return HttpResponse.raw(200, bytes, type)
    }

    private fun requireMachine(name: String) {
        if (app.machines.machines.value.none { it.name == name }) throw IllegalArgumentException("machine inconnue : $name")
    }

    private suspend fun execResponse(cmd: dev.androdock.machines.ShellCommand, body: JsonObject): HttpResponse {
        val timeout = body["timeoutSec"]?.jsonPrimitive?.longOrNull ?: 300
        val r = ProcessRunner.run(cmd, timeout, body.str("stdin"))
        return ok(buildJsonObject {
            put("exitCode", r.exitCode); put("timedOut", r.timedOut); put("durationMs", r.durationMs)
            put("stdout", r.stdout); put("stderr", r.stderr)
        })
    }

    private fun settings(): JsonObject {
        val c = app.settings.config.value
        return buildJsonObject {
            put("cpus", c.cpus); put("memoryMb", c.memoryMb); put("diskGb", c.diskGb)
            put("exposeOnLan", c.exposeOnLan); put("keepAwake", c.keepAwake); put("autoStart", c.autoStart)
            put("hwAccel", c.hwAccel)
            put("controlApi", c.controlApi)
        }
    }

    private fun updateSettings(b: JsonObject) {
        app.settings.update { c ->
            c.copy(
                cpus = b.int("cpus")?.coerceIn(1, 16) ?: c.cpus,
                memoryMb = b.int("memoryMb")?.coerceIn(512, 16384) ?: c.memoryMb,
                diskGb = b.int("diskGb")?.coerceIn(4, 512) ?: c.diskGb,
                exposeOnLan = b.bool("exposeOnLan") ?: c.exposeOnLan,
                hwAccel = b.bool("hwAccel") ?: c.hwAccel,
                keepAwake = b.bool("keepAwake") ?: c.keepAwake,
                autoStart = b.bool("autoStart") ?: c.autoStart,
                controlApi = b.bool("controlApi") ?: c.controlApi,
            )
        }
    }

    /** Relais transparent vers l'API Docker Engine de la VM : /v1/docker/<chemin> → http://127.0.0.1:2375/<chemin>. */
    private suspend fun proxyDocker(req: HttpRequest, rest: List<String>): HttpResponse = withContext(Dispatchers.IO) {
        if (app.vm.status.value.state != EngineState.RUNNING) return@withContext error(503, "moteur Docker non démarré")
        val qs = if (req.query.isEmpty()) "" else "?" + req.query.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        val url = "http://127.0.0.1:${DockerVm.DOCKER_PORT}/" + rest.joinToString("/") + qs
        val type = (req.header("content-type") ?: "application/json").toMediaTypeOrNull()
        val body = if (req.method in setOf("POST", "PUT", "PATCH")) req.body.toRequestBody(type)
        else if (req.method == "DELETE" && req.body.isNotEmpty()) req.body.toRequestBody(type) else null
        val r = Request.Builder().url(url).method(req.method, body).build()
        dockerProxy.newCall(r).execute().use { resp ->
            HttpResponse.raw(resp.code, resp.body?.bytes() ?: ByteArray(0), resp.header("Content-Type") ?: "application/json")
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private fun obj(req: HttpRequest): JsonObject =
        if (req.body.isEmpty()) JsonObject(emptyMap())
        else runCatching { json.parseToJsonElement(req.bodyText).jsonObject }
            .getOrElse { throw IllegalArgumentException("corps JSON invalide") }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun ok(body: JsonElement) = HttpResponse.json(200, body.toString())
    private fun accepted(msg: String) = HttpResponse.json(202, buildJsonObject { put("accepted", true); put("message", msg) }.toString())
    private fun error(code: Int, msg: String) = HttpResponse.json(code, buildJsonObject { put("error", msg) }.toString())

    companion object { const val TAG = "ControlApi" }
}
