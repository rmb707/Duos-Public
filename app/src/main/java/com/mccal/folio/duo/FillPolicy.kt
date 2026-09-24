package com.mccal.folio.duo

/** What to switch: [enable] gets the platform's Full screen, [reset] goes back to the app's own default. */
internal data class FillPlan(val enable: List<String>, val reset: List<String>) {
    val isEmpty get() = enable.isEmpty() && reset.isEmpty()
}

/**
 * Fold8Duo (WP-58): which apps should fill the inner screen. Pure — the caller brings the app list and what the device
 * has switched on now; this only decides. Per app, the owner's own choice (on / off) wins; the default reaches the
 * apps that qualify (the ones he installed, in his own profile). The launcher switches off only what it switched on
 * itself, or what the owner said off to: an app set through Samsung's own settings is left alone.
 */
internal object FillPolicy {
    fun wants(pkg: String, byDefault: Boolean, candidates: Set<String>, on: Set<String>, off: Set<String>): Boolean = when (pkg) {
        in on -> true
        in off -> false
        else -> byDefault && pkg in candidates
    }

    /** [current]: on now, per the device. [ours]: what this launcher switched on itself. Nothing touches [front]. */
    fun plan(byDefault: Boolean, candidates: Set<String>, on: Set<String>, off: Set<String>, current: Set<String>,
        ours: Set<String>, front: String? = null): FillPlan {
        val considered = (candidates + on + off + ours) - setOfNotNull(front)
        val enable = considered.filter { it !in current && wants(it, byDefault, candidates, on, off) }.sorted()
        val reset = considered.filter { it in current && !wants(it, byDefault, candidates, on, off) && (it in ours || it in off) }.sorted()
        return FillPlan(enable, reset)
    }
}
