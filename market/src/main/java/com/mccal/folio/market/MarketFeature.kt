package com.mccal.folio.market

/**
 * Who sees the Market.
 *
 * 0.7.0 gives it to Folio Dev and to supporters with a code, while everyone else keeps every feature they already
 * had. The tweaks and themes the Market hands out are all still in Settings, so nobody on the stable release loses
 * anything by not seeing the store yet.
 *
 * Beta Updates used to open it too. It doesn't any more (McCal, 2026-09-19): the store is what a supporter gets for
 * supporting, and a switch anyone can flick is not that. It goes the other way round now — redeeming a code turns
 * the beta channel on, so a supporter gets the builds as well as the store.
 *
 * When 0.7.0 ships properly, [RELEASED] becomes true and the gate stops mattering.
 */
object MarketFeature {
    /** True once the Market ships to everyone. */
    const val RELEASED = false

    /** Folio Dev (debug and fast builds) always has it, so it can be tested beside the signed release. */
    fun isDevBuild(packageName: String) = packageName.endsWith(".dev")

    /** [hasEarlyCode] is a supporter's code carrying the beta scope. It isn't needed once the Market is released. */
    fun isEnabled(packageName: String, hasEarlyCode: Boolean = false): Boolean =
        // Fold8Duo: Folio Dev is the owner's daily Home in this fork, not a Market test bed; with it on, Folio Dev's own
        // icon and the Home settings gear open the store instead of Settings. Supporter codes and the release still count.
        RELEASED || hasEarlyCode
}
