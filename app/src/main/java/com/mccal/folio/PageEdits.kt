package com.mccal.folio

/*
 * Fold8Duo WP-47 · Edit Pages (iOS: jiggle mode › page dots): put Home pages in a new order, hide and show them, and
 * remove empty or hidden ones. One session is a [PagesDraft]; [PagePlanner.apply] turns it into the new saved state
 * in one step, so a whole session is one Home edit that Undo reverts and Layout History records.
 *
 * What travels with a page: its cells (apps, shortcuts, folders), its widgets (the placement moves; the widget keeps
 * its slot, so its host id, its Smart Stack and its restore record, all keyed by slot, go with it untouched), its icon
 * size and labels (pageStyles), its place in every Focus's page choice and opening page, and whether it's hidden. Icon
 * stacks are keyed by their app, so they follow the app. The unfolded-only page and the dock are never touched.
 *
 * Removing: an empty page anywhere, or a hidden page with things on it (after a confirmation on screen). Its apps and
 * shortcuts come off Home (they're still in the App Library), its widgets are removed the way Folio removes any widget
 * (the placement goes; the host keeps the id while Undo can bring it back, then releases it), and folders are never
 * deleted: each one moves to the first free cell on a page Home shows, or onto a new page at the end.
 *
 * Pure: no Android (PagePlannerTest).
 */

/** One Edit Pages session. Pages are numbered as the saved layout had them when the session started. */
internal data class PagesDraft(
    /** Pages in their new order (removed pages left out). */
    val order: List<Int>,
    /** Pages to hide. A removed page keeps its mark, so the planner knows it was hidden. */
    val hidden: Set<Int>,
    /** Pages to remove. */
    val removed: Set<Int> = emptySet(),
    /** How many pages the saved layout had when the session started. */
    val pages: Int = order.size + removed.size,
) {
    /** Pages Home will show. */
    val shownCount: Int get() = order.count { it !in hidden }

    /** The page at position [from] moved to position [to]. */
    fun move(from: Int, to: Int): PagesDraft =
        if (from !in order.indices || to !in order.indices || from == to) this
        else copy(order = order.toMutableList().apply { add(to, removeAt(from)) })

    /** [page] hidden or shown; null when hiding it would leave Home with no page. */
    fun withHidden(page: Int, hide: Boolean): PagesDraft? = when {
        page !in order || (page in hidden) == hide -> this
        hide && shownCount <= 1 -> null
        else -> copy(hidden = if (hide) hidden + page else hidden - page)
    }

    /** [page] removed (the planner still checks it's empty or hidden); null when Home would be left with no page. */
    fun without(page: Int): PagesDraft? = when {
        page !in order -> this
        page !in hidden && shownCount <= 1 -> null
        else -> copy(order = order - page, removed = removed + page)
    }

    companion object {
        /** A session starting from [state]: every page in its place, the hidden ones marked. */
        fun of(state: LauncherState): PagesDraft {
            val pages = state.layout.pageCount
            return PagesDraft((0 until pages).toList(), HomePages.view(state).hidden, emptySet(), pages)
        }

        /** Upstream's "Remove this empty page" while pages are hidden: the last page Home shows, when it's empty. */
        fun removingLastShown(state: LauncherState): PagesDraft? {
            val draft = of(state)
            val last = draft.order.lastOrNull { it !in draft.hidden } ?: return null
            return if (PagePlanner.contents(state.layout, last).empty) draft.without(last) else null
        }
    }
}

internal object PagePlanner {
    enum class Refusal {
        /** Home lost pages while the session was open. */
        STALE,
        /** Every page would be hidden or removed. */
        NO_SHOWN_PAGE,
        /** A page to remove is shown and still has something on it. */
        NOT_EMPTY,
        /** A widget kept from before Folio 0.6 sits under the grid and only loads on its own page number. */
        OLD_WIDGET,
        /** Editing is off: a Focus limits Home's pages, or the saved layout is unreadable or still loading. */
        LOCKED,
    }

    sealed interface Outcome {
        data class Applied(
            val state: LauncherState,
            /** Each kept page's old number to its new one. */
            val pageMap: Map<Int, Int>,
            /** Apps and shortcuts taken off Home with a removed page (they stay in the App Library). */
            val offHome: List<String>,
            /** Slots of the widgets removed with their page. */
            val removedWidgets: List<Int>,
            /** Folders moved off a removed page. */
            val movedFolders: List<String>,
        ) : Outcome

        data class Refused(val reason: Refusal) : Outcome
    }

    data class PageContents(val apps: Int, val folders: Int, val widgets: Int) {
        val empty: Boolean get() = apps == 0 && folders == 0 && widgets == 0
    }

    fun contents(layout: HomeLayout, page: Int): PageContents {
        val cells = PageView.cells(layout, page).filterNotNull()
        return PageContents(cells.count { !isReservedFolderId(it) }, cells.count(::isReservedFolderId),
            layout.widgetPlacements.count { it.page == page })
    }

    /** Whether [page] may be removed in [draft]: hidden, or empty while another page stays on Home. */
    fun removable(layout: HomeLayout, draft: PagesDraft, page: Int): Boolean =
        page in draft.order && (page in draft.hidden || (contents(layout, page).empty && draft.shownCount > 1))

