package dev.androdock.vm

import java.io.File
import java.io.RandomAccessFile

/** Mode d'exécution de la VM. */
enum class Accel(val label: String) {
    KVM("matérielle (KVM)"),
    TCG("logicielle (émulation TCG)"),
}

/** Résultat de la détection de KVM, avec une explication lisible. */
data class AccelProbe(val kvmUsable: Boolean, val reason: String)

/**
 * Accélération matérielle de la VM.
 *
 * Sur un Android non rooté, /dev/kvm est absent ou refusé aux applications (SELinux) : la VM tourne
 * alors en émulation (TCG), 5 à 20 fois plus lente. Sur un appareil qui l'expose (root, ROM
 * particulière), QEMU utilise KVM et exécute la VM directement sur le processeur.
 */
object Acceleration {
    fun probe(dev: File = File("/dev/kvm")): AccelProbe {
        if (!dev.exists()) return AccelProbe(false, "/dev/kvm absent : noyau sans KVM, ou caché aux applications par Android")
        return try {
            RandomAccessFile(dev, "rw").use { }
            AccelProbe(true, "/dev/kvm accessible")
        } catch (e: Exception) {
            AccelProbe(false, "/dev/kvm présent mais refusé à l'application (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /** Machine, processeur et accélérateur QEMU pour ce mode. */
    fun qemuArgs(accel: Accel): List<String> = when (accel) {
        // gic-version=host : le contrôleur d'interruptions de la VM suit celui du processeur réel.
        Accel.KVM -> listOf("-M", "virt,gic-version=host", "-cpu", "host", "-accel", "kvm")
        Accel.TCG -> listOf("-M", "virt", "-cpu", "max,pauth-impdef=on", "-accel", "tcg,thread=multi,tb-size=256")
    }

    /** Modes à essayer dans l'ordre : KVM puis repli logiciel si KVM est utilisable et autorisé. */
    fun candidates(enabled: Boolean, probe: AccelProbe): List<Accel> =
        if (enabled && probe.kvmUsable) listOf(Accel.KVM, Accel.TCG) else listOf(Accel.TCG)
}
