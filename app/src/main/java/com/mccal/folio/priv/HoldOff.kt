package com.mccal.folio.priv

/**
 * Fold8Duo: when early light should leave an opening alone (SPEC §4.5 I-5, narrowed — see docs/decisions.md D14).
 *
 * Early light overrules One UI's "hold TENT until ~92°". Two situations want One UI's own behaviour instead:
 *  - **a camera in front.** People stand a folded phone up as a tent to take a picture with the cover screen as the
 *    viewfinder; taking that screen away for the second it takes the stall rule to notice is the one place early light
 *    gets in the way of something deliberate.
 *  - **a phone call.** The phone is at an ear; nobody is looking at either screen.
 *
 * The lock screen is deliberately NOT here: the owner wants the animation there (AT-F0, AT-F0b), and asking for a
 * different panel does nothing to the keyguard.
 *
 * Pure (no android.*): the judgement is tested on the JVM; [ForegroundWatch] fetches the two facts it needs.
 */
internal object HoldOffPolicy {
    /** AudioManager.MODE_IN_CALL: a cellular call, not a VoIP or video call (people do open the phone for those). */
    const val MODE_IN_CALL = 2

    private val cameras = setOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2")

    fun isCamera(packageName: String): Boolean =
        packageName in cameras || packageName.endsWith(".camera") || ".camera." in packageName

    /** A reason to hold off, or null. */
    fun reason(topPackage: String?, audioMode: Int): String? = when {
        audioMode == MODE_IN_CALL -> "a phone call"
        topPackage != null && isCamera(topPackage) -> "a camera in front ($topPackage)"
        else -> null
    }
}