    /** [draft] applied to [state]: the new saved layout and every setting that follows pages, or why it can't be. */
    fun apply(state: LauncherState, draft: PagesDraft): Outcome {
        val layout = state.layout
        val pages = layout.pageCount
        if (pages < draft.pages) return Outcome.Refused(Refusal.STALE)
        // A page Home gained while the session was open (an app arriving on a new page) goes last, shown.
        val order = draft.order + (draft.pages until pages)
        if ((order + draft.removed).sorted() != (0 until pages).toList()) return Outcome.Refused(Refusal.STALE)
        if (order.none { it !in draft.hidden }) return Outcome.Refused(Refusal.NO_SHOWN_PAGE)
        if (draft.removed.any { it !in draft.hidden && !contents(layout, it).empty }) return Outcome.Refused(Refusal.NOT_EMPTY)
        val pageMap = order.withIndex().associate { (index, page) -> page to index }
        // The overflow widget kept from before Folio 0.6 sits under the grid, and the loader accepts it only on page
        // slot / 3: a page holding one keeps its number (removing that page is fine).
        if (layout.widgetPlacements.any { it.page >= 0 && it.row + it.spanY > GRID_ROWS && it.page !in draft.removed && pageMap[it.page] != it.page })
            return Outcome.Refused(Refusal.OLD_WIDGET)
        val cells = MutableList<String?>(order.size * HOME_CELLS) { null }
        order.forEachIndexed { index, page -> PageView.cells(layout, page).forEachIndexed { local, id -> cells[homeCellIndex(index, local)] = id } }
        val leaving = draft.removed.sorted().flatMap { PageView.cells(layout, it).filterNotNull() }
        val folders = leaving.filter(::isReservedFolderId)
        val offHome = leaving.filterNot(::isReservedFolderId)
        val removedWidgets = layout.widgetPlacements.filter { it.page >= 0 && it.page in draft.removed }.map { it.slot }
        val placements = layout.widgetPlacements.filter { it.slot !in removedWidgets }
            .map { if (it.page < 0) it else it.copy(page = pageMap.getValue(it.page)) }
        val hidden = draft.hidden.mapNotNullTo(mutableSetOf()) { pageMap[it] }
        // Folders from a removed page: the first free cell Home shows (never a hidden page's), else a new last page.
        val blocked = placements.flatMapTo(mutableSetOf()) { it.coveredIndices() }.filterTo(mutableSetOf()) { it >= 0 } +
            hidden.flatMap { page -> (0 until HOME_CELLS).map { homeCellIndex(page, it) } }
        var slots: List<String?> = cells.dropLastWhile { it == null }
        folders.forEach { slots = pinHomeApp(slots, it, true, blocked, state.homeAppRows) }
        val arranged = maxOf(order.size, homePageCount(slots.size))
        val next = layout.copy(slots = slots, widgetPlacements = placements,
            widgetRestores = layout.widgetRestores.filter { it.slot !in removedWidgets },
            // Exactly the pages arranged, empty ones included (a saved layout keeps at most 20 empty pages).
            minPages = arranged.coerceIn(1, 20))
        val pageCount = next.pageCount
        val result = state.copy(homeSlots = next.slots, widgetPlacements = next.widgetPlacements,
            widgetRestores = next.widgetRestores, minPages = next.minPages,
            hiddenPages = hidden.filterTo(mutableSetOf()) { it < pageCount },
            pageStyles = state.pageStyles.mapNotNull { (page, style) -> pageMap[page]?.let { it to style } }.toMap(),
            focusModes = state.focusModes.map { mode -> mode.copy(
                // A Focus whose chosen pages all went shows every page again: an empty choice isn't something it can keep.
                pages = mode.pages?.let { chosen -> chosen.mapNotNullTo(mutableSetOf()) { pageMap[it] }.ifEmpty { null } },
                // An opening page past the last one (upstream opens the last page then) is left as it was.
                homePage = mode.homePage?.let { page -> pageMap[page] ?: page.takeIf { it >= pages } }) })
        return Outcome.Applied(result, pageMap, offHome, removedWidgets, folders)
    }
}

/** Home settings that follow pages. They're saved beside the layout, so Undo has to put them back with it. */
internal data class PageSettings(val hiddenPages: Set<Int>, val pageStyles: Map<Int, PageStyle>, val focusModes: List<FocusMode>,
    val minPages: Int) {
    companion object {
        fun of(state: LauncherState) = PageSettings(state.hiddenPages, state.pageStyles, state.focusModes, state.minPages)
    }
}

/**
 * For Undo: a page edit ([before] to [after]) and the page settings it changed ([was] to [now]). Folio's Undo puts the
 * layout back; [restore] then puts these back too.
 */
internal data class PageUndo(val before: HomeLayout, val after: HomeLayout, val was: PageSettings, val now: PageSettings) {
    companion object {
        /**
         * [state] just after Undo put [before] back in place of [after]: when that undid [undo], its page settings
         * return too, each only if nothing else has changed it since.
         */
        fun restore(undo: PageUndo?, before: HomeLayout, after: HomeLayout, state: LauncherState): LauncherState {
            if (undo == null || undo.before != before || undo.after != after) return state
            fun <T> back(current: T, now: T, was: T) = if (current == now) was else current
            return state.copy(
                hiddenPages = back(state.hiddenPages, undo.now.hiddenPages, undo.was.hiddenPages),
                pageStyles = back(state.pageStyles, undo.now.pageStyles, undo.was.pageStyles),
                minPages = back(state.minPages, undo.now.minPages, undo.was.minPages),
                focusModes = state.focusModes.map { mode ->
                    val now = undo.now.focusModes.firstOrNull { it.id == mode.id }
                    val was = undo.was.focusModes.firstOrNull { it.id == mode.id }
                    if (now == null || was == null) mode
                    else mode.copy(pages = back(mode.pages, now.pages, was.pages), homePage = back(mode.homePage, now.homePage, was.homePage))
                })
        }
    }
}
