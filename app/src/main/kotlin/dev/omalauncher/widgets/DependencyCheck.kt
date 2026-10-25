package dev.omalauncher.widgets

import dev.omalauncher.linux.shellQuote

/**
 * Which declared pacman packages are missing, and which programs widgets run
 * that aren't installed, from one guest shell call: [script] prints a
 * `pkg <name>` or `bin <name>` line for each, and [parse] reads them back.
 */
internal object DependencyCheck {
    class Missing(
        val packages: Set<String>,
        val programs: Set<String>,
    )

    /** Prints packages and programs that aren't installed, dynamically checking pacman or dpkg. */
    fun script(
        packages: Collection<String>,
        programs: Collection<String>,
    ): String =
        (if (packages.isEmpty()) "" else {
            "if command -v pacman >/dev/null 2>&1; then " +
                "pacman -T ${packages.joinToString(" ") { shellQuote(it) }} | sed 's/^/pkg /'; " +
            "elif command -v dpkg-query >/dev/null 2>&1; then " +
                "for p in ${packages.joinToString(" ") { shellQuote(it) }}; do " +
                    "dpkg-query -W -f='\${Status}' \"\$p\" 2>/dev/null | grep -q \"ok installed\" || echo \"pkg \$p\"; " +
                "done; " +
            "fi; "
        }) +
        (if (programs.isEmpty()) "" else "for c in ${programs.joinToString(" ") { shellQuote(it) }}; do command -v \"\$c\" >/dev/null || echo \"bin \$c\"; done; ") +
        "true"

    fun parse(out: String): Missing {
        val lines = out.lines().map { it.trim() }
        return Missing(
            lines.filter { it.startsWith("pkg ") }.map { it.removePrefix("pkg ") }.toSet(),
            lines.filter { it.startsWith("bin ") }.map { it.removePrefix("bin ") }.toSet(),
        )
    }
}
