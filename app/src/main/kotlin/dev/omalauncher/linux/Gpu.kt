package dev.omalauncher.linux

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileNotFoundException

/**
 * 2 GPU Modes:
 * - VirGL: Hardware 3D acceleration via ANGLE/Vulkan socket (universal, safe on MediaTek/Mali & Snapdragon)
 * - Native Vulkan (Turnip / PanVK): Direct Vulkan/Zink (ONLY allowed on Qualcomm Snapdragon chipsets)
 */
enum class EsoGpuMode(val id: String, val label: String) {
    VIRGL("virgl", "VirGL (ANGLE 3D)"),
    NATIVE_VULKAN("native", "Native Vulkan (Snapdragon Only)"),
    SOFTWARE("software", "Software Rendering")
}

val VIRGL_GRAPHICS_ENV = listOf(
    "GALLIUM_DRIVER=virpipe",
    "VTEST_SOCKET_NAME=/tmp/.virgl_test",
    "MESA_GL_VERSION_OVERRIDE=4.0",
    "MESA_GLES_VERSION_OVERRIDE=3.2",
)

/**
 * Opt-in GPU acceleration for Linux apps.
 */
class Gpu(
    private val context: Context,
    private val proot: Proot,
    private val pacman: Pacman,
) {
    private val prefs = LauncherData.prefs(context)

    /**
     * Detects whether the device SoC is Qualcomm Snapdragon (Adreno).
     * Native Vulkan/Turnip is only permitted on Snapdragon to prevent MediaTek Mali kernel panic.
     */
    val isSnapdragon: Boolean = run {
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { Build.SOC_MANUFACTURER.lowercase() }.getOrDefault("")
        } else ""
        val hasKgsl = File("/dev/kgsl-3d0").exists()
        hasKgsl || hardware.contains("qcom") || board.contains("qcom") || soc.contains("qualcomm")
    }

    /** The pinned driver for this phone, or null when we have none. */
    private val spec: GpuDriver? = gpuKind(
        File("/dev/mali0").exists(),
        File("/dev/kgsl-3d0").exists(),
        runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText() }.getOrNull(),
    )?.let(GpuDrivers::of)

    val supported: Boolean get() = true

    var mode by mutableStateOf(
        when (prefs.getString(PREF_GPU_MODE, "virgl")) {
            "native" -> if (isSnapdragon) EsoGpuMode.NATIVE_VULKAN else EsoGpuMode.VIRGL
            "software" -> EsoGpuMode.SOFTWARE
            else -> EsoGpuMode.VIRGL
        }
    )
        private set

    val driverLabel: String get() = when (mode) {
        EsoGpuMode.VIRGL -> "VirGL (Universal)"
        EsoGpuMode.NATIVE_VULKAN -> if (isSnapdragon) "Native (${spec?.label ?: "Turnip"})" else "PanVK (Blocked on MediaTek)"
        EsoGpuMode.SOFTWARE -> "Software"
    }

    var wanted by mutableStateOf(prefs.getBoolean(WANTED, true))
        private set

    var active by mutableStateOf(false)
        private set

    var device by mutableStateOf(prefs.getString(DEVICE, null))
        private set

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    private val build get() = Build.FINGERPRINT + " " + System.getProperty("os.version")

    /**
     * Activates the GPU based on selected mode.
     */
    suspend fun recheck() {
        if (!wanted || state is JobState.Working) return

        when (mode) {
            EsoGpuMode.VIRGL -> {
                proot.graphicsEnv = VIRGL_GRAPHICS_ENV
                active = true
                device = "VirGL 3D (virpipe via ANGLE)"
                state = JobState.Idle
                return
            }
            EsoGpuMode.SOFTWARE -> {
                proot.graphicsEnv = SOFTWARE_ENV
                active = false
                device = null
                state = JobState.Idle
                return
            }
            EsoGpuMode.NATIVE_VULKAN -> {
                // PanVK / Native Vulkan is ONLY allowed on Snapdragon!
                if (!isSnapdragon) {
                    Log.w(TAG, "Native Vulkan/PanVK only supported on Snapdragon; falling back to VirGL to protect MediaTek SoC")
                    mode = EsoGpuMode.VIRGL
                    prefs.edit().putString(PREF_GPU_MODE, "virgl").apply()
                    proot.graphicsEnv = VIRGL_GRAPHICS_ENV
                    active = true
                    device = "VirGL 3D (virpipe via ANGLE)"
                    state = JobState.Idle
                    return
                }

                val driver = spec ?: return turnOff()
                if (!proot.hostFile(driver.driverPath).isFile) return turnOff()
                val mesaCurrent = runCatching { proot.hostFile(GpuDrivers.MESA_ZINK_STAMP).readText().trim() }
                    .getOrNull() == GpuDrivers.MESA_ZINK_TAR_SHA256
                if (mesaCurrent && prefs.getString(CHECKED_BUILD, null) == build) {
                    if (!proot.hostFile(driver.icdPath).isFile) {
                        retireLegacyManifest(driver)
                        if (!run("installing", GpuDrivers.manifestScript(driver))) {
                            proot.graphicsEnv = SOFTWARE_ENV
                            conceal(driver)
                            return
                        }
                    }
                    return activate(driver)
                }
                val ok = if (mesaCurrent) {
                    Log.i(TAG, "system changed since the last GPU check, checking again")
                    run("checking", GpuDrivers.probeScript(driver))
                } else {
                    Log.i(TAG, "the patched Mesa changed, installing it and checking again")
                    stageMesaBundle()
                    run("installing", GpuDrivers.mesaZinkScript() + " && " + GpuDrivers.probeScript(driver))
                }
                if (ok) {
                    activate(driver)
                } else {
                    proot.graphicsEnv = SOFTWARE_ENV
                    conceal(driver)
                }
            }
        }
    }

    suspend fun turnOn() {
        if (state is JobState.Working) return
        wanted = true
        prefs.edit().putBoolean(WANTED, true).apply()
        recheck()
    }

    fun turnOff() {
        wanted = false
        active = false
        mode = EsoGpuMode.SOFTWARE
        state = JobState.Idle
        prefs.edit().putBoolean(WANTED, false).putString(PREF_GPU_MODE, "software").apply()
        proot.graphicsEnv = SOFTWARE_ENV
        spec?.let(::conceal)
    }

    suspend fun toggleMode() {
        if (isSnapdragon) {
            val next = if (mode == EsoGpuMode.VIRGL) EsoGpuMode.NATIVE_VULKAN else EsoGpuMode.VIRGL
            mode = next
            prefs.edit().putString(PREF_GPU_MODE, next.id).putBoolean(WANTED, true).apply()
            wanted = true
            recheck()
        } else {
            val next = if (mode == EsoGpuMode.VIRGL) EsoGpuMode.SOFTWARE else EsoGpuMode.VIRGL
            mode = next
            prefs.edit().putString(PREF_GPU_MODE, next.id).putBoolean(WANTED, next == EsoGpuMode.VIRGL).apply()
            wanted = (next == EsoGpuMode.VIRGL)
            recheck()
        }
    }

    private fun activate(driver: GpuDriver) {
        active = true
        proot.graphicsEnv = driver.env
    }

    private fun conceal(driver: GpuDriver) {
        proot.hostFile(driver.icdPath).delete()
        retireLegacyManifest(driver)
    }

    private fun retireLegacyManifest(driver: GpuDriver) = GpuDrivers.retireLegacyManifest(driver, proot.hostFile(GpuDrivers.LEGACY_MALI_ICD))

    private fun stageMesaBundle() {
        val dest = proot.hostFile(GpuDrivers.MESA_ZINK_BUNDLE)
        val input = try {
            context.assets.open("mesa-zink/mesa-zink.tar.xz")
        } catch (_: FileNotFoundException) {
            return
        }
        dest.parentFile?.mkdirs()
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
    }

    private suspend fun run(
        action: String,
        script: String,
        lock: Mutex? = null,
    ): Boolean {
        val (ok, tail) = proot.trackedJob(action, script, lock) { state = it }
        val name = tail.toList().firstNotNullOfOrNull { DEVICE_NAME.find(it)?.groupValues?.get(1) }
        Log.i(TAG, "$action: ${if (ok) "ok, $name" else "failed"}")
        if (ok) {
            device = name
            prefs.edit().putString(CHECKED_BUILD, build).putString(DEVICE, name).apply()
        }
        return ok
    }

    companion object {
        private const val TAG = "gpu"
        private const val WANTED = "gpu_wanted"
        private const val PREF_GPU_MODE = "eso_gpu_mode"
        private const val CHECKED_BUILD = "gpu_checked_build"
        private const val DEVICE = "gpu_device"

        private val DEVICE_NAME = Regex("""deviceName\s*=\s*(.+)""")
    }
}
