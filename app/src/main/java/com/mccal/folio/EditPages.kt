package com.mccal.folio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Fold8Duo WP-47 · Edit Pages, iPhone's page editor. While icons jiggle, tapping the page dots (or Edit › Edit Pages)
 * shows every Home page as a thumbnail with a check circle: uncheck to hide a page, hold and drag to put it elsewhere,
 * and a hidden or empty page has a minus to remove it. Done, Back, a tap outside the pages, or leaving jiggle mode
 * applies the whole session as one Home edit (LauncherModel.applyPages), which Undo in Settings reverts.
 *
 * The rules live in PageEdits.kt and HomePages.kt; this file only draws the session and collects it.
 */

/** Whether Edit Pages is showing: opened by the page dots and the Edit menu, shown by [EditPagesHost]. */
internal object EditPages {
    var open by mutableStateOf(false)
        private set

    fun show() { open = true }
    fun close() { open = false }
}

/**
 * Hosts Edit Pages inside Home. [homePage] is the page Home shows now and [homePages] how many pages Home has laid out
 * (Home's numbering); after Done, [onShowPage] gets the new number of the page Home was on (the first page when it was
 * hidden or removed), at a moment the pager can take it without passing through the App Library, which would end
 * jiggle mode: at once when that page exists already, else once Home has laid out its new pages.
 */
@Composable
internal fun EditPagesHost(model: LauncherModel, apps: Map<String, AppEntry>, appRows: Int, editing: Boolean, homePage: Int,
    homePages: Int, onShowPage: (Int) -> Unit) {
    if (!EditPages.open) return
    // Home going away (the activity ending) ends the session unapplied: nothing on Home changes.
    DisposableEffect(Unit) { onDispose { EditPages.close() } }
    val context = LocalContext.current
    val real by model.state.collectAsState()
    val startPage = remember { HomePages.view(model.state.value).shown.getOrNull(homePage) }
    val laidOut by rememberUpdatedState(homePages)
    var landing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var finished by remember { mutableStateOf(false) }
    landing?.let { (page, count) ->
        LaunchedEffect(page, count) {
            withTimeoutOrNull(1_000) { snapshotFlow { laidOut }.first { it == count } }
            onShowPage(page)
            EditPages.close()
        }
    }
    if (finished) return
    EditPagesOverlay(real, apps, appRows, editing) { draft ->
        if (finished) return@EditPagesOverlay
        finished = true
        when (val outcome = model.applyPages(draft)) {
            is PagePlanner.Outcome.Applied -> {
                val page = startPage?.let { outcome.pageMap[it] }?.let(HomePages.view(model.state.value)::viewPage) ?: 0
                if (page < laidOut) onShowPage(page)
                landing = page to HomePages.shownPages(model.state.value)
            }
            is PagePlanner.Outcome.Refused -> {
                IslandEvents.notice(context, context.getString(when (outcome.reason) {
                    PagePlanner.Refusal.STALE -> R.string.edit_pages_refused_stale
                    PagePlanner.Refusal.OLD_WIDGET -> R.string.edit_pages_refused_old_widget
                    PagePlanner.Refusal.NOT_EMPTY -> R.string.edit_pages_refused_not_empty
                    PagePlanner.Refusal.NO_SHOWN_PAGE -> R.string.edit_pages_last_page
                    PagePlanner.Refusal.LOCKED -> R.string.edit_pages_refused_locked
                }))
                EditPages.close()
            }
        }
    }
}

/** The page editor over Home, in its own window so Back and the keyboard focus are its own. */
@Composable
internal fun EditPagesOverlay(real: LauncherState, apps: Map<String, AppEntry>, appRows: Int, editing: Boolean = true,
    onDone: (PagesDraft) -> Unit) {
    DisposableEffect(Unit) { LauncherSheetsOpen.intValue++; onDispose { LauncherSheetsOpen.intValue-- } }
    val draft = rememberSaveable(saver = DraftStateSaver) { mutableStateOf(PagesDraft.of(real)) }
    val done by rememberUpdatedState { onDone(draft.value) }
    // Pressing Home (or anything else that ends jiggle mode) ends the session the way Done does.
    LaunchedEffect(editing) { if (!editing) done() }
    Dialog(onDismissRequest = { done() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        LaunchedEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                }
            }
        }
        EditPagesContent(real.layout, draft, apps, appRows, onDone = { done() })
    }
}

