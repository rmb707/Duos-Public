package com.mccal.folio.priv

/**
 * Fold8Duo (WP-58): the platform's own "Full screen" for one app — app-compat change OVERRIDE_ANY_ORIENTATION_TO_USER,
 * switched the way Samsung's Settings switches it, through `am compat` as the shell user (the change is marked
 * overridable on this build, so the shell may set it). Switched on, the app follows the phone's rotation and ignores
 * its own aspect limits on the inner screen instead of sitting in a box. The platform persists the override across
 * reboots and restarts that app's processes when it changes. Undo by hand: `am compat reset <change> <package>`.
 */
internal class AppCompatOverrides(private val log: (String) -> Unit) {
    /** Switch the change on ([on] = true) or back to the platform's default. Returns `am`'s own line, or why not. */
    fun set(packageName: String, on: Boolean): String {
        if (!PACKAGE.matches(packageName)) return "refused: not a package name"
        val verb = if (on) "enable" else "reset"
        val reply = run("am compat $verb $CHANGE $packageName")
        log("full screen $verb $packageName: $reply")
        return reply
    }

    /** The packages the change is on for right now, comma-separated; "" for none; "failed: …" when unreadable. */
    fun packages(): String {
        val dump = run("dumpsys platform_compat")
        val line = dump.lineSequence().firstOrNull { "name=$CHANGE;" in it }
            ?: return "failed: change not listed (${dump.take(80)})"
        val overrides = PACKAGE_OVERRIDES.find(line)?.groupValues?.get(1) ?: return ""
        return overrides.split(',').map { it.trim() }.filter { it.endsWith("=true") }.joinToString(",") { it.removeSuffix("=true") }
    }

    private fun run(command: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", "$command 2>&1").start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        text
    }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }

    companion object {
        const val CHANGE = "OVERRIDE_ANY_ORIENTATION_TO_USER"
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val PACKAGE_OVERRIDES = Regex("packageOverrides=\\{([^}]*)}")
    }
}
