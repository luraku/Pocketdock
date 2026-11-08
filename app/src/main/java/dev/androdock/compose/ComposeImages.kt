package dev.androdock.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Images qu'un `docker compose up` irait télécharger, d'après la configuration résolue (JSON). */
object ComposeImages {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Images des services [services] (tous si vide), hors services construits localement (`build:`)
     * ou à ne pas télécharger (`pull_policy: build | never`), sans doublons.
     */
    fun toPull(config: String, services: List<String> = emptyList()): List<String> {
        val all = (json.parseToJsonElement(config) as? JsonObject)?.get("services") as? JsonObject ?: return emptyList()
        return all.filterKeys { services.isEmpty() || it in services }.values.mapNotNull { svc ->
            val s = svc as? JsonObject ?: return@mapNotNull null
            if (s["build"] != null) return@mapNotNull null
            val policy = (s["pull_policy"] as? JsonPrimitive)?.contentOrNull
            if (policy == "build" || policy == "never") return@mapNotNull null
            (s["image"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.distinct()
    }
}
