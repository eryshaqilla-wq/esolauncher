package dev.omalauncher.linux.eso

import java.io.File

/**
 * Supported execution runners for Esolauncher.
 */
enum class EsoRunnerType {
    /** Non-root ptrace emulation via libproot.so (Arch Linux) */
    PROOT,
    /** Root Kali NetHunter chroot via su -mm */
    NETHUNTER,
    /** Root Droidspaces Ubuntu container via su -c */
    DROIDSPACES,
    /** Root custom Linux chroot via su -mm */
    CUSTOM,
}

/**
 * An available or configured Linux target environment.
 */
data class EsoEnvironment(
    val id: String,
    val displayName: String,
    val description: String,
    val rootfsPath: String,
    val isRoot: Boolean,
    val type: EsoRunnerType,
    val containerName: String = "",
) {
    val isLocalDirectory: Boolean get() = File(rootfsPath).isDirectory
}