/** A draft survives the activity being recreated: page counts and page numbers as one IntArray. */
private val DraftStateSaver = Saver<MutableState<PagesDraft>, IntArray>(
    save = { state -> state.value.let { d -> (listOf(d.pages, d.order.size) + d.order + d.hidden.size + d.hidden + d.removed.size + d.removed).toIntArray() } },
    restore = { saved -> runCatching {
        var i = 0
        fun next() = saved[i++]
        val pages = next()
        val order = List(next()) { next() }
        val hidden = List(next()) { next() }.toSet()
        val removed = List(next()) { next() }.toSet()
        mutableStateOf(PagesDraft(order, hidden, removed, pages))
    }.getOrNull() })

/** The page editor itself (no window), so it can be checked on the JVM (EditPagesRenderTest). */
@Composable
internal fun EditPagesContent(layout: HomeLayout, draft: MutableState<PagesDraft>, apps: Map<String, AppEntry>, appRows: Int, onDone: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) { if (note != null) { delay(2_600); note = null } }
    val lastPageNote = stringResource(R.string.edit_pages_last_page)
    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, MotionSpeed.spring(.9f, 520f)) }
    // Every thumbnail is as tall as the page that needs the most rows, so all of them line up.
    val rows = remember(layout, appRows) {
        (0 until layout.pageCount).maxOfOrNull { page -> shownHomeRows(appRows, PageView.cells(layout, page), layout.widgetPlacements.filter { it.page == page }) }
            ?.coerceAtLeast(visibleHomeRows(appRows)) ?: visibleHomeRows(appRows)
    }
    fun toggle(page: Int) {
        val d = draft.value
        val next = d.withHidden(page, page !in d.hidden)
        if (next == null) { haptic.performHapticFeedback(HapticFeedbackType.Reject); note = lastPageNote }
        else { haptic.performHapticFeedback(HapticFeedbackType.ToggleOn); draft.value = next }
    }
    fun remove(page: Int) {
        if (!PagePlanner.removable(layout, draft.value, page)) return
        if (PagePlanner.contents(layout, page).empty) draft.value.without(page)?.let { draft.value = it } else confirm = page
    }
    // A tap outside the pages is Done (TalkBack has the Done button: a clickable here would merge every page into one node).
    val tapDone by rememberUpdatedState(onDone)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }.background(FolioGlass.scrim)
        .pointerInput(Unit) { detectTapGestures { tapDone() } }
        .testTag("edit-pages")) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.folioSafeTop).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 60.dp, bottom = 64.dp)) {
            val order = draft.value.order
            val gap = 16.dp
            val checkRow = 44.dp
            val aspect = GRID_COLUMNS.toFloat() / rows
            val room = maxWidth - 32.dp
            // About iPhone's size on the cover (three across); up to six across the unfolded screen, an even number so
            // the fold runs between two columns rather than through a page.
            val columns = evenColumnsOnHinge(((room + gap) / (128.dp + gap)).toInt().coerceIn(2, 6), 2).coerceAtMost(maxOf(1, order.size))
            val cardWidth = minOf(168.dp, (room - gap * (columns - 1)) / columns, (maxHeight * .7f - checkRow) * aspect).coerceAtLeast(56.dp)
            val cardHeight = cardWidth / aspect + checkRow
            val gridRows = (order.size + columns - 1) / columns
            val gridWidth = cardWidth * columns + gap * (columns - 1)
            val gridHeight = cardHeight * gridRows + gap * (gridRows - 1).coerceAtLeast(0)
            val top = ((maxHeight - gridHeight) / 2).coerceAtLeast(0.dp)
            val stepX = with(density) { (cardWidth + gap).toPx() }
            val stepY = with(density) { (cardHeight + gap).toPx() }
            val cardPx = with(density) { Size(cardWidth.toPx(), cardHeight.toPx()) }
            fun slot(index: Int) = IntOffset(((index % columns) * stepX).roundToInt(), ((index / columns) * stepY).roundToInt())
            val scroll = rememberScrollState()
            var dragging by remember { mutableStateOf<Int?>(null) }
            var dragOffset by remember { mutableStateOf(Offset.Zero) }
            val slotOf by rememberUpdatedState(::slot)
            val geometry by rememberUpdatedState(PagesGrid(columns, gridRows, cardPx, stepX, stepY))
            // Where the held page is over now decides its place; the others make room.
            fun follow() {
                val page = dragging ?: return
                val d = draft.value
                val from = d.order.indexOf(page).takeIf { it >= 0 } ?: return
                val grid = geometry
                val origin = slotOf(from)
                val centerX = origin.x + dragOffset.x + grid.card.width / 2
                val centerY = origin.y + dragOffset.y + grid.card.height / 2
                val column = floor(centerX / grid.stepX).toInt().coerceIn(0, grid.columns - 1)
                val row = floor(centerY / grid.stepY).toInt().coerceIn(0, maxOf(0, grid.rows - 1))
                val to = (row * grid.columns + column).coerceIn(0, d.order.size - 1)
                if (to == from) return
                draft.value = d.move(from, to)
                val landed = slotOf(to)
                dragOffset += Offset((origin.x - landed.x).toFloat(), (origin.y - landed.y).toFloat())
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            // Near the top or bottom edge while holding a page, the grid scrolls under it.
            LaunchedEffect(dragging) {
                val edge = with(density) { 56.dp.toPx() }
                val step = with(density) { 10.dp.toPx() }
                val viewport = with(density) { maxHeight.toPx() }
                val offsetTop = with(density) { top.toPx() }
                while (dragging != null) {
                    withFrameNanos { }
                    val page = dragging ?: break
                    val index = draft.value.order.indexOf(page).takeIf { it >= 0 } ?: break
                    val y = offsetTop + slotOf(index).y + dragOffset.y - scroll.value
                    val speed = when { y < edge -> -step; y + geometry.card.height > viewport - edge -> step; else -> 0f }
                    if (speed != 0f) {
                        val moved = scroll.scrollBy(speed)
                        if (moved != 0f) { dragOffset += Offset(0f, moved); follow() }
                    }
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(top))
                // A tap on a page (or between pages) does nothing; a tap outside the grid is Done.
                Box(Modifier.width(gridWidth).height(gridHeight).testTag("edit-pages-grid").pointerInput(Unit) { detectTapGestures { } }) {
                    order.forEachIndexed { index, page ->
                        key(page) {
                            val target = slot(index)
                            val position = remember { Animatable(target, IntOffset.VectorConverter) }
                            val held = dragging == page
                            LaunchedEffect(target, held) {
                                if (held) return@LaunchedEffect
                                if (reduceMotion) position.snapTo(target) else position.animateTo(target, MotionSpeed.spring(.82f, 420f))
                            }
                            val hidden = page in draft.value.hidden
                            val empty = PagePlanner.contents(layout, page).empty
                            PageCard(page, index, order.size, layout, rows, apps, hidden, empty,
                                removable = PagePlanner.removable(layout, draft.value, page), cardWidth = cardWidth,
                                onToggle = { toggle(page) }, onRemove = { remove(page) },
                                onMove = { to -> draft.value = draft.value.move(index, to) },
                                modifier = Modifier
                                    .offset { if (dragging == page) slotOf(draft.value.order.indexOf(page).coerceAtLeast(0)) + dragOffset.round() else position.value }
                                    .zIndex(if (held) 1f else 0f)
                                    .graphicsLayer {
                                        val lift = if (dragging == page && !reduceMotion) 1.06f else 1f
                                        scaleX = lift; scaleY = lift
                                    }
                                    .pointerInput(page) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragging = page; dragOffset = Offset.Zero; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                            onDragEnd = { scope.launch { release(page, draft, slotOf, dragOffset, position); dragging = null; dragOffset = Offset.Zero } },
                                            onDragCancel = { scope.launch { release(page, draft, slotOf, dragOffset, position); dragging = null; dragOffset = Offset.Zero } },
                                        ) { change, amount -> change.consume(); dragOffset += amount; follow() }
                                    })
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        JigglePill(stringResource(R.string.done), emphasized = true, modifier = Modifier.align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.folioSafeTop).padding(top = 8.dp, end = 12.dp).testTag("edit-pages-done")) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm); onDone()
        }
        Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 24.dp, vertical = 12.dp)
            .widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            note?.let { Text(it, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 6.dp).testTag("edit-pages-note")) }
            Text(stringResource(R.string.edit_pages_hint), color = FolioGlass.secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
    confirm?.let { page -> RemovePageAlert(PagePlanner.contents(layout, page), onDismiss = { confirm = null }) {
        draft.value.without(page)?.let { draft.value = it }
        confirm = null
    } }
}

