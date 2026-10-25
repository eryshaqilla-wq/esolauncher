package dev.omalauncher.linux.eso

import dev.omalauncher.linux.shellQuote
import java.io.File
import java.util.TimeZone

/**
 * Command builder and bridge dispatcher for root chroot/containers.
 * Integrates precision MagiskPolicy for targeted SELinux permissions
 * without touching global setenforce (keeping banking apps and system safe).
 */
object EsoBridge {
    /**
     * Precision MagiskPolicy rules matching /data/adb/service.d/termux_chroot_selinux.sh
     */
    private const val MAGISK_POLICY_INIT =
        "if command -v magiskpolicy >/dev/null 2>&1; then " +
            "magiskpolicy --live 'permissive untrusted_app' 'permissive untrusted_app_27' 'permissive untrusted_app_29' 'permissive untrusted_app_30' 'permissive untrusted_app_32' 'permissive untrusted_app_all' 'permissive runas_app' 2>/dev/null || true; " +
            "magiskpolicy --live 'allow { untrusted_app untrusted_app_27 untrusted_app_29 untrusted_app_30 untrusted_app_32 untrusted_app_all runas_app } system_data_file dir { read open search getattr }' 'allow { untrusted_app untrusted_app_27 untrusted_app_29 untrusted_app_30 untrusted_app_32 untrusted_app_all runas_app } system_data_file file { read open getattr }' 2>/dev/null || true; " +
            "magiskpolicy --live 'allow { untrusted_app untrusted_app_27 untrusted_app_all } self capability sys_nice' 2>/dev/null || true; " +
            "magiskpolicy --live 'allow { untrusted_app untrusted_app_27 untrusted_app_all } { gpu_device ion_device } chr_file { open read write ioctl }' 2>/dev/null || true; " +
            "magiskpolicy --live 'allow { untrusted_app untrusted_app_27 untrusted_app_all } { system_data_file app_data_file } sock_file { create write unlink getattr setattr }' 2>/dev/null || true; " +
        "fi"

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

        val runnerScript = when (env.type) {
            EsoRunnerType.NETHUNTER, EsoRunnerType.CUSTOM -> {
                """
                $MAGISK_POLICY_INIT
                chmod 777 "${runtimeDir.path}" 2>/dev/null || true
                chmod 666 "${runtimeDir.path}/$waylandSocketName" 2>/dev/null || true
                mkdir -p "$rootfs/run/user/0" 2>/dev/null || true
                mount --bind "${runtimeDir.path}" "$rootfs/run/user/0" 2>/dev/null || true
                chroot "$rootfs" /usr/bin/env -i \
                    HOME=/root \
                    USER=root \
                    LANG=C.UTF-8 \
                    TZ="$tz" \
                    TERM=xterm-256color \
                    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin \
                    XDG_RUNTIME_DIR=/run/user/0 \
                    WAYLAND_DISPLAY=$waylandSocketName \
                    XDG_SESSION_TYPE=wayland \
                    GDK_BACKEND=wayland \
                    QT_QPA_PLATFORM=wayland \
                    MOZ_ENABLE_WAYLAND=1 \
                    SDL_VIDEODRIVER=wayland \
                    PULSE_SERVER=tcp:127.0.0.1:4713 \
                    /bin/sh -c ${shellQuote(script)}
                """.trimIndent()
            }
            EsoRunnerType.DROIDSPACES -> {
                val dsBin = "/data/local/Droidspaces/bin/droidspaces"
                """
                $MAGISK_POLICY_INIT
                chmod 777 "${runtimeDir.path}" 2>/dev/null || true
                chmod 666 "${runtimeDir.path}/$waylandSocketName" 2>/dev/null || true
                mkdir -p "$rootfs/run/user/0" 2>/dev/null || true
                mount --bind "${runtimeDir.path}" "$rootfs/run/user/0" 2>/dev/null || true
                if [ -x "$dsBin" ]; then
                    $dsBin exec --name="${env.containerName}" /usr/bin/env -i \
                        HOME=/root \
                        USER=root \
                        LANG=C.UTF-8 \
                        TZ="$tz" \
                        TERM=xterm-256color \
                        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                        XDG_RUNTIME_DIR=/run/user/0 \
                        WAYLAND_DISPLAY=$waylandSocketName \
                        XDG_SESSION_TYPE=wayland \
                        GDK_BACKEND=wayland \
                        QT_QPA_PLATFORM=wayland \
                        MOZ_ENABLE_WAYLAND=1 \
                        SDL_VIDEODRIVER=wayland \
                        PULSE_SERVER=tcp:172.28.0.1:4713 \
                        /bin/sh -c ${shellQuote(script)}
                else
                    chroot "$rootfs" /usr/bin/env -i \
                        HOME=/root \
                        USER=root \
                        LANG=C.UTF-8 \
                        TZ="$tz" \
                        TERM=xterm-256color \
                        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                        XDG_RUNTIME_DIR=/run/user/0 \
                        WAYLAND_DISPLAY=$waylandSocketName \
                        XDG_SESSION_TYPE=wayland \
                        GDK_BACKEND=wayland \
                        QT_QPA_PLATFORM=wayland \
                        MOZ_ENABLE_WAYLAND=1 \
                        SDL_VIDEODRIVER=wayland \
                        PULSE_SERVER=tcp:172.28.0.1:4713 \
                        /bin/sh -c ${shellQuote(script)}
                fi
                """.trimIndent()
            }
            EsoRunnerType.PROOT -> script
        }

        return if (env.type == EsoRunnerType.NETHUNTER) {
            listOf("su", "-mm", "-c", runnerScript)
        } else {
            listOf("su", "-c", runnerScript)
        }
    }
}
