package dev.androdock.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Adapte une configuration Compose résolue (`docker compose config --format json`) à une VM
 * émulée : les healthchecks sont écrits pour une machine native (MySQL `start_period: 30s`),
 * alors qu'ici l'initialisation d'une base peut prendre un quart d'heure. On allonge donc le
 * délai de grâce (`start_period`) ; un service sain est toujours déclaré sain dès son premier
 * test réussi, seul le verdict « unhealthy » est retardé.
 */
object HealthcheckAdapter {
    private val json = Json { prettyPrint = false }

    /** Renvoie la configuration adaptée et la liste des services modifiés. */
    fun adapt(config: String, minStartPeriodSeconds: Long = 30 * 60): Pair<String, List<String>> {
        val root = json.parseToJsonElement(config).jsonObject
        val services = root["services"] as? JsonObject ?: return config to emptyList()
        val changed = mutableListOf<String>()
        val newServices = services.mapValues { (name, svc) ->
            val s = svc as? JsonObject ?: return@mapValues svc
            val hc = s["healthcheck"] as? JsonObject ?: return@mapValues svc
            if ((hc["disable"] as? JsonPrimitive)?.contentOrNull == "true") return@mapValues svc
            val current = (hc["start_period"] as? JsonPrimitive)?.contentOrNull?.let(::parseDuration) ?: 0
            if (current >= minStartPeriodSeconds) return@mapValues svc
            changed += name
            JsonObject(s + ("healthcheck" to JsonObject(hc + ("start_period" to JsonPrimitive(formatDuration(minStartPeriodSeconds))))))
        }
        if (changed.isEmpty()) return config to emptyList()
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("services" to JsonObject(newServices)))) to changed
    }

    /** Durée au format Go (« 1m30s », « 500ms », « 2h ») en secondes ; null si illisible. */
    fun parseDuration(s: String): Long? {
        val re = Regex("""(\d+(?:\.\d+)?)(ns|us|µs|ms|s|m|h)""")
        if (!Regex("""^((\d+(?:\.\d+)?)(ns|us|µs|ms|s|m|h))+$""").matches(s.trim())) return null
        var total = 0.0
        for (m in re.findAll(s)) {
            val v = m.groupValues[1].toDouble()
            total += when (m.groupValues[2]) {
                "h" -> v * 3600; "m" -> v * 60; "s" -> v; "ms" -> v / 1e3; "us", "µs" -> v / 1e6; else -> v / 1e9
            }
        }
        return total.toLong()
    }

    fun formatDuration(seconds: Long): String = when {
        seconds % 3600 == 0L -> "${seconds / 3600}h"
        seconds % 60 == 0L -> "${seconds / 60}m"
        else -> "${seconds}s"
    }
}
