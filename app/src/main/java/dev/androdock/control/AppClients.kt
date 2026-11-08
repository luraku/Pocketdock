package dev.androdock.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Droits d'une application tierce sur l'API de contrôle, du plus faible au plus fort. */
enum class Scope(val id: String, val label: String) {
    READ("read", "Consulter l'état du moteur, les conteneurs, les stacks et les journaux"),
    RUN("run", "Télécharger des images, lancer des conteneurs et des stacks, démarrer le moteur"),
    MANAGE("manage", "Arrêter, redémarrer et supprimer des conteneurs et des stacks"),
    ADMIN("admin", "Tout le reste : commandes, API Docker complète, machines, réglages, arrêt et réinitialisation du moteur");

    companion object {
        fun parse(id: String): Scope? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

@Serializable
data class AppClient(
    val id: String,
    val name: String,
    val tokenSha256: String,
    val scopes: List<String>,
    val createdAt: Long,
    val lastUsedAt: Long = 0,
) {
    /** « admin » vaut tous les droits ; les autres sont indépendants. */
    fun has(scope: Scope) = scope.id in scopes || Scope.ADMIN.id in scopes
}

/** Demande d'accès en attente de l'accord de l'utilisateur (en mémoire seulement). */
data class PairRequest(
    val id: String,
    val name: String,
    val scopes: Set<Scope>,
    val createdAt: Long,
    @Volatile var state: State = State.PENDING,
    /** Jeton remis une seule fois à l'application, au premier relevé après accord. */
    @Volatile var token: String? = null,
    @Volatile var grantedScopes: Set<Scope> = emptySet(),
) {
    enum class State { PENDING, APPROVED, DENIED, EXPIRED }
}

/**
 * Applications autorisées à piloter Pocketdock, chacune avec son jeton et ses droits.
 *
 * Seule l'empreinte SHA-256 des jetons est enregistrée (`files/control/clients.json`) : le jeton
 * n'est connu que de l'application, à qui il est remis une seule fois après l'accord de
 * l'utilisateur. Le jeton principal (`files/control/token`, tableau de bord) n'est pas concerné.
 */
class AppClients(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val random = SecureRandom()
    private val pending = LinkedHashMap<String, PairRequest>()
    private var clients: MutableList<AppClient> = load()

    /** Appelé à chaque nouvelle demande (notification à l'utilisateur). */
    @Volatile var onRequest: ((PairRequest) -> Unit)? = null

    @Synchronized fun list(): List<AppClient> = clients.toList()

    @Synchronized fun pendingRequests(): List<PairRequest> {
        expire(); return pending.values.filter { it.state == PairRequest.State.PENDING }
    }

    /** Application correspondant à ce jeton, ou null. Met à jour la date de dernière utilisation. */
    @Synchronized fun authenticate(token: String): AppClient? {
        if (!token.startsWith(TOKEN_PREFIX)) return null
        val h = sha256(token)
        val i = clients.indexOfFirst { MessageDigest.isEqual(it.tokenSha256.toByteArray(), h.toByteArray()) }
        if (i < 0) return null
        val c = clients[i]
        if (now() - c.lastUsedAt > 60_000) { clients[i] = c.copy(lastUsedAt = now()); save() }
        return clients[i]
    }

    /** Nouvelle demande d'accès ; refusée s'il y en a déjà trop en attente (anti-spam). */
    @Synchronized fun request(name: String, scopes: Set<Scope>): PairRequest {
        expire()
        val clean = name.replace(Regex("[\\p{Cntrl}]"), "").trim().take(MAX_NAME)
        require(clean.isNotEmpty()) { "champ name requis" }
        require(scopes.isNotEmpty()) { "champ scopes requis (read, run, manage, admin)" }
        check(pending.values.count { it.state == PairRequest.State.PENDING } < MAX_PENDING) { "trop de demandes en attente" }
        val r = PairRequest(id = randomHex(8), name = clean, scopes = scopes, createdAt = now())
        pending[r.id] = r
        onRequest?.invoke(r)
        return r
    }

    @Synchronized fun pairing(id: String): PairRequest? { expire(); return pending[id] }

    /** Relevé par l'application : remet le jeton une seule fois, puis oublie la demande. */
    @Synchronized fun collect(id: String): PairRequest? {
        expire()
        val r = pending[id] ?: return null
        if (r.state != PairRequest.State.PENDING) pending.remove(id)
        return r.copy().also { if (r.state == PairRequest.State.APPROVED) r.token = null }
    }

    /** Accord de l'utilisateur, éventuellement avec moins de droits que demandé. */
    @Synchronized fun approve(id: String, granted: Set<Scope>): AppClient {
        expire()
        val r = pending[id]?.takeIf { it.state == PairRequest.State.PENDING } ?: throw NoSuchElementException("demande inconnue ou expirée")
        val scopes = granted.intersect(r.scopes).ifEmpty { throw IllegalArgumentException("aucun droit accordé") }
        val token = TOKEN_PREFIX + randomHex(24)
        val client = AppClient(id = randomHex(6), name = r.name, tokenSha256 = sha256(token),
            scopes = scopes.sortedBy { it.ordinal }.map { it.id }, createdAt = now())
        clients.add(client); save()
        r.token = token; r.grantedScopes = scopes; r.state = PairRequest.State.APPROVED
        return client
    }

    @Synchronized fun deny(id: String) {
        val r = pending[id]?.takeIf { it.state == PairRequest.State.PENDING } ?: throw NoSuchElementException("demande inconnue ou expirée")
        r.state = PairRequest.State.DENIED
    }

    @Synchronized fun revoke(id: String): Boolean {
        val removed = clients.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    private fun expire() {
        val t = now()
        pending.values.forEach { if (it.state == PairRequest.State.PENDING && t - it.createdAt > PENDING_TTL_MS) it.state = PairRequest.State.EXPIRED }
        pending.entries.removeAll { t - it.value.createdAt > 2 * PENDING_TTL_MS }
    }

    private fun load(): MutableList<AppClient> = runCatching {
        if (file.isFile) json.decodeFromString<List<AppClient>>(file.readText()).toMutableList() else mutableListOf()
    }.getOrElse { mutableListOf() }

    private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(clients.toList()))
        tmp.renameTo(file)
    }

    private fun randomHex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        const val TOKEN_PREFIX = "pdk_"
        const val MAX_PENDING = 3
        const val MAX_NAME = 60
        const val PENDING_TTL_MS = 5 * 60_000L

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun parseScopes(ids: List<String>): Set<Scope> =
            ids.map { Scope.parse(it) ?: throw IllegalArgumentException("droit inconnu : $it (read, run, manage, admin)") }.toSet()

        /**
         * Droit nécessaire pour une requête de l'API de contrôle (segments après /v1/).
         * null : réservé au jeton principal (gestion des applications elles-mêmes).
         */
        fun requiredScope(method: String, s: List<String>): Scope? {
            val m = method.uppercase()
            val first = s.firstOrNull()
            return when {
                first == "clients" -> null
                first == "docker" -> when {
                    m == "GET" || m == "HEAD" -> Scope.READ
                    // Cycle de vie d'un conteneur existant : gérer. Le reste de l'API Docker : administrer.
                    s.size == 4 && s[1] == "containers" && m == "POST" && s[3] in setOf("start", "stop", "restart", "kill", "pause", "unpause") -> Scope.MANAGE
                    s.size == 3 && s[1] == "containers" && m == "DELETE" -> Scope.MANAGE
                    else -> Scope.ADMIN
                }
                m == "GET" || m == "HEAD" -> Scope.READ
                s == listOf("engine", "start") -> Scope.RUN
                s == listOf("containers", "run") || s == listOf("images", "pull") -> Scope.RUN
                first == "compose" && m == "PUT" && s.size == 2 -> Scope.RUN
                first == "compose" && m == "POST" && s.size == 3 && s[2] in setOf("up", "start", "pull", "restart") -> Scope.RUN
                first == "compose" && m == "POST" && s.size == 3 && s[2] in setOf("stop", "down") -> Scope.MANAGE
                first == "compose" && m == "DELETE" -> Scope.MANAGE
                else -> Scope.ADMIN // exec, compose cmd, machines, réglages, arrêt et réinitialisation du moteur
            }
        }
    }
}
