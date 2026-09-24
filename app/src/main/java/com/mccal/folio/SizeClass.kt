package com.mccal.folio

import android.content.res.Configuration
import android.util.DisplayMetrics

/**
 * How much to scale dp by to judge a size class at the phone's own screen density. Developer options' "Smallest
 * width" and Display size change how many dp a screen reports, but a phone-sized screen should still get the phone
 * layout: a Galaxy Z Fold8 cover set to 600dp was getting the unfolded one (bottom dock, wide margins).
 */
internal val Configuration.classScale: Float
    get() = classScale(densityDpi, DisplayMetrics.DENSITY_DEVICE_STABLE)

/** Regular size (the unfolded screen, tablets), judged at the phone's own density. */
internal fun Configuration.fitsRegularHomeLayout(): Boolean = fitsRegularHomeLayout(screenWidthDp.toFloat(), screenHeightDp.toFloat(), classScale)

/**
 * How many columns Settings shows at once.
 *
 * 1 is the phone: one page at a time, and above 600 dp the list opens over it from the sidebar button. 2 is iPad
 * Settings: the list beside the page, in either orientation, once there's room for two readable columns. 3 adds the
 * page you opened *from* a page - a tweak beside the Tweaks list - so tapping a row adds a column instead of
 * replacing what you were looking at.
 *
 * [nested] is true when the open page came from a list on another page. [onFold] is true when the phone is half
 * folded with the fold down the screen: the divider belongs on the crease then, and a third column would put one of
 * them across it.
 */
internal fun settingsColumns(
    widthDp: Float,
    heightDp: Float,
    classScale: Float = 1f,
    nested: Boolean = false,
    onFold: Boolean = false,
): Int = when {
    !fitsRegularHomeLayout(widthDp, heightDp, classScale) || widthDp < 700f -> 1
    nested && !onFold && widthDp >= 920f -> 3
    else -> 2
}
