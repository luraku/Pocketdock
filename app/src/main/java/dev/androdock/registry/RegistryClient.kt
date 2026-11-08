package dev.androdock.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class RegistryException(message: String, val status: Int = 0) : Exception(message)

/** Image non prise en charge par le téléchargement natif : on repasse par `docker pull`. */
class UnsupportedImageException(message: String) : Exception(message)

data class Layer(val digest: String, val mediaType: String, val size: Long)

data class ImageManifest(val configDigest: String, val layers: List<Layer>)

/**
 * Client minimal de l'API de distribution OCI / Docker Registry v2 : jeton anonyme (Docker Hub,
 * GHCR et la plupart des registres publics), manifestes et index multi-plateformes, blobs.
 */
class RegistryClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    /** Schéma des registres : https en production, http pour les tests. */
    private val scheme: String = "https",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun base(ref: ImageRef): HttpUrl = "$scheme://${ref.apiHost}/v2/${ref.repository}/".toHttpUrl()

    /** Exécute la requête, en obtenant un jeton anonyme si le registre le demande (401). */
    fun execute(ref: ImageRef, build: Request.Builder): Response {
        val key = "${ref.apiHost}/${ref.repository}"
        tokens[key]?.let { build.header("Authorization", "Bearer $it") }
        val first = http.newCall(build.build()).execute()
        if (first.code != 401) return first
        val challenge = first.header("WWW-Authenticate").orEmpty()
        first.close()
        val token = fetchToken(challenge, ref) ?: throw RegistryException("authentification refusée par ${ref.apiHost}", 401)
        tokens[key] = token
        val second = http.newCall(build.header("Authorization", "Bearer $token").build()).execute()
        if (second.code == 401 || second.code == 403) {
            second.close()
            throw RegistryException("accès refusé à ${ref.repository} (image privée ?)", second.code)
        }
        return second
    }

    private fun fetchToken(challenge: String, ref: ImageRef): String? {
        if (!challenge.startsWith("Bearer", ignoreCase = true)) return null
        val params = Regex("(\\w+)=\"([^\"]*)\"").findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = params["realm"] ?: return null
        val url = realm.toHttpUrl().newBuilder()
            .apply { params["service"]?.let { addQueryParameter("service", it) } }
            .addQueryParameter("scope", params["scope"] ?: "repository:${ref.repository}:pull")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            return (o["token"] ?: o["access_token"])?.jsonPrimitive?.content
        }
    }

    /** Manifeste de l'image pour linux/[arch] (index multi-plateformes résolu). */
    fun manifest(ref: ImageRef, arch: String = "arm64"): ImageManifest {
        var o = getManifest(ref, ref.reference)
        if (isIndex(o)) {
            val digest = pickPlatform(o, arch) ?: throw UnsupportedImageException("pas de variante linux/$arch pour $ref")
            o = getManifest(ref, digest)
        }
        val mediaType = o["mediaType"]?.jsonPrimitive?.content.orEmpty()
        // Schéma 1 (obsolète) : « application/vnd.docker.distribution.manifest.v1+json » ou « …v1+prettyjws ».
        // Attention, le manifeste OCI moderne s'appelle lui aussi « …image.manifest.v1+json ».
        if ((o["schemaVersion"] as? JsonPrimitive)?.content == "1" || mediaType.startsWith("application/vnd.docker.distribution.manifest.v1")) {
            throw UnsupportedImageException("manifeste de schéma 1 non pris en charge")
        }
        val config = o["config"]?.jsonObject?.get("digest")?.jsonPrimitive?.content
            ?: throw RegistryException("manifeste sans configuration")
        val layers = (o["layers"] as? JsonArray).orEmpty().map {
            val l = it.jsonObject
            Layer(l["digest"]!!.jsonPrimitive.content, l["mediaType"]?.jsonPrimitive?.content.orEmpty(), l["size"]?.jsonPrimitive?.longOrNull ?: -1)
        }
        return ImageManifest(config, layers)
    }

    private fun getManifest(ref: ImageRef, reference: String): JsonObject {
        val req = Request.Builder().url(base(ref).newBuilder().addPathSegment("manifests").addPathSegment(reference).build())
            .header("Accept", MANIFEST_TYPES.joinToString(", "))
        execute(ref, req).use { r ->
            if (r.code == 404) throw RegistryException("image introuvable : $ref", 404)
            if (!r.isSuccessful) throw RegistryException("manifeste de $ref : HTTP ${r.code}", r.code)
            return json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
        }
    }

    /** Ouvre le flux d'un blob (couche ou configuration) ; l'appelant le ferme. */
    fun blob(ref: ImageRef, digest: String): Response {
        val r = execute(ref, Request.Builder().url(base(ref).newBuilder().addPathSegment("blobs").addPathSegment(digest).build()))
        if (!r.isSuccessful) { r.close(); throw RegistryException("blob $digest : HTTP ${r.code}", r.code) }
        return r
    }

    fun blobBytes(ref: ImageRef, digest: String): ByteArray = blob(ref, digest).use { it.body!!.bytes() }

    companion object {
        val MANIFEST_TYPES = listOf(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
        )

        fun isIndex(o: JsonObject): Boolean {
            val t = o["mediaType"]?.jsonPrimitive?.content.orEmpty()
            return t.contains("index") || t.contains("manifest.list") || (o["manifests"] != null && o["layers"] == null)
        }

        /** Condensé du manifeste linux/[arch] dans un index (variante v8 ou absente pour arm64). */
        fun pickPlatform(index: JsonObject, arch: String): String? =
            index["manifests"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { m ->
                val p = m["platform"]?.jsonObject ?: return@firstOrNull false
                val variant = p["variant"]?.jsonPrimitive?.content
                p["os"]?.jsonPrimitive?.content == "linux" && p["architecture"]?.jsonPrimitive?.content == arch &&
                    (arch != "arm64" || variant == null || variant == "v8")
            }?.get("digest")?.jsonPrimitive?.content
    }
}
