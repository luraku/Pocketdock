package dev.androdock.docker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PortMapping(
    @SerialName("IP") val ip: String? = null,
    @SerialName("PrivatePort") val privatePort: Int = 0,
    @SerialName("PublicPort") val publicPort: Int? = null,
    @SerialName("Type") val type: String = "tcp",
)

@Serializable
data class ContainerSummary(
    @SerialName("Id") val id: String,
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("Image") val image: String = "",
    @SerialName("State") val state: String = "",
    @SerialName("Status") val status: String = "",
    @SerialName("Created") val created: Long = 0,
    @SerialName("Ports") val ports: List<PortMapping> = emptyList(),
    @SerialName("Labels") val labels: Map<String, String>? = null,
) {
    val name: String get() = names.firstOrNull()?.removePrefix("/") ?: id.take(12)
    val shortId: String get() = id.take(12)
    val running: Boolean get() = state == "running"
    val composeProject: String? get() = labels?.get("com.docker.compose.project")
}

@Serializable
data class ImageSummary(
    @SerialName("Id") val id: String,
    @SerialName("RepoTags") val repoTags: List<String>? = null,
    @SerialName("Size") val size: Long = 0,
    @SerialName("Created") val created: Long = 0,
) {
    val displayName: String
        get() = repoTags?.firstOrNull { it != "<none>:<none>" } ?: id.removePrefix("sha256:").take(12)
}

@Serializable
data class VolumeSummary(
    @SerialName("Name") val name: String,
    @SerialName("Driver") val driver: String = "",
    @SerialName("Mountpoint") val mountpoint: String = "",
)

@Serializable
data class VolumeList(@SerialName("Volumes") val volumes: List<VolumeSummary>? = null)

@Serializable
data class DockerVersion(
    @SerialName("Version") val version: String = "",
    @SerialName("ApiVersion") val apiVersion: String = "",
    @SerialName("KernelVersion") val kernelVersion: String = "",
    @SerialName("Os") val os: String = "",
    @SerialName("Arch") val arch: String = "",
)

@Serializable
data class PullProgress(
    val status: String? = null,
    val id: String? = null,
    val progress: String? = null,
    val error: String? = null,
)

/** Paramètres de création d'un conteneur depuis l'interface. */
data class RunSpec(
    val image: String,
    val name: String? = null,
    /** "8080:80" ou "8080:80/udp" */
    val ports: List<String> = emptyList(),
    /** "CLE=valeur" */
    val env: List<String> = emptyList(),
    /** "volume:/chemin" */
    val volumes: List<String> = emptyList(),
    val command: String? = null,
    val restartAlways: Boolean = false,
)
