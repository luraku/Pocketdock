package dev.androdock

import dev.androdock.docker.DockerClient
import dev.androdock.docker.DockerException
import dev.androdock.docker.RunSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class DockerClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DockerClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        client = DockerClient(server.port)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun pingOk() = runTest {
        server.enqueue(MockResponse().setBody("OK"))
        assertTrue(client.ping())
        assertEquals("/_ping", server.takeRequest().path)
    }

    @Test fun pingFailsWhenDown() = runTest {
        server.shutdown()
        assertFalse(client.ping())
    }

    @Test fun parsesContainers() = runTest {
        server.enqueue(MockResponse().setBody(
            """[{"Id":"abcdef1234567890","Names":["/web"],"Image":"nginx","State":"running","Status":"Up 2s",
               "Ports":[{"IP":"0.0.0.0","PrivatePort":80,"PublicPort":8080,"Type":"tcp"}],
               "Labels":{"com.docker.compose.project":"demo"},"Unknown":true}]"""
        ))
        val list = client.containers()
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("web", c.name)
        assertEquals("abcdef123456", c.shortId)
        assertTrue(c.running)
        assertEquals(8080, c.ports[0].publicPort)
        assertEquals("demo", c.composeProject)
        assertEquals("/containers/json?all=1", server.takeRequest().path)
    }

    @Test fun errorMessageIsExtracted() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"conflit de nom"}"""))
        try {
            client.start("x"); fail("exception attendue")
        } catch (e: DockerException) {
            assertEquals(409, e.code)
            assertEquals("conflit de nom", e.message)
        }
    }

    @Test fun runPullsMissingImageThenStarts() = runTest {
        val paths = mutableListOf<String>()
        var created = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                paths += "${request.method} $p"
                return when {
                    p.startsWith("/containers/create") -> if (created++ == 0)
                        MockResponse().setResponseCode(404).setBody("""{"message":"No such image"}""")
                    else MockResponse().setResponseCode(201).setBody("""{"Id":"cid123"}""")
                    p.startsWith("/images/create") -> MockResponse().setBody(
                        "{\"status\":\"Pulling\"}\n{\"status\":\"Download complete\"}\n")
                    p == "/containers/cid123/start" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        val statuses = mutableListOf<String>()
        val id = client.run(RunSpec(image = "nginx:alpine", name = "web", ports = listOf("8080:80"))) {
            statuses += it.status.orEmpty()
        }
        assertEquals("cid123", id)
        assertEquals(listOf("Pulling", "Download complete"), statuses)
        assertTrue(paths.any { it.startsWith("POST /images/create?fromImage=nginx&tag=alpine") })
        assertEquals("POST /containers/cid123/start", paths.last())
    }

    @Test fun createBodyHasPortBindings() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"Id":"c1"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        client.run(RunSpec(image = "redis", ports = listOf("6379:6379", "5353:53/udp"), env = listOf("A=1"), restartAlways = true))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val host = body["HostConfig"]!!.jsonObject
        val bindings = host["PortBindings"]!!.jsonObject
        assertEquals("6379", bindings["6379/tcp"]!!.jsonArray[0].jsonObject["HostPort"]!!.jsonPrimitive.content)
        assertEquals("5353", bindings["53/udp"]!!.jsonArray[0].jsonObject["HostPort"]!!.jsonPrimitive.content)
        assertEquals("unless-stopped", host["RestartPolicy"]!!.jsonObject["Name"]!!.jsonPrimitive.content)
        assertEquals("A=1", body["Env"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun parsePortVariants() {
        assertEquals("8080" to "80/tcp", client.parsePort("8080:80"))
        assertEquals("53" to "53/udp", client.parsePort("53/udp"))
        assertEquals(null, client.parsePort("  "))
    }

    @Test fun splitReferenceVariants() {
        assertEquals("nginx" to "latest", client.splitReference("nginx"))
        assertEquals("postgres" to "16-alpine", client.splitReference("postgres:16-alpine"))
        assertEquals("localhost:5000/app" to "latest", client.splitReference("localhost:5000/app"))
        assertEquals("ghcr.io/o/app" to "v1", client.splitReference("ghcr.io/o/app:v1"))
    }

    @Test fun demuxMultiplexedAndRaw() {
        fun frame(stream: Int, text: String): ByteArray {
            val b = text.toByteArray()
            return byteArrayOf(stream.toByte(), 0, 0, 0, 0, 0, 0, b.size.toByte()) + b
        }
        assertEquals("out\nerr\n", DockerClient.demux(frame(1, "out\n") + frame(2, "err\n")))
        assertEquals("brut tty", DockerClient.demux("brut tty".toByteArray()))
        assertEquals("", DockerClient.demux(ByteArray(0)))
    }
}