/** A held page settles from under the finger into its place (instantly with Reduce Motion, which skips the spring). */
private suspend fun release(page: Int, draft: MutableState<PagesDraft>, slot: (Int) -> IntOffset, dragOffset: Offset,
    position: Animatable<IntOffset, *>) {
    val index = draft.value.order.indexOf(page).takeIf { it >= 0 } ?: return
    position.snapTo(slot(index) + dragOffset.round())
}

private fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())

/** The grid as last laid out, read by the drag (whose gesture outlives any one layout pass). */
private data class PagesGrid(val columns: Int, val rows: Int, val card: Size, val stepX: Float, val stepY: Float)

@Composable
private fun PageCard(page: Int, index: Int, count: Int, layout: HomeLayout, rows: Int, apps: Map<String, AppEntry>, hidden: Boolean,
    empty: Boolean, removable: Boolean, cardWidth: Dp, onToggle: () -> Unit, onRemove: () -> Unit, onMove: (Int) -> Unit, modifier: Modifier) {
    val number = index + 1
    val label = stringResource(when { hidden -> R.string.edit_pages_page_hidden; empty -> R.string.edit_pages_page_empty; else -> R.string.edit_pages_page_shown }, number)
    val toggleLabel = stringResource(if (hidden) R.string.edit_pages_show_page else R.string.edit_pages_hide_page, number)
    val removeLabel = stringResource(R.string.edit_pages_remove_page, number)
    val earlier = stringResource(R.string.edit_pages_move_earlier)
    val later = stringResource(R.string.edit_pages_move_later)
    Column(modifier.width(cardWidth).testTag("edit-page-$page"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(cardWidth * rows.toFloat() / GRID_COLUMNS)
            .semantics {
                contentDescription = label
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(earlier) { onMove(index - 1); true })
                    if (index < count - 1) add(CustomAccessibilityAction(later) { onMove(index + 1); true })
                    add(CustomAccessibilityAction(toggleLabel) { onToggle(); true })
                    if (removable) add(CustomAccessibilityAction(removeLabel) { onRemove(); true })
                }
            }) {
            val shape = RoundedCornerShape(14.dp)
            PageThumbnail(layout, page, rows, apps, Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) .4f else 1f }
                .background(Color.White.copy(alpha = if (hidden) .06f else .12f), shape).border(1.dp, Color.White.copy(alpha = .18f), shape))
            if (removable) JiggleRemoveButton(removeLabel, inset = 4.dp, onRemove = onRemove)
        }
        // iPhone's check circle: filled blue with a white tick while the page is on Home, an empty ring while hidden.
        Box(Modifier.fillMaxWidth().height(44.dp).toggleable(value = !hidden, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = toggleLabel }.testTag("edit-page-check-$page"), contentAlignment = Alignment.Center) {
            if (!hidden) Box(Modifier.size(20.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CheckCircle, null, tint = FolioColors.Blue, modifier = Modifier.size(26.dp))
            } else Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = Color.White.copy(alpha = .75f), modifier = Modifier.size(26.dp))
        }
    }
}

