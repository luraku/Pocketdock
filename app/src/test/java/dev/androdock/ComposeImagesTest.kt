package dev.androdock

import dev.androdock.compose.ComposeImages
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeImagesTest {
    private val config = """
      {"name":"demo","services":{
        "web":{"image":"nginx:alpine"},
        "cache":{"image":"redis:7-alpine"},
        "app":{"build":{"context":"."},"image":"demo-app"},
        "local":{"image":"x:1","pull_policy":"never"},
        "web2":{"image":"nginx:alpine"}}}
    """.trimIndent()

    @Test fun imagesToPull() {
        assertEquals(listOf("nginx:alpine", "redis:7-alpine"), ComposeImages.toPull(config))
        assertEquals(listOf("redis:7-alpine"), ComposeImages.toPull(config, listOf("cache", "app")))
    }
}
