package com.mccal.folio.duo

/**
 * Fold8Duo (docs/status/FEATURE-TIERS.md): one app, two tiers the user picks at first run. Pure: no android.*, so the
 * rule that decides what each tier may do runs as plain JVM tests.
 *
 * Lite needs nothing granted and is the default. Full adds the few things only an elevated grant can do. A capability
 * takes its elevated route only when the user chose Full AND the grant is actually live right now; otherwise it uses
 * its Lite fallback, so Lite never reaches for a grant it does not have and Full degrades cleanly if the grant drops.
 */
internal enum class Tier { LITE, FULL }

/** A feature that behaves differently between the tiers. Every one here needs the elevated grant for its Full route. */
internal enum class Capability {
    /** Full: the true hinge angle. Lite: the Earth-compensated magnetometer estimate (duo/MagHinge.kt). */
    HINGE_ANGLE,
    /** Full: the inner screen lights at the opening onset. Lite: One UI's own panel swap. */
    EARLY_LIGHT,
    /** Full: a live copy of the screen under the over-app effect. Lite: a still, or a plain sweep. */
    LIVE_FROST,
    /** Full: the app's own last frame in the open card. Lite: the icon grows into the app. */
    APP_SNAPSHOT,
    /** Full: per-app full screen in one tap. Lite: one tap to Samsung's own Aspect ratio page. */
    ONE_TAP_FULL_SCREEN,
}

internal object FeatureTiers {
    /** Whether [capability] may use its elevated route now. Never in Lite; in Full only while the grant is live. */
    fun elevated(tier: Tier, capability: Capability, grantLive: Boolean): Boolean = tier == Tier.FULL && grantLive

    /** The capabilities running on their Lite fallback right now — what the chooser and Settings name as "missing". */
    fun degraded(tier: Tier, grantLive: Boolean): List<Capability> =
        Capability.entries.filterNot { elevated(tier, it, grantLive) }
}
