package dev.omalauncher.linux.eso

import android.content.Context
import dev.omalauncher.linux.shellQuote
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Command builder and bridge dispatcher for root chroot/containers.
 * Executes root commands cleanly via su without complex escaping or mount namespace locks.
 * Includes Standalone VirGL 3D acceleration engine (zero dependency on Termux).
 */
object EsoBridge {
    /**
     * Unpacks the bundled standalone VirGL server and libraries into internal storage.
     */
    fun unpackVirglBundle(context: Context, destDir: File) {
        if (File(destDir, "virgl_test_server_android").isFile) return
        destDir.mkdirs()
        runCatching {
            context.assets.open("virgl/virgl-android.tar.gz").use { input ->
                TarArchiveInputStream(GZIPInputStream(input.buffered(1 shl 16))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.removePrefix("./").removePrefix("/")
                        val out = File(destDir, name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                        out.setExecutable(true, false)
                        out.setReadable(true, false)
                    }
                }
            }
        }
    }

    /**
     * Prepares permissions, mount points, SELinux policies, and launches Standalone VirGL server.
     */
    fun prepareEnvironment(context: Context, env: EsoEnvironment, runtimeDir: File) {
        if (!env.isRoot) return
        val rootfs = env.rootfsPath
        val waylandSocketName = "wayland-0"
        val virglDir = File(context.filesDir, "virgl")

        // Unpack standalone VirGL binaries
        unpackVirglBundle(context, virglDir)

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

            # Standalone VirGL 3D Server Superpower (100% Independent of Termux)
            chmod -R 777 "${virglDir.path}" 2>/dev/null || true
            VIRGL_BIN="${virglDir.path}/virgl_test_server_android"
            VIRGL_LIB="${virglDir.path}/lib"
            if [ ! -f "${'$'}VIRGL_BIN" ] && [ -f "/data/local/tmp/eso_bins/virgl/virgl_test_server_android" ]; then
                VIRGL_BIN="/data/local/tmp/eso_bins/virgl/virgl_test_server_android"
                VIRGL_LIB="/data/local/tmp/eso_bins/virgl/lib"
            fi
            if [ ! -f "${'$'}VIRGL_BIN" ] && [ -f "/data/data/com.termux/files/usr/bin/virgl_test_server_android" ]; then
                VIRGL_BIN="/data/data/com.termux/files/usr/bin/virgl_test_server_android"
                VIRGL_LIB="/data/data/com.termux/files/usr/opt/virglrenderer-android/lib"
            fi

            mkdir -p "$rootfs/tmp/.virgl" 2>/dev/null || true
            if ! pgrep -f virgl_test_server >/dev/null 2>&1; then
                if [ -x "${'$'}VIRGL_BIN" ]; then
                    nohup env TMPDIR="$rootfs/tmp" LD_LIBRARY_PATH="${'$'}VIRGL_LIB" "${'$'}VIRGL_BIN" --angle-gl --socket-path "$rootfs/tmp/.virgl_test" >/dev/null 2>&1 &
                fi
            fi
            sleep 0.2
            chmod 666 "$rootfs/tmp/.virgl_test" 2>/dev/null || true
            ln -sfn /tmp/.virgl_test "$rootfs/tmp/.virgl/.virgl_test" 2>/dev/null || true
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
            "GALLIUM_DRIVER=virpipe",
            "VTEST_SOCKET_NAME=/tmp/.virgl_test",
            "MESA_GL_VERSION_OVERRIDE=4.0",
            "MESA_GLES_VERSION_OVERRIDE=3.2",
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