/** A sketch of one page: its apps and folders where they sit, and its widgets as glass blocks. */
@Composable
private fun PageThumbnail(layout: HomeLayout, page: Int, rows: Int, apps: Map<String, AppEntry>, modifier: Modifier) {
    val cells = remember(layout, page) { PageView.cells(layout, page) }
    val widgets = remember(layout, page) { layout.widgetPlacements.filter { it.page == page && it.row < rows } }
    val icons = remember(cells, layout.folders, apps) {
        cells.filterNotNull().flatMap { id -> if (isReservedFolderId(id)) layout.folder(id)?.appIds.orEmpty().take(4) else listOf(id) }
            .mapNotNull { id -> apps[id]?.let { id to it.icon.asImageBitmap() } }.toMap()
    }
    Canvas(modifier) {
        val cell = size.width / GRID_COLUMNS
        val row = size.height / rows
        val unit = minOf(cell, row)
        widgets.forEach { w ->
            val pad = unit * .08f
            drawRoundRect(Color.White.copy(alpha = .2f), Offset(w.column * cell + pad, w.row * row + pad),
                Size(w.spanX * cell - 2 * pad, minOf(w.spanY, rows - w.row) * row - 2 * pad), CornerRadius(unit * .22f))
        }
        cells.forEachIndexed { local, id ->
            val r = local / GRID_COLUMNS
            if (id == null || r >= rows) return@forEachIndexed
            val icon = unit * .62f
            val x = (local % GRID_COLUMNS) * cell + (cell - icon) / 2
            val y = r * row + (row - icon) / 2
            if (isReservedFolderId(id)) {
                drawRoundRect(Color.White.copy(alpha = .28f), Offset(x, y), Size(icon, icon), CornerRadius(icon * .24f))
                layout.folder(id)?.appIds?.take(4)?.forEachIndexed { i, child ->
                    icons[child]?.let { drawIcon(it, x + icon * (.1f + .44f * (i % 2)), y + icon * (.1f + .44f * (i / 2)), icon * .36f) }
                }
            } else icons[id]?.let { drawIcon(it, x, y, icon) }
        }
    }
}

