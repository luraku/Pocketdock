package dev.androdock

import dev.androdock.control.HttpResponse
import dev.androdock.control.HttpServer
import dev.androdock.machines.DistroCatalog
import dev.androdock.runtime.MiniHttpServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HttpServersTest {
    private val http = OkHttpClient()

    @Test fun controlServerParsesRequests() {
        HttpServer(0) { req ->
            HttpResponse.json(201, """{"m":"${req.method}","p":"${req.path}","q":"${req.query["a"]}","b":"${req.bodyText}","h":"${req.header("X-Test")}"}""")
        }.use { srv ->
            val r = Request.Builder().url("http://127.0.0.1:${srv.port}/v1/machines/demo%20x/exec?a=b%20c")
                .header("X-Test", "oui")
                .post("hello".toRequestBody("text/plain".toMediaType())).build()
            http.newCall(r).execute().use { resp ->
                assertEquals(201, resp.code)
                assertEquals("""{"m":"POST","p":"/v1/machines/demo x/exec","q":"b c","b":"hello","h":"oui"}""", resp.body!!.string())
            }
        }
    }

    @Test fun controlServerReturns500OnHandlerError() {
        HttpServer(0) { error("boum") }.use { srv ->
            http.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}/x").build()).execute().use {
                assertEquals(500, it.code)
                assertTrue(it.body!!.string().contains("boum"))
            }
        }
    }

    @Test fun miniServerServesOnlyKnownFiles() {
        val f = File.createTempFile("boot", ".bin").apply { writeText("noyau"); deleteOnExit() }
        MiniHttpServer(mapOf("vmlinuz" to f)).use { srv ->
            http.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}/vmlinuz").build()).execute().use {
                assertEquals(200, it.code); assertEquals("noyau", it.body!!.string())
            }
            http.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}/../etc/passwd").build()).execute().use {
                assertEquals(404, it.code)
            }
        }
    }

    @Test fun distroUrlsFollowArchitecture() {
        val alpine = DistroCatalog.byId("alpine")!!
        assertTrue(alpine.urlFor("x86_64").endsWith("/x86_64/alpine-minirootfs-3.22.0-x86_64.tar.gz"))
        assertTrue(alpine.urlFor("aarch64").contains("-aarch64.tar.gz"))
        assertTrue(DistroCatalog.byId("ubuntu")!!.urlFor("x86_64").endsWith("-base-amd64.tar.gz"))
        assertTrue(DistroCatalog.byId("ubuntu")!!.urlFor("aarch64").endsWith("-base-arm64.tar.gz"))
    }
}
