package dev.omalauncher.linux.eso

import android.content.Context
import dev.omalauncher.linux.shellQuote
import java.io.File
import java.util.TimeZone

/**
 * Command builder and bridge dispatcher for root chroot/containers.
 * Executes root commands cleanly via su without complex escaping or mount namespace locks.
 */
object EsoBridge {
    /**
     * Prepares permissions, mount points, and SELinux policies once during initialization.
     */
    fun prepareEnvironment(context: Context, env: EsoEnvironment, runtimeDir: File) {
        if (!env.isRoot) return
        val rootfs = env.rootfsPath
        val waylandSocketName = "wayland-0"

        runCatching {
            runtimeDir.mkdirs()
            android.system.Os.chmod(runtimeDir.path, 0x1ff) // 0777
        }

        val initScript = File(context.filesDir, "eso_root_init.sh")
        val content = """
            #!/system/bin/sh
            if command -v magiskpolicy >/dev/null 2>&1; then
                magiskpolicy --live "permissive untrusted_app" "permissive untrusted_app_all" 2>/dev/null || true
                magiskpolicy --live "allow { untrusted_app untrusted_app_all } system_data_file dir { read open search getattr }" "allow { untrusted_app untrusted_app_all } system_data_file file { read open getattr }" 2>/dev/null || true
                magiskpolicy --live "allow { untrusted_app untrusted_app_all } { system_data_file app_data_file } sock_file { create write unlink getattr setattr }" 2>/dev/null || true
            fi
            chmod 777 "${runtimeDir.path}" 2>/dev/null || true
            chmod 666 "${runtimeDir.path}/$waylandSocketName" 2>/dev/null || true
            mkdir -p "$rootfs/run/user/0" 2>/dev/null || true
            chmod 777 "$rootfs/run/user/0" 2>/dev/null || true
            mkdir -p "$rootfs/root/.local/share/applications" 2>/dev/null || true
            chmod 777 "$rootfs/root/.local" "$rootfs/root/.local/share" "$rootfs/root/.local/share/applications" 2>/dev/null || true
            if ! grep -q "$rootfs/run/user/0" /proc/mounts; then
                /system/bin/toybox mount "${runtimeDir.path}" "$rootfs/run/user/0" 2>/dev/null || true
            fi
        """.trimIndent()

        runCatching {
            initScript.writeText(content)
            initScript.setExecutable(true, false)
            initScript.setReadable(true, false)
            Runtime.getRuntime().exec(arrayOf("su", "-c", "/system/bin/sh ${initScript.path}")).waitFor()
        }
    }

    /**
     * Builds execution command list for root chroot or container environments.
     */
    fun buildRootCommand(
        env: EsoEnvironment,
        runtimeDir: File,
        script: String,
        tz: String = TimeZone.getDefault().id
    ): List<String> {
        val rootfs = env.rootfsPath
        val waylandSocketName = "wayland-0"

        val envVars = listOf(
            "HOME=/root",
            "USER=root",
            "LANG=C.UTF-8",
            "TZ=$tz",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin:/root/.opencode/bin",
            "XDG_RUNTIME_DIR=/run/user/0",
            "WAYLAND_DISPLAY=$waylandSocketName",
            "XDG_SESSION_TYPE=wayland",
            "GDK_BACKEND=wayland",
            "QT_QPA_PLATFORM=wayland",
            "MOZ_ENABLE_WAYLAND=1",
            "SDL_VIDEODRIVER=wayland",
            "PULSE_SERVER=" + if (env.type == EsoRunnerType.DROIDSPACES) "tcp:172.28.0.1:4713" else "tcp:127.0.0.1:4713"
        ).joinToString(" ")

        val command = when (env.type) {
            EsoRunnerType.DROIDSPACES -> {
                val dsBin = "/data/local/Droidspaces/bin/droidspaces"
                "if [ -x $dsBin ]; then $dsBin exec --name=${env.containerName} /usr/bin/env -i $envVars /bin/sh -c ${shellQuote(script)}; else chroot $rootfs /usr/bin/env -i $envVars /bin/sh -c ${shellQuote(script)}; fi"
            }
            else -> {
                "chroot $rootfs /usr/bin/env -i $envVars /bin/sh -c ${shellQuote(script)}"
            }
        }

        return listOf("su", "-c", command)
    }
}