private fun DrawScope.drawIcon(bitmap: ImageBitmap, x: Float, y: Float, size: Float) {
    if (size < 1f) return
    clipPath(Path().apply { addRoundRect(RoundRect(x, y, x + size, y + size, CornerRadius(size * .24f))) }) {
        drawImage(bitmap, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), IntOffset(x.roundToInt(), y.roundToInt()),
            IntSize(size.roundToInt(), size.roundToInt()), filterQuality = FilterQuality.Medium)
    }
}

/** iPhone's question before a hidden page with things on it goes: what happens to its apps, widgets and folders. */
@Composable
private fun RemovePageAlert(contents: PagePlanner.PageContents, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val lines = buildList {
        if (contents.apps > 0) add(pluralStringResource(R.plurals.edit_pages_remove_apps, contents.apps, contents.apps))
        if (contents.widgets > 0) add(pluralStringResource(R.plurals.edit_pages_remove_widgets, contents.widgets, contents.widgets))
        if (contents.folders > 0) add(pluralStringResource(R.plurals.edit_pages_remove_folders, contents.folders, contents.folders))
        add(stringResource(R.string.edit_pages_remove_undo))
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_pages_remove_title)) },
        text = { Text(lines.joinToString(" ")) },
        confirmButton = { TextButton(onClick = onRemove, modifier = Modifier.testTag("edit-pages-remove-confirm")) {
            Text(stringResource(R.string.edit_pages_remove_confirm), color = FolioColors.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
