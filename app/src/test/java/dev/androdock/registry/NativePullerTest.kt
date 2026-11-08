package dev.androdock.registry

import dev.androdock.docker.DockerClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class NativePullerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val registry = MockWebServer()
    private val docker = MockWebServer()
    private val loaded = ByteArrayOutputStream()
    private var imagePresent = false
    private var corruptLayer = false

    private fun sha(b: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    // Une couche tar minimale (contenu arbitraire : seul l'octet compte pour les empreintes)
    private val layerTar = ByteArray(10_000) { (it % 251).toByte() }
    private val layerGz = gzip(layerTar)
    private val config = """{"architecture":"arm64","os":"linux","rootfs":{"type":"layers","diff_ids":["${sha(layerTar)}"]}}""".toByteArray()
    private val manifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
        "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"${sha(config)}","size":${config.size}},
        "layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"${sha(layerGz)}","size":${layerGz.size}}]}""".toByteArray()
    private val index = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
        {"digest":"sha256:amd","platform":{"os":"linux","architecture":"amd64"}},
        {"digest":"${sha(manifest)}","platform":{"os":"linux","architecture":"arm64","variant":"v8"}}]}"""

    @Before fun setUp() {
        registry.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                if (path.startsWith("/token")) return MockResponse().setBody("""{"token":"t0k"}""")
                if (request.getHeader("Authorization") != "Bearer t0k") {
                    return MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", "Bearer realm=\"http://${registry.hostName}:${registry.port}/token\",service=\"test\"")
                }
                return when {
                    path.endsWith("/manifests/1.0") -> MockResponse().setBody(index)
                    path.endsWith("/manifests/${sha(manifest)}") -> MockResponse().setBody(Buffer().write(manifest))
                    path.endsWith("/blobs/${sha(config)}") -> MockResponse().setBody(Buffer().write(config))
                    path.endsWith("/blobs/${sha(layerGz)}") -> MockResponse().setBody(Buffer().write(if (corruptLayer) gzip(ByteArray(10)) else layerGz))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        docker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/images/${sha(config)}/json") ->
                    if (imagePresent) MockResponse().setBody("{}") else MockResponse().setResponseCode(404).setBody("""{"message":"absente"}""")
                request.path!!.startsWith("/images/load") -> { request.body.copyTo(loaded); MockResponse().setBody("""{"stream":"Loaded image: demo:1.0\n"}""") }
                request.path!!.contains("/tag") -> MockResponse().setResponseCode(201)
                else -> MockResponse().setResponseCode(404)
            }
        }
        registry.start(); docker.start()
    }

    @After fun tearDown() { registry.shutdown(); docker.shutdown() }

    private fun puller() = NativePuller(DockerClient(docker.port), tmp.newFolder("work"), RegistryClient(scheme = "http"))
    private fun ref() = "${registry.hostName}:${registry.port}/team/demo:1.0"

    @Test fun pullsArm64DecompressesAndLoads() = runBlocking {
        val r = puller().pull(ref())
        assertEquals(sha(config), r.imageId)
        assertFalse(r.alreadyPresent)
        assertEquals(layerTar.size.toLong(), r.bytes)
        // Archive docker save reçue par le démon
        val entries = HashMap<String, ByteArray>()
        TarArchiveInputStream(loaded.toByteArray().inputStream()).use { t ->
            while (true) { val e = t.nextEntry ?: break; entries[e.name] = t.readBytes() }
        }
        val diffHex = sha(layerTar).removePrefix("sha256:")
        assertTrue(entries.keys.toString(), entries["$diffHex/layer.tar"]!!.contentEquals(layerTar))
        assertTrue(entries["${sha(config).removePrefix("sha256:")}.json"]!!.contentEquals(config))
        val m = String(entries["manifest.json"]!!)
        assertTrue(m, m.contains("\"RepoTags\":[\"${registry.hostName}:${registry.port}/team/demo:1.0\"]"))
        assertTrue(m.contains("\"$diffHex/layer.tar\""))
    }

    @Test fun existingImageIsOnlyTagged() = runBlocking {
        imagePresent = true
        val r = puller().pull(ref())
        assertTrue(r.alreadyPresent)
        assertEquals(0, loaded.size())
    }

    @Test fun corruptLayerIsRejected() = runBlocking {
        corruptLayer = true
        try { puller().pull(ref()); fail() } catch (e: RegistryException) { assertTrue(e.message!!.contains("corrompue")) }
        assertEquals(0, loaded.size())
    }

    @Test fun platformSelection() {
        val idx = kotlinx.serialization.json.Json.parseToJsonElement(index) as kotlinx.serialization.json.JsonObject
        assertEquals(sha(manifest), RegistryClient.pickPlatform(idx, "arm64"))
        assertEquals("sha256:amd", RegistryClient.pickPlatform(idx, "amd64"))
        assertEquals(null, RegistryClient.pickPlatform(idx, "riscv64"))
    }
}
