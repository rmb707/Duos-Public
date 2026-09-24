@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mccal.folio

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Fold8Duo: the App Library as one A–Z list of every app (asked for by the owner, 2026-09-21: "view all my apps in a list
 * if I swipe right one more time after all my screens"). The page after the last Home page shows it; a letter strip down
 * the side jumps between sections, iPhone-style; the unfolded screen fits more apps to a row. The category tiles are one
 * tap away (the button beside the search field, or Settings > Search & App Library). Lives apart from AppLibrary.kt,
 * which only calls in, so upstream's App Library keeps merging.
 */

/**
 * One section after another: a sticky letter, then its apps [columns] to a row. [recentlyAdded] (null except while
 * browsing the list) goes above them all.
 */
internal fun LazyListScope.libraryListSections(
    sections: List<Pair<String, List<AppEntry>>>, columns: Int, glass: Boolean, dark: Boolean,
    drag: HomeDragState?, page: Int?, onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    recentlyAdded: List<AppEntry>? = null,
) {
    val perRow = columns.coerceAtLeast(1)
    recentlyAdded?.let { recentlyAddedSection(it, perRow, glass, page, onLaunchFrom, onActions) }
    sections.forEach { (letter, apps) ->
        stickyHeader(key = "list-heading-$letter") { LetterHeading(letter, glass, dark) }
        items(apps.chunked(perRow), key = { row -> "list-row-" + row.first().id }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { app -> LibraryListApp(app, Modifier.weight(1f), drag, page, onLaunchFrom, onActions) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Recently Added (WP-48): apps installed in the last week, newest first, at most 8 (RecentlyAdded.kt), in the list's own
 * rows. It sits above the A–Z sections and never after them: the letter strip finds its headings counting back from the
 * end of the list ([LibraryIndex.headingIndices]).
 *
 * The apps are looked up off the main thread, so they arrive a moment after the list is drawn. A lazy list holds on to
 * its first visible item, and at the top that is the letter A (the Downloading row above it has no height when nothing
 * downloads), so the section would land above the screen. A 1 dp anchor, there whenever the list is browsed, is that
 * first item instead, and the section appears under it. Scrolled down the list, nothing moves.
 *
 * Tap opens and a long press gives the app's menu, but these rows can't be dragged to Home: each app has its row in the
 * A–Z list as well, and Home's drag keeps one region per app, which two rows would fight over.
 */
private fun LazyListScope.recentlyAddedSection(
    apps: List<AppEntry>, perRow: Int, glass: Boolean, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
) {
    item(key = "list-recent-anchor") { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
    if (apps.isEmpty()) return
    item(key = "list-recent-heading") { RecentlyAddedHeading(glass) }
    items(apps.chunked(perRow), key = { row -> "list-recent-row-" + row.first().id }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { app -> LibraryListApp(app, Modifier.weight(1f), null, page, onLaunchFrom, onActions, tag = "library-recent-") }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Recently Added's title, in the style of the Downloading row above it. */
@Composable
private fun RecentlyAddedHeading(glass: Boolean) {
    val ink = if (glass) Ink else MaterialTheme.colorScheme.onSurface
    Text(stringResource(R.string.fold8_recently_added), Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp).semantics { heading() },
        color = ink.copy(alpha = .7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * Recently Added for [apps] while [show] (browsing the list: not searching, not on the category tiles, not choosing Home
 * apps), else null. Empty until the lookup is done; after that the last answer stays while a new one is found.
 */
@Composable
internal fun rememberRecentlyAdded(apps: List<AppEntry>, show: Boolean): List<AppEntry>? {
    val context = LocalContext.current
    val found by produceState(emptyList<AppEntry>(), apps, show) {
        if (show) value = withContext(Dispatchers.IO) { recentlyAddedApps(context, apps) }
    }
    return if (show) found else null
}

/**
 * The apps in [apps] installed in the last week, newest first (RecentlyAdded.kt), by Android's own record of when each
 * was first installed: one query per profile, so a work app counts from its install in the work profile. Pinned
 * shortcuts and apps that aren't available have no install to go by. Folio itself is listed only as its Settings app,
 * and like Settings on iPhone that never counts as added. Call off the main thread.
 */
internal fun recentlyAddedApps(context: Context, apps: List<AppEntry>): List<AppEntry> {
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
    val installed = HashMap<String, Long>()
    apps.filter { !it.isShortcut && it.available && it.packageName != context.packageName }.groupBy { it.user }.forEach { (user, entries) ->
        val firstInstall = runCatching { launcherApps.getActivityList(null, user) }.getOrNull()
            ?.associate { it.componentName to it.firstInstallTime } ?: return@forEach
        entries.forEach { app -> firstInstall[app.component]?.let { installed[app.id] = it } }
    }
    return RecentlyAdded.pick(apps, System.currentTimeMillis(), { installed[it.id] })
}

/** The same letter heading the App Library's search list has always used. */
@Composable
private fun LetterHeading(letter: String, glass: Boolean, dark: Boolean) {
    val ink = if (glass) Ink else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // An opaque small chip prevents text from showing through the sticky letter.
        Box(Modifier.size(width = 32.dp, height = 28.dp).background(letterChipColor(glass, dark), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Text(letter, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
        if (glass) HorizontalDivider(Modifier.weight(1f).padding(start = 10.dp), color = Color.White.copy(alpha = .24f))
    }
}

@Composable
private fun letterChipColor(glass: Boolean, dark: Boolean) =
    if (glass) (if (dark) Color(0xFF314852) else Color(0xFFB7CBD3)) else MaterialTheme.colorScheme.surfaceContainer

/**
 * One app in the list: icon and name. Tap opens it from its icon; a long press gives its options (or, with Home's drag, picks it up).
 * [tag] prefixes the test tag, so an app shown twice (Recently Added and its letter) still has one "library-app-" row.
 */
@Composable
private fun LibraryListApp(
    app: AppEntry, modifier: Modifier, drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit, tag: String = "library-app-",
) {
    val launchBounds = remember { android.graphics.Rect() }
    val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
    val open = { onLaunchFrom(app, launchBounds) }
    val optionsLabel = stringResource(R.string.app_options)
    Row(modifier.heightIn(min = 60.dp).then(dragModifier).clip(RoundedCornerShape(14.dp)).testTag(tag + app.id)
        .then(if (drag == null) Modifier.combinedClickable(onClick = open, onLongClick = { onActions(app) })
            else Modifier.clickable(onClick = open).semantics { onLongClick(optionsLabel) { onActions(app); true } })
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, null, Modifier.size(40.dp)
            .onGloballyPositioned { launchBounds.set(it.boundsInWindow().toAndroidBounds()) }.clip(RoundedCornerShape(10.dp)))
        Row(Modifier.weight(1f).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            NewAppDot(app.packageName, 7.dp)
            Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        }
    }
}

/**
 * The letter strip down the side of the list: touch or slide along it and the list jumps to that letter, with a tick for
 * each new one and the letter shown large beside your finger. Letters with no apps are dimmed and land on the next section.
 */
@Composable
internal fun LibraryLetterStrip(
    listState: LazyListState, sections: List<Pair<String, List<AppEntry>>>, columns: Int, ink: Color, haptics: Boolean,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val present = remember(sections) { sections.mapTo(HashSet()) { it.first } }
    val strip = remember(present) { LibraryIndex.strip(present) }
    val presentNow by rememberUpdatedState(present)
    val sectionsNow by rememberUpdatedState(sections)
    val columnsNow by rememberUpdatedState(columns)
    val tick by rememberUpdatedState(haptics)
    val jump: (String) -> Unit = { letter ->
        val sizes = sectionsNow.map { it.first to it.second.size }
        LibraryIndex.headingIndices(sizes, columnsNow, listState.layoutInfo.totalItemsCount)[letter]?.let { index ->
            scope.launch { listState.scrollToItem(index) }
        }
    }
    var held by remember { mutableStateOf(false) }
    var heldLetter by remember { mutableStateOf("") }
    var fingerY by remember { mutableFloatStateOf(0f) }
    val reduceMotion = LocalReduceMotion.current
    val bubble by animateFloatAsState(if (held) 1f else 0f, if (reduceMotion) snap() else tween(if (held) 90 else 220), label = "library-letter")
    val dark = LocalDuoPalette.current.dark
    val indexLabel = stringResource(R.string.library_index)
    val context = LocalContext.current
    // A control, not reading text: sized in dp so a large font setting can't push the letters into each other.
    val letterSize = with(density) { 11.dp.toSp() }
    BoxWithConstraints(modifier.width(26.dp).testTag("library-letter-strip")) {
        val rowHeight = 16.dp
        val rows = with(density) { ((constraints.maxHeight - 8.dp.toPx()) / rowHeight.toPx()).toInt() }
        val shown = remember(strip, rows) { LibraryIndex.shown(strip, rows) }
        var stripTop by remember { mutableFloatStateOf(0f) }
        Column(Modifier.align(Alignment.Center).width(26.dp)
            .background(if (held) ink.copy(alpha = .10f) else Color.Transparent, RoundedCornerShape(13.dp))
            .padding(vertical = 4.dp)
            // Where the letters start, for the bubble: the finger's y below is measured from here.
            .onGloballyPositioned { stripTop = it.positionInParent().y }
            .pointerInput(strip) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last: String? = null
                    fun at(y: Float) {
                        fingerY = y
                        val letter = strip[LibraryIndex.slotAt(y, size.height.toFloat(), strip.size)]
                        val target = LibraryIndex.target(letter, strip, presentNow) ?: return
                        heldLetter = target
                        if (target == last) return
                        last = target
                        if (tick) view.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
                        jump(target)
                    }
                    held = true
                    try {
                        at(down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { change.consume(); break }
                            if (change.positionChanged()) { change.consume(); at(change.position.y) }
                        }
                    } finally { held = false }
                }
            }
            .semantics {
                contentDescription = indexLabel
                customActions = strip.filter { it in present }.map { letter ->
                    CustomAccessibilityAction(context.getString(R.string.library_jump_to, letter)) { jump(letter); true }
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally) {
            shown.forEach { entry ->
                Box(Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                    if (entry == null) Box(Modifier.size(3.dp).background(ink.copy(alpha = .5f), CircleShape))
                    else Text(entry, color = if (entry in present) ink else ink.copy(alpha = .35f), fontSize = letterSize,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        // The letter you're on, large, just left of the strip at your finger's height (kept inside the list). It takes no
        // room of its own: measured at its full size and placed outside the narrow strip.
        val bubbleSize = 56.dp
        val stripHeight = constraints.maxHeight
        if (heldLetter.isNotEmpty()) Box(Modifier
            .layout { measurable, _ ->
                val side = bubbleSize.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(0, 0) {
                    val y = (stripTop + fingerY - side / 2f).coerceIn(0f, (stripHeight - side).coerceAtLeast(0).toFloat())
                    placeable.place(-(side + 14.dp.roundToPx()), y.roundToInt())
                }
            }
            .graphicsLayer { alpha = bubble; val s = .85f + .15f * bubble; scaleX = s; scaleY = s }
            .background(letterChipColor(glass = true, dark = dark), CircleShape),
            contentAlignment = Alignment.Center) {
            Text(heldLetter, color = ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Beside the App Library's search field: list or category tiles, remembered (the same setting as in Settings). */
@Composable
internal fun LibraryViewToggle(categories: Boolean, ink: Color, modifier: Modifier = Modifier) {
    val model = androidx.lifecycle.viewmodel.compose.viewModel<LauncherModel>()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (Fold8Defaults.takeLibraryList(context) && model.state.value.libraryCategories) model.setLibraryCategories(false)
    }
    val label = stringResource(if (categories) R.string.library_show_list else R.string.library_show_categories)
    Box(modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = .12f))
        .clickable(role = Role.Button, onClickLabel = label) { model.setLibraryCategories(!categories) }
        .semantics { contentDescription = label }
        .testTag("library-view-toggle"), contentAlignment = Alignment.Center) {
        Icon(if (categories) Icons.AutoMirrored.Rounded.FormatListBulleted else Icons.Rounded.GridView, null,
            tint = ink.copy(alpha = .7f), modifier = Modifier.size(20.dp))
    }
}

/** Fold8Duo's one-time choices for the owner's phone, kept out of Folio's saved state so upstream merges never meet them. */
internal object Fold8Defaults {
    private const val PREFS = "folio"
    private const val KEY_LIBRARY_LIST = "fold8duo.libraryListApplied"

    /** True the first time it's asked on this install: the App Library then opens as the A–Z list (the owner's request). */
    fun takeLibraryList(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (prefs.getBoolean(KEY_LIBRARY_LIST, false)) return false
        prefs.edit().putBoolean(KEY_LIBRARY_LIST, true).apply()
        return true
    }
}
