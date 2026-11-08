package dev.androdock.registry

/**
 * Référence d'image Docker décomposée, avec les mêmes règles que le client docker :
 * `nginx` → registry-1.docker.io / library/nginx:latest ; `ghcr.io/a/b:1` → ghcr.io / a/b:1.
 */
data class ImageRef(
    /** Nom du registre tel qu'écrit (docker.io pour Docker Hub). */
    val registry: String,
    val repository: String,
    val tag: String?,
    val digest: String?,
) {
    /** Hôte de l'API du registre. */
    val apiHost: String get() = if (registry == DOCKER_HUB) "registry-1.docker.io" else registry

    /** Nom attribué à l'image chargée, tel que l'afficherait `docker images` (ex. mysql:8.0). */
    val repoTag: String?
        get() = tag?.let {
            val repo = if (registry == DOCKER_HUB) repository.removePrefix("library/") else "$registry/$repository"
            "$repo:$it"
        }

    /** Référence à demander au registre (le condensé l'emporte sur l'étiquette). */
    val reference: String get() = digest ?: tag ?: "latest"

    override fun toString() = (if (registry == DOCKER_HUB) repository else "$registry/$repository") +
        (tag?.let { ":$it" } ?: "") + (digest?.let { "@$it" } ?: "")

    companion object {
        const val DOCKER_HUB = "docker.io"

        fun parse(input: String): ImageRef {
            var rest = input.trim()
            require(rest.isNotEmpty() && rest.none { it.isWhitespace() }) { "référence d'image invalide : « $input »" }
            var digest: String? = null
            rest.indexOf('@').takeIf { it >= 0 }?.let { digest = rest.substring(it + 1); rest = rest.substring(0, it) }
            var registry = DOCKER_HUB
            val slash = rest.indexOf('/')
            if (slash > 0) {
                val first = rest.substring(0, slash)
                if ('.' in first || ':' in first || first == "localhost") { registry = first; rest = rest.substring(slash + 1) }
            }
            if (registry == "index.docker.io") registry = DOCKER_HUB
            var tag: String? = null
            val colon = rest.lastIndexOf(':')
            if (colon > rest.lastIndexOf('/')) { tag = rest.substring(colon + 1); rest = rest.substring(0, colon) }
            if (tag == null && digest == null) tag = "latest"
            if (registry == DOCKER_HUB && '/' !in rest) rest = "library/$rest"
            require(rest.isNotEmpty() && rest == rest.lowercase()) { "référence d'image invalide : « $input »" }
            return ImageRef(registry, rest, tag, digest)
        }
    }
}
