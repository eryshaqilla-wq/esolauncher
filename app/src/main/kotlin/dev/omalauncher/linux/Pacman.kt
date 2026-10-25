package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

data class Package(
    val name: String,
    val version: String,
    val description: String,
    /** Sync repo (core, extra, alarm, ...) or [AUR]; null for the installed list. */
    val repo: String?,
    val installed: Boolean,
)

/** Installs and removes guest packages from the settings screen, one pacman at a time. */
class Pacman(
    private val context: Context,
    private val proot: Proot,
) {
    /** pacman holds a lock on its database; everything that runs it takes this first. */
    val lock = Mutex()

    /** Per-package install/remove progress. */
    val states = mutableStateMapOf<String, JobState>()

    /** Bumped after installs and removals so lists reload. */
    val revision = mutableIntStateOf(0)

    /**
     * Not offered for removal: what the launcher relies on (foot, fonts, Omarchy's apps), plus
     * base packages. Everything else is either safe to remove or required by something.
     */
    val core: Set<String> get() = if (proot.activeEnvironment.isRoot) {
        setOf("base-files", "dpkg", "apt", "bash", "coreutils", "foot")
    } else {
        CORE_PACKAGES + setOf("base", "archlinuxarm-keyring")
    }

    private val localDb = File(proot.rootfs, "var/lib/pacman/local")

    /**
     * Search packages dynamically based on active environment (apt-cache on Debian/Kali/Ubuntu, pacman/AUR on Arch).
     */
    suspend fun search(query: String): List<Package> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val env = proot.activeEnvironment
        if (env.isRoot) {
            val out = output("apt-cache search -- ${shellQuote(q)} 2>/dev/null | head -n $MAX_RESULTS")
            if (out.isBlank()) return emptyList()
            val installedNames = localNames()
            val lower = q.lowercase()
            return out.lines()
                .filter { it.isNotBlank() && " - " in it }
                .map { line ->
                    val name = line.substringBefore(" - ").trim()
                    val desc = line.substringAfter(" - ").trim()
                    Package(
                        name = name,
                        version = "",
                        description = desc,
                        repo = "apt",
                        installed = name in installedNames,
                    )
                }
                .sortedWith(
                    compareBy<Package>(
                        { it.name != lower },
                        { !it.name.startsWith(lower) },
                        { lower !in it.name },
                        { it.name },
                    )
                )
                .take(MAX_RESULTS)
        }
        return coroutineScope {
            val aur = async { Aur.search(q) }
            val repos = searchRepos(q)
            val names = repos.mapTo(HashSet()) { it.name }
            val local = localNames()
            val fromAur = aur.await().filter { it.name !in names }.map { it.copy(installed = it.name in local) }
            val lower = q.lowercase()
            (repos + fromAur)
                .sortedWith(
                    compareBy<Package>(
                        { it.name != lower },
                        { !it.name.startsWith(lower) },
                        { lower !in it.name },
                        { it.repo == AUR },
                        { it.name },
                    ),
                ).take(MAX_RESULTS)
        }
    }

    /** `pacman -Ss` against the local sync databases. */
    private suspend fun searchRepos(q: String): List<Package> {
        if (q.isEmpty()) return emptyList()
        val out = output("pacman -Ss -- ${shellQuote(q)}")
        val results = mutableListOf<Package>()
        val lines = out.lines()
        var i = 0
        while (i < lines.size) {
            val header = lines[i]
            // "extra/btop 1.3.2-1 (group) [installed]" followed by an indented description.
            if (header.isEmpty() || header[0].isWhitespace() || '/' !in header.substringBefore(' ')) {
                i++
                continue
            }
            val parts = header.split(' ')
            val description = lines.getOrNull(i + 1)?.takeIf { it.startsWith(" ") }?.trim().orEmpty()
            results +=
                Package(
                    name = parts[0].substringAfter('/'),
                    version = parts.getOrElse(1) { "" },
                    description = description,
                    repo = parts[0].substringBefore('/'),
                    installed = "[installed" in header,
                )
            i += 2
        }
        return results
    }

    /**
     * Every installed package's name.
     */
    private suspend fun localNames(): Set<String> =
        withContext(Dispatchers.IO) {
            if (proot.activeEnvironment.isRoot) {
                val out = output("dpkg-query -W -f='\${Package}\\n' 2>/dev/null")
                out.lines().filter { it.isNotBlank() }.toSet()
            } else {
                localDb.list().orEmpty().mapTo(HashSet()) { it.substringBeforeLast('-').substringBeforeLast('-') }
            }
        }

    /**
     * Explicitly installed packages (what `pacman -Qe` or `dpkg-query` lists).
     */
    suspend fun installed(): List<Package> {
        val env = proot.activeEnvironment
        if (env.isRoot) {
            val out = output("dpkg-query -W -f='\${Package}\\t\${Version}\\t\${binary:Summary}\\n' 2>/dev/null")
            if (out.isBlank()) return emptyList()
            return out.lines()
                .filter { it.isNotBlank() && '\t' in it }
                .map { line ->
                    val parts = line.split('\t')
                    Package(
                        name = parts[0],
                        version = parts.getOrElse(1) { "" },
                        description = parts.getOrElse(2) { "" },
                        repo = "apt",
                        installed = true,
                    )
                }
                .sortedBy { it.name }
        }
        return withContext(Dispatchers.IO) {
            localDb
                .listFiles()
                .orEmpty()
                .mapNotNull { dir ->
                    val fields = runCatching { parseDesc(File(dir, "desc").readLines()) }.getOrNull() ?: return@mapNotNull null
                    // %REASON% 1 = installed as a dependency; explicit packages have no entry.
                    if (fields["REASON"] == "1") return@mapNotNull null
                    val name = fields["NAME"] ?: return@mapNotNull null
                    Package(name, fields["VERSION"].orEmpty(), fields["DESC"].orEmpty(), null, installed = true)
                }.sortedBy { it.name }
        }
    }

    /** A local db `desc` file: `%KEY%` lines, each followed by its value (first line kept). */
    private fun parseDesc(lines: List<String>): Map<String, String> {
        val fields = HashMap<String, String>()
        for (i in lines.indices) {
            val key = lines[i]
            if (key.length > 2 && key.startsWith('%') && key.endsWith('%')) fields[key.trim('%')] = lines.getOrNull(i + 1).orEmpty()
        }
        return fields
    }

    /** A full sync plus the package, like the bootstrap: never a partial upgrade. */
    suspend fun install(pkg: Package): Boolean {
        if (proot.activeEnvironment.isRoot) {
            return run(pkg.name, "installing", "DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y ${shellQuote(pkg.name)}")
        }
        return if (pkg.repo == AUR) {
            run(pkg.name, "building", "/usr/local/lib/aur/install.sh ${shellQuote(pkg.name)}")
        } else {
            run(pkg.name, "installing", pacmanInstall(listOf(pkg.name)))
        }
    }

    /**
     * The AUR build scripts (assets/aur) in the guest's /usr/local/lib/aur, the fakeroot shim in its bin,
     * and the yay wrapper that builds as a user even when called as root (by App Finder, say).
     */
    fun managed(): List<Managed> {
        if (proot.activeEnvironment.isRoot) return emptyList()
        val dir = File(proot.rootfs, "usr/local/lib/aur")
        return listOf(
            "install.sh" to File(dir, "install.sh"),
            "fakeroot" to File(dir, "bin/fakeroot"),
            "yay" to File(proot.rootfs, "usr/local/bin/yay"),
        ).map { (asset, target) ->
            Managed(target, Policy.Owned, executable = true) { context.assets.open("aur/$asset").use { it.readBytes() } }
        }
    }

    /** Removes the package and the dependencies nothing else needs. */
    suspend fun remove(name: String): Boolean {
        if (proot.activeEnvironment.isRoot) {
            return run(name, "removing", "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${shellQuote(name)}")
        }
        return run(name, "removing", "pacman -Rns --noconfirm ${shellQuote(name)}")
    }

    /** Kept between searches: starting proot costs more than the search. */
    private val shell = GuestShell(proot)

    private suspend fun output(script: String): String =
        runCatching { shell.run(script, timeoutSeconds = 30).out }.getOrElse {
            Log.w(TAG, "pacman query failed", it)
            ""
        }

    private suspend fun run(
        name: String,
        action: String,
        script: String,
    ): Boolean {
        val (ok, _) = proot.trackedJob(action, script, lock) { if (it == JobState.Idle) states.remove(name) else states[name] = it }
        Log.i(TAG, "$action $name: ${if (ok) "ok" else "failed"}")
        revision.intValue++
        return ok
    }

    companion object {
        private const val TAG = "pacman"

        /** Short AUR queries match thousands of packages. */
        private const val MAX_RESULTS = 100
    }
}
