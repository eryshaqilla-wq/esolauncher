package dev.omalauncher.linux.eso

import android.content.Context
import android.util.Log
import dev.omalauncher.LauncherData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Auto-detects root privileges and Linux containers/chroots on the device.
 */
object EsoDetector {
    private const val TAG = "eso.detector"
    private const val PREF_KEY_RUNNER = "eso.runner.type"
    private const val PREF_KEY_ROOTFS = "eso.runner.rootfs"
    private const val PREF_KEY_CONTAINER = "eso.runner.container"
    private const val PREF_KEY_DISPLAY = "eso.runner.display"

    /**
     * Checks whether root access (su) is available and functional.
     */
    fun checkSu(): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        if (suPaths.any { File(it).canExecute() }) return true
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Scans for all available environments: NetHunter, Droidspaces, custom chroots, and PRoot.
     */
    suspend fun scanEnvironments(context: Context): List<EsoEnvironment> = withContext(Dispatchers.IO) {
        val list = mutableListOf<EsoEnvironment>()
        val hasSu = checkSu()

        if (hasSu) {
            // 1. Kali NetHunter candidates
            val nhCandidates = listOf("/data/local/nhsystem/kali-arm64", "/data/local/nhsystem/kalifs")
            for (path in nhCandidates) {
                if (testDirectoryExistsRoot(path)) {
                    list.add(
                        EsoEnvironment(
                            id = "nh_${File(path).name}",
                            displayName = "Kali NetHunter (Root Chroot)",
                            description = "Terdeteksi di $path · 0 MB download · 100% native kernel speed",
                            rootfsPath = path,
                            isRoot = true,
                            type = EsoRunnerType.NETHUNTER
                        )
                    )
                }
            }

            // 2. Droidspaces candidates
            val dsRoots = listOf("/data/local/Droidspaces/Containers", "/data/local/Droidspaces/spaces")
            for (dsRoot in dsRoots) {
                if (testDirectoryExistsRoot(dsRoot)) {
                    val containers = listDirectoriesRoot(dsRoot)
                    for (container in containers) {
                        val subRootfs = "$dsRoot/$container/rootfs"
                        val finalPath = if (testDirectoryExistsRoot(subRootfs)) subRootfs else "$dsRoot/$container"
                        list.add(
                            EsoEnvironment(
                                id = "ds_$container",
                                displayName = "Droidspaces: $container (Root)",
                                description = "Terdeteksi di $finalPath · PulseAudio bridge aktif",
                                rootfsPath = finalPath,
                                isRoot = true,
                                type = EsoRunnerType.DROIDSPACES,
                                containerName = container
                            )
                        )
                    }
                }
            }

            // 3. Custom Linux chroots in /data/local
            val customCandidates = listOf("/data/local/ubuntu", "/data/local/debian", "/data/local/kali", "/data/local/chroot")
            for (path in customCandidates) {
                if (testDirectoryExistsRoot(path)) {
                    list.add(
                        EsoEnvironment(
                            id = "custom_${File(path).name}",
                            displayName = "Custom Chroot: ${File(path).name} (Root)",
                            description = "Terdeteksi di $path · Native execution",
                            rootfsPath = path,
                            isRoot = true,
                            type = EsoRunnerType.CUSTOM
                        )
                    )
                }
            }
        }

        // 4. Fallback Arch Linux (PRoot / Non-Root)
        val prootRootfs = File(context.filesDir, "arch").path
        list.add(
            EsoEnvironment(
                id = "proot_arch",
                displayName = "Arch Linux (PRoot / Non-Root)",
                description = "Emulasi user-space tanpa root · ~1 GB unduhan",
                rootfsPath = prootRootfs,
                isRoot = false,
                type = EsoRunnerType.PROOT
            )
        )

        list
    }

    private fun testDirectoryExistsRoot(path: String): Boolean {
        if (File(path).isDirectory) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "[ -d '$path' ]"))
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun listDirectoriesRoot(parentPath: String): List<String> {
        val f = File(parentPath)
        val localList = f.listFiles()?.filter { it.isDirectory }?.map { it.name }
        if (!localList.isNullOrEmpty()) return localList
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -1 '$parentPath'"))
            val output = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            output.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Retrieves the currently active environment from preferences.
     */
    fun getActiveEnvironment(context: Context): EsoEnvironment {
        val prefs = LauncherData.prefs(context)
        val typeStr = prefs.getString(PREF_KEY_RUNNER, null)
        val rootfs = prefs.getString(PREF_KEY_ROOTFS, null)
        val container = prefs.getString(PREF_KEY_CONTAINER, "") ?: ""
        val display = prefs.getString(PREF_KEY_DISPLAY, "") ?: ""

        if (typeStr != null && rootfs != null) {
            val type = runCatching { EsoRunnerType.valueOf(typeStr) }.getOrDefault(EsoRunnerType.PROOT)
            return EsoEnvironment(
                id = "${typeStr.lowercase()}_active",
                displayName = display.ifEmpty { type.name },
                description = rootfs,
                rootfsPath = rootfs,
                isRoot = type != EsoRunnerType.PROOT,
                type = type,
                containerName = container
            )
        }

        // Default: PRoot Arch
        val prootRootfs = File(context.filesDir, "arch").path
        return EsoEnvironment(
            id = "proot_arch",
            displayName = "Arch Linux (PRoot / Non-Root)",
            description = "Emulasi user-space tanpa root",
            rootfsPath = prootRootfs,
            isRoot = false,
            type = EsoRunnerType.PROOT
        )
    }

    /**
     * Saves the chosen active environment to preferences.
     */
    fun setActiveEnvironment(context: Context, env: EsoEnvironment) {
        val prefs = LauncherData.prefs(context)
        prefs.edit()
            .putString(PREF_KEY_RUNNER, env.type.name)
            .putString(PREF_KEY_ROOTFS, env.rootfsPath)
            .putString(PREF_KEY_CONTAINER, env.containerName)
            .putString(PREF_KEY_DISPLAY, env.displayName)
            .apply()
        Log.i(TAG, "Active environment switched to: ${env.displayName} (${env.type}, path=${env.rootfsPath})")
    }
}
