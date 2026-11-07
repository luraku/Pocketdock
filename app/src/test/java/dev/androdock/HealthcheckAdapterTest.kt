package dev.androdock

import dev.androdock.compose.HealthcheckAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthcheckAdapterTest {
    private val config = """
        {"name":"forever","services":{
          "mysql":{"image":"mysql:8.0","healthcheck":{"test":["CMD-SHELL","mysqladmin ping"],"interval":"10s","timeout":"5s","retries":30,"start_period":"30s"}},
          "web":{"build":{"context":"/mnt/data/projects/forever/web"}},
          "slow":{"image":"x","healthcheck":{"test":["CMD","true"],"start_period":"1h"}},
          "off":{"image":"y","healthcheck":{"disable":true}}
        },"volumes":{"mysql-data":{}}}
    """.trimIndent()

    @Test fun extendsOnlyShortStartPeriods() {
        val (out, changed) = HealthcheckAdapter.adapt(config)
        assertEquals(listOf("mysql"), changed)
        val svc = Json.parseToJsonElement(out).jsonObject["services"]!!.jsonObject
        val hc = svc["mysql"]!!.jsonObject["healthcheck"]!!.jsonObject
        assertEquals("30m", hc["start_period"]!!.jsonPrimitive.content)
        assertEquals("10s", hc["interval"]!!.jsonPrimitive.content)       // le reste est conservé
        assertEquals(30, hc["retries"]!!.jsonPrimitive.content.toInt())
        assertEquals("1h", svc["slow"]!!.jsonObject["healthcheck"]!!.jsonObject["start_period"]!!.jsonPrimitive.content)
        assertEquals("forever", Json.parseToJsonElement(out).jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun noHealthcheckMeansUnchanged() {
        val c = """{"services":{"a":{"image":"nginx"}}}"""
        assertEquals(c to emptyList<String>(), HealthcheckAdapter.adapt(c))
    }

    @Test fun goDurations() {
        assertEquals(30L, HealthcheckAdapter.parseDuration("30s"))
        assertEquals(90L, HealthcheckAdapter.parseDuration("1m30s"))
        assertEquals(7200L, HealthcheckAdapter.parseDuration("2h"))
        assertEquals(0L, HealthcheckAdapter.parseDuration("500ms"))
        assertNull(HealthcheckAdapter.parseDuration("bientôt"))
        assertEquals("30m", HealthcheckAdapter.formatDuration(1800))
        assertEquals("45s", HealthcheckAdapter.formatDuration(45))
    }
}
