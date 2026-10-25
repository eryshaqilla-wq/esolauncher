package dev.omalauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.LinuxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Everything about the optional Linux system in one place: what it adds and
 * an install button, the install's progress, or, once installed, its size
 * and a way to remove it. Every feature that needs Linux opens this.
 */
@Composable
fun LinuxScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val state by runtime.bootstrap.state.collectAsState()
    OverlayPanel("linux", onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            when (val s = state) {
                LinuxState.Checking -> {}
                LinuxState.NotInstalled -> InstallOffer(runtime)
                is LinuxState.Installing, is LinuxState.Failed -> LinuxProgress(s, runtime.bootstrap.chosenAgents, runtime::retryLinux, runtime::cancelLinux.takeIf { runtime.bootstrap.cancellable })
                LinuxState.Installed -> Installed(runtime)
                LinuxState.Removing -> BasicText("removing linux…", style = Mono.copy(color = Palette.accent))
            }
        }
    }
}

/**
 * Starts the Linux install and asks (Android 13+) to post notifications: the foreground
 * service's notification shows the install's progress and that Linux is running.
 * The install doesn't wait for the answer, which may never come back: on the welcome
 * screen, becoming the home app moves home to a new task, away from the dialog.
 */
@Composable
fun rememberLinuxInstall(runtime: Runtime): (List<String>) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return { bins ->
        runtime.installLinux(AGENTS.filter { it.bin in bins })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun InstallOffer(runtime: Runtime) {
    var detectedEnvs by remember { mutableStateOf<List<dev.omalauncher.linux.eso.EsoEnvironment>>(emptyList()) }
    var selectedEnv by remember { mutableStateOf<dev.omalauncher.linux.eso.EsoEnvironment?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf("agy", "claude")) }
    val install = rememberLinuxInstall(runtime)

    LaunchedEffect(Unit) {
        val envs = dev.omalauncher.linux.eso.EsoDetector.scanEnvironments(runtime.context)
        detectedEnvs = envs
        val rootEnv = envs.firstOrNull { it.isRoot }
        if (rootEnv != null) {
            selectedEnv = rootEnv
        } else {
            selectedEnv = envs.firstOrNull()
        }
    }

    BasicText("linux environment, optional", style = Mono.copy(fontSize = TextSize.Large))
    Spacer(Modifier.height(8.dp))
    BasicText(
        "Linux apps, terminal widgets, coding agents, and Wayland display server.",
        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
    )
    Spacer(Modifier.height(16.dp))

    if (detectedEnvs.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (env in detectedEnvs) {
                Choice(
                    title = if (env.isRoot) "⚡ ${env.displayName}" else env.displayName,
                    subtitle = env.description,
                    picked = selectedEnv?.id == env.id,
                    onToggle = { selectedEnv = env }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    val buttonText = if (selectedEnv?.isRoot == true) {
        "connect ${selectedEnv?.displayName}"
    } else {
        if (picked.isEmpty()) "install linux" else "install linux and ${picked.size} agent${if (picked.size == 1) "" else "s"}"
    }

    PrimaryButton(buttonText, Modifier.takesPassedKeys().passesKeys()) {
        selectedEnv?.let {
            dev.omalauncher.linux.eso.EsoDetector.setActiveEnvironment(runtime.context, it)
        }
        install(picked)
    }
    Spacer(Modifier.height(24.dp))
    AgentPicker(picked) { bin -> picked = if (bin in picked) picked - bin else picked + bin }
}

@Composable
private fun Installed(runtime: Runtime) {
    val env = runtime.proot.activeEnvironment
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        if (!env.isRoot) {
            size = withContext(Dispatchers.IO) { diskUsage(runtime.proot.rootfs) }
        }
    }
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.card(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(env.displayName.lowercase(), style = Mono.copy(fontSize = TextSize.Item))
            BasicText(
                if (env.isRoot) "connected · root chroot (${env.rootfsPath})" else "installed · " + (size?.let(::formatSize) ?: "measuring…"),
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
        }
    }
    if (runtime.gpu.supported && !env.isRoot) {
        Spacer(Modifier.height(8.dp))
        GpuRow(runtime)
    }
    Spacer(Modifier.height(16.dp))
    // Keys go between remove linux and its cancel as they swap, and wait out the removal for the install it ends with.
    val ask = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    if (!confirm) {
        val label = if (env.isRoot) "disconnect environment" else "remove linux"
        BracketAction(label, Palette.red, Modifier.focusRequester(ask).passesKeys(cancel)) { confirm = true }
    } else {
        Column(
            Modifier.card(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = if (env.isRoot) "disconnect environment?" else "remove linux?"
            val msg = if (env.isRoot) {
                "Disconnects from ${env.displayName}. Your files and packages inside ${env.rootfsPath} will NOT be deleted."
            } else {
                "Deletes Linux apps, coding agents, packages and everything in /root, like Downloads. " +
                    "Themes, wallpapers, widgets and your home screen stay."
            }
            BasicText(title, style = Mono.copy(color = Palette.red))
            BasicText(
                msg,
                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
            )
            Row {
                val actionLabel = if (env.isRoot) "disconnect" else "remove"
                BracketAction(actionLabel, Palette.red, Modifier.passesKeys(), runtime::removeLinux)
                BracketAction("cancel", Palette.muted, Modifier.focusRequester(cancel).passesKeys(ask)) { confirm = false }
            }
        }
    }
}

/** Opt-in GPU acceleration for Linux apps; see [dev.omalauncher.linux.Gpu]. */
@Composable
private fun GpuRow(runtime: Runtime) {
    val gpu = runtime.gpu
    val state = gpu.state
    val status =
        when {
            state is JobState.Working -> "${state.action}…"
            gpu.active -> "on · ${gpu.device ?: "GPU"} · for apps opened from now on"
            gpu.wanted && state is JobState.Failed -> "off: the GPU failed its check after a system update"
            gpu.wanted -> "on · waiting for its check"
            state is JobState.Failed -> "the GPU failed its check; apps stay in software"
            else -> "experimental · ${gpu.driverLabel} · faster OpenGL and Vulkan apps and games; terminal apps don't change"
        }
    Column(Modifier.card()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("gpu acceleration", style = Mono.copy(fontSize = TextSize.Item))
                BasicText(
                    status,
                    style = Mono.copy(color = if (gpu.active) Palette.green else Palette.muted, fontSize = TextSize.Small),
                )
            }
            // Keys on one wait out its job, for the one it ends with.
            val passed = Modifier.takesPassedKeys().passesKeys()
            when {
                state is JobState.Working -> {}
                gpu.wanted -> {
                    if (state is JobState.Failed) BracketAction("retry", Palette.accent, passed) { runtime.setGpu(true) }
                    BracketAction("turn off", Palette.red, passed) { runtime.setGpu(false) }
                }
                else -> BracketAction("turn on", Palette.accent, passed) { runtime.setGpu(true) }
            }
        }
        JobLog(state)
    }
}

/** Bytes in [dir], without following symlinks (the rootfs is full of absolute guest links). */
private fun diskUsage(dir: File): Long {
    var total = 0L
    runCatching {
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ) = FileVisitResult.CONTINUE
            },
        )
    }
    return total
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"
