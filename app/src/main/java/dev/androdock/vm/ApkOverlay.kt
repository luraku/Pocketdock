package dev.androdock.vm

import dev.androdock.runtime.Archives
import java.io.File

/**
 * Génère l'overlay Alpine ("apkovl") appliqué au démarrage de la VM.
 *
 * Alpine démarre en mode "diskless" depuis le noyau et l'initramfs ; l'init
 * télécharge l'overlay, installe les paquets listés dans /etc/apk/world puis
 * lance les services. Résultat : une VM minimale avec dockerd, dont les
 * données (/var/lib/docker) vivent sur le disque virtuel /dev/vda.
 */
object ApkOverlay {
    /** Réseau slirp de QEMU : invité 10.0.2.15, hôte Android joignable en 10.0.2.2. */
    const val GUEST_IP = "10.0.2.15"
    const val GATEWAY = "10.0.2.2"
    val DNS = listOf("1.1.1.1", "8.8.8.8")

    fun write(dest: File, alpineBranch: String, dockerPort: Int) {
        dest.parentFile?.mkdirs()
        Archives.TarGzWriter(dest.outputStream()).use { t ->
            for (d in listOf(
                "etc", "etc/apk", "etc/apk/protected_paths.d", "etc/conf.d", "etc/init.d", "etc/network",
                "etc/runlevels", "etc/runlevels/sysinit", "etc/runlevels/boot", "etc/runlevels/default",
                "etc/runlevels/shutdown", "etc/acpi", "etc/acpi/PWRF", "etc/docker", "etc/sysctl.d",
            )) t.dir(d)

            t.file("etc/hostname", "androdock\n")
            t.file("etc/hosts", "127.0.0.1\tlocalhost androdock\n::1\tlocalhost androdock\n")
            t.file(
                "etc/network/interfaces",
                "auto lo\niface lo inet loopback\n\nauto eth0\niface eth0 inet static\n" +
                    "\taddress $GUEST_IP\n\tnetmask 255.255.255.0\n\tgateway $GATEWAY\n",
            )
            t.file("etc/resolv.conf", DNS.joinToString("") { "nameserver $it\n" })
            t.file(
                "etc/apk/repositories",
                "http://dl-cdn.alpinelinux.org/alpine/$alpineBranch/main\n" +
                    "http://dl-cdn.alpinelinux.org/alpine/$alpineBranch/community\n",
            )
            t.file(
                "etc/apk/world",
                listOf("alpine-base", "docker", "docker-cli-compose", "docker-cli-buildx", "e2fsprogs", "iptables", "ca-certificates")
                    .joinToString("\n", postfix = "\n"),
            )

            // dockerd écoute sur le socket local et sur TCP (redirigé vers 127.0.0.1 côté Android uniquement)
            t.file(
                "etc/conf.d/docker",
                "DOCKER_OPTS=\"-H unix:///var/run/docker.sock -H tcp://0.0.0.0:$dockerPort\"\n" +
                    "DOCKER_LOGFILE=\"/var/log/docker.log\"\n",
            )
            t.file(
                "etc/docker/daemon.json",
                """{ "storage-driver": "overlay2", "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "2" } }""" + "\n",
            )
            t.file(
                "etc/sysctl.d/androdock.conf",
                "net.ipv4.ip_forward = 1\nnet.ipv4.conf.all.route_localnet = 1\nnet.ipv4.conf.eth0.route_localnet = 1\n",
            )
            // Ports publiés sur 127.0.0.1 (ex. « 127.0.0.1:8088:8000 ») : joignables depuis Android.
            t.file("etc/init.d/androdock-net", NET_SERVICE.replace("@DOCKER_PORT@", dockerPort.toString()), "755".toInt(8))

            // Prépare et monte le disque de données avant dockerd
            t.file("etc/init.d/androdock-data", DATA_SERVICE, "755".toInt(8))

            // Extinction propre quand Android demande l'arrêt (bouton power virtuel)
            t.file("etc/acpi/PWRF/00000080", "#!/bin/sh\n/sbin/poweroff\n", "755".toInt(8))

            val runlevels = mapOf(
                "sysinit" to listOf("devfs", "dmesg", "mdev", "hwdrivers", "modloop"),
                "boot" to listOf("hostname", "bootmisc", "syslog", "modules", "sysctl", "hwclock", "cgroups"),
                "default" to listOf("networking", "acpid", "androdock-net", "androdock-data", "docker", "local"),
                "shutdown" to listOf("killprocs", "mount-ro", "savecache"),
            )
            for ((level, services) in runlevels) {
                for (s in services) t.symlink("etc/runlevels/$level/$s", "/etc/init.d/$s")
            }
        }
    }

    /**
     * QEMU redirige les ports d'Android vers l'interface eth0 de la VM. Un port publié par
     * Docker sur 127.0.0.1 n'y est pas joignable : on renvoie donc tout le trafic TCP entrant
     * par eth0 vers 127.0.0.1 (route_localnet). Les ports publiés sur 0.0.0.0 restent servis :
     * docker-proxy écoute aussi sur 127.0.0.1. Règle insérée en tête, avant celles de Docker.
     */
    private val NET_SERVICE = """
        #!/sbin/openrc-run
        description="Redirection des ports locaux de la VM vers Android"

        depend() {
            need net
            before docker
        }

        start() {
            ebegin "Redirection eth0 -> 127.0.0.1"
            sysctl -q -w net.ipv4.conf.all.route_localnet=1 net.ipv4.conf.eth0.route_localnet=1
            iptables -t nat -C PREROUTING -i eth0 -p tcp ! --dport @DOCKER_PORT@ -j DNAT --to-destination 127.0.0.1 2>/dev/null ||
                iptables -t nat -I PREROUTING 1 -i eth0 -p tcp ! --dport @DOCKER_PORT@ -j DNAT --to-destination 127.0.0.1
            eend ${'$'}?
        }
    """.trimIndent() + "\n"

    private val DATA_SERVICE = """
        #!/sbin/openrc-run
        description="Disque de données Pocketdock (/var/lib/docker)"

        depend() {
            need localmount
            before docker
        }

        start() {
            ebegin "Montage du disque de données"
            DISK=/dev/vda
            [ -b "${'$'}DISK" ] || { eend 1 "disque absent"; return 1; }
            # blkid de busybox renvoie 0 même sans système de fichiers : on interroge e2fsprogs.
            if ! dumpe2fs -h "${'$'}DISK" >/dev/null 2>&1; then
                if blkid "${'$'}DISK" 2>/dev/null | grep -q 'TYPE='; then
                    eend 1 "${'$'}DISK contient un système de fichiers non ext : refus de formater"; return 1
                fi
                einfo "Formatage initial de ${'$'}DISK"
                mkfs.ext4 -q -F -L androdock "${'$'}DISK" || { eend 1 "mkfs.ext4 a échoué"; return 1; }
            fi
            mkdir -p /mnt/data
            mount -t ext4 -o noatime,discard "${'$'}DISK" /mnt/data || { eend 1 "montage de ${'$'}DISK impossible"; return 1; }
            mkdir -p /mnt/data/docker /var/lib/docker
            mount --bind /mnt/data/docker /var/lib/docker
            eend ${'$'}?
        }

        stop() {
            ebegin "Démontage du disque de données"
            umount /var/lib/docker 2>/dev/null
            sync
            umount /mnt/data
            eend 0
        }
    """.trimIndent() + "\n"
}
