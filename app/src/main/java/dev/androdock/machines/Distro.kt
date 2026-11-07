package dev.androdock.machines

/**
 * Distributions Linux installables comme « machines » (rootfs exécuté via proot).
 * L'architecture du rootfs doit être celle de l'appareil : [url] contient {arch}
 * (aarch64 / x86_64) et {deb} (arm64 / amd64), remplacés à l'installation.
 */
data class Distro(
    val id: String,
    val label: String,
    val url: String,
    val stripComponents: Int = 0,
) {
    fun urlFor(linuxArch: String): String = url
        .replace("{arch}", linuxArch)
        .replace("{deb}", if (linuxArch == "x86_64") "amd64" else "arm64")
}

object DistroCatalog {
    val all = listOf(
        Distro(
            id = "alpine",
            label = "Alpine Linux 3.22",
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/{arch}/alpine-minirootfs-3.22.0-{arch}.tar.gz",
        ),
        Distro(
            id = "ubuntu",
            label = "Ubuntu 24.04 LTS",
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-{deb}.tar.gz",
        ),
    )

    /** Archive personnalisée (URL .tar.gz / .tar.xz d'un rootfs arm64). */
    val custom = Distro(id = "custom", label = "Rootfs personnalisé", url = "")

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: custom.takeIf { it.id == id }
}
