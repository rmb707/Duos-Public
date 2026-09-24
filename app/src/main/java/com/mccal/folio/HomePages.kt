package com.mccal.folio

/*
 * Fold8Duo WP-47 · Home pages hidden with Edit Pages (iOS: jiggle mode › page dots › uncheck a page).
 *
 * A hidden page keeps its place in the saved layout with everything on it, so everything that reads the saved layout
 * sees it exactly as before: backups and Layout History save it, the widget host keeps its widgets' ids, an
 * uninstalled app still leaves it, and Undo puts it back. Only Home leaves it out. Home draws and edits
 * [PageView.layout], the saved pages without the hidden ones, numbered from 0 like any Home, and [PageView.merge]
 * writes an edit made there back into the saved layout: each shown page returns to its real place, a page the edit
 * added goes after the last real page (never onto a hidden one), and the hidden pages come back untouched, except that
 * an app the edit put on Home leaves the hidden page it was on, because an app is only ever in one place.
 *
 * Why not the Focus way ([FocusPages]): a Focus draws a filtered copy and locks editing while it's on, since an edit to
 * the copy would land on the wrong page of the real layout. A hidden page stays hidden until it's shown again, so
 * editing can't wait for it: the edit is translated instead. While a Focus limits Home's pages, the Focus decides
 * what shows exactly as upstream (editing is locked then anyway).
 *
 * With no page hidden the view is the saved layout itself and merge returns the edit unchanged, so nothing differs
 * from upstream Folio. Pure: no Android (HomePagesTest).
 */

/** Home as it shows with some pages hidden: [shown] real pages, in order, numbered from 0. */
internal class PageView private constructor(
    /** The saved layout. */
    val real: HomeLayout,
    /** Real pages Home shows, in order: Home's page `i` is real page `shown[i]`. */
    val shown: List<Int>,
) {
    private val shownSet = shown.toSet()

    /** Real pages left off Home. */
    val hidden: Set<Int> = (0 until real.pageCount).filterTo(linkedSetOf()) { it !in shownSet }

    /** Whether any page is hidden; otherwise Home is the saved layout itself. */
    val active: Boolean get() = hidden.isNotEmpty()

    /** Folders sitting on hidden pages: off Home with their page, so Home's view leaves them out too. */
    private val hiddenFolders: Set<String> =
        hidden.flatMapTo(mutableSetOf()) { page -> cells(real, page).filterNotNull().filter(::isReservedFolderId) }

    /** What Home draws and edits: the shown pages renumbered from 0; the unfolded-only page and the dock as saved. */
    val layout: HomeLayout = if (!active) real else {
        val placements = real.widgetPlacements.mapNotNull { w -> if (w.page < 0) w else viewPage(w.page)?.let { w.copy(page = it) } }
        val slots = placements.mapTo(mutableSetOf()) { it.slot }
        real.copy(slots = shown.flatMap { cells(real, it) }.dropLastWhile { it == null },
            widgetPlacements = placements, folders = real.folders.filter { it.id !in hiddenFolders },
            widgetRestores = real.widgetRestores.filter { it.slot in slots }, minPages = shown.size)
    }

    /** The real page for Home's page [page]; a page past the last shown one is a new page after every real page. */
    fun realPage(page: Int): Int = when {
        page < 0 -> page
        page < shown.size -> shown[page]
        else -> real.pageCount + page - shown.size
    }

    /** Home's number for real page [page]; null while it's hidden. */
    fun viewPage(page: Int): Int? = if (page < 0) page else shown.indexOf(page).takeIf { it >= 0 }

    /**
     * The saved layout after [edited], an edit of [layout]. Null when the result can't be proven safe (something lost
     * that the edit didn't remove, anything doubled, a widget slot or folder reused): the edit is then refused.
     */
    fun merge(edited: HomeLayout): HomeLayout? {
        if (!active) return edited
        if (edited == layout) return real
        val realPages = real.pageCount
        val viewPages = maxOf(edited.pageCount, shown.size)
        val cells = MutableList((realPages + (viewPages - shown.size).coerceAtLeast(0)) * HOME_CELLS) { real.slots.getOrNull(it) }
        for (page in 0 until viewPages) {
            val target = realPage(page)
            for (local in 0 until HOME_CELLS) cells[homeCellIndex(target, local)] = edited.slots.getOrNull(homeCellIndex(page, local))
        }
        val hiddenPlacements = real.widgetPlacements.filter { it.page >= 0 && it.page in hidden }
        val placements = edited.widgetPlacements.map { if (it.page < 0) it else it.copy(page = realPage(it.page)) } + hiddenPlacements
        val folders = edited.folders + real.folders.filter { it.id in hiddenFolders }
        // Restore records Home's view didn't carry (the hidden pages' widgets) come back as they were.
        val shownRestores = layout.widgetRestores.mapTo(mutableSetOf()) { it.slot }
        val restores = edited.widgetRestores + real.widgetRestores.filter { it.slot !in shownRestores && edited.widgetRestores.none { e -> e.slot == it.slot } }
        val merged = real.copy(slots = cells.dropLastWhile { it == null }, dock = edited.dock, leadingSlots = edited.leadingSlots,
            widgetPlacements = ordered(real.widgetPlacements, placements) { it.slot },
            folders = ordered(real.folders, folders) { it.id },
            widgetRestores = ordered(real.widgetRestores, restores) { it.slot })
        return takeOffHiddenPages(merged, appsOnHome(edited)).takeIf { conserved(edited, it) }
    }

    /**
     * An app the edit put on Home (a shown page, the unfolded-only page, the dock or a shown folder) leaves the hidden
     * page it was on. A folder there left with one app becomes that app, and an empty one goes, exactly as when an app
     * is taken out of a folder on Home ([removeAppFromFolder]).
     */
    private fun takeOffHiddenPages(layout: HomeLayout, onHome: Set<String>): HomeLayout {
        var next = layout
        // Dissolving a folder puts its last app in the folder's cell, which may itself be on Home now: go round again.
        repeat(onHome.size + 2) {
            var changed = false
            for (folderId in hiddenFolders) for (appId in next.folder(folderId)?.appIds.orEmpty().filter { it in onHome }) {
                if (next.folder(folderId)?.appIds?.contains(appId) != true) continue
                next = removeAppFromFolder(next, folderId, appId, DropTarget.Remove)
                changed = true
            }
            val slots = next.slots.toMutableList()
            for (page in hidden) for (local in 0 until HOME_CELLS) {
                val index = homeCellIndex(page, local)
                if (slots.getOrNull(index)?.let { it in onHome } == true) { slots[index] = null; changed = true }
            }
            next = next.copy(slots = slots.dropLastWhile { it == null })
            if (!changed) return next
        }
        return next
    }

    /** Nothing lost that the edit didn't remove, nothing doubled, no widget slot or folder reused. */
    private fun conserved(edited: HomeLayout, merged: HomeLayout): Boolean {
        val before = appIds(real)
        val after = appIds(merged)
        val afterSet = after.toSet()
        val removedByEdit = appIds(layout).toSet() - appIds(edited).toSet()
        if (!(before.toSet() - removedByEdit).all { it in afterSet }) return false
        if (!appIds(edited).all { it in afterSet }) return false
        val counts = before.groupingBy { it }.eachCount()
        if (after.groupingBy { it }.eachCount().any { (id, n) -> n > maxOf(1, counts[id] ?: 0) }) return false
        val slots = merged.widgetPlacements.map { it.slot }
        if (slots.distinct().size != slots.size) return false
        val slotsRemovedByEdit = layout.widgetPlacements.map { it.slot }.toSet() - edited.widgetPlacements.map { it.slot }.toSet()
        if (!(real.widgetPlacements.map { it.slot }.toSet() - slotsRemovedByEdit).all { it in slots }) return false
        if (!boundIdsUnique(merged) && boundIdsUnique(real)) return false
        if (!foldersPlacedOnce(merged) && foldersPlacedOnce(real)) return false
        return true
    }

    companion object {
        fun of(layout: HomeLayout, hidden: Set<Int>): PageView {
            val pages = layout.pageCount
            val shown = (0 until pages).filter { it !in hidden }
            // Never an empty Home: if every page were marked hidden (a damaged save), Home shows them all.
            return PageView(layout, shown.ifEmpty { (0 until pages).toList() })
        }

        /** The [HOME_CELLS] cells of real page [page]. */
        fun cells(layout: HomeLayout, page: Int): List<String?> = List(HOME_CELLS) { layout.slots.getOrNull(homeCellIndex(page, it)) }

        /** Every app on Home, each time it appears: pages, the unfolded-only page, the dock and inside folders. */
        fun appIds(layout: HomeLayout): List<String> =
            (layout.slots + layout.leadingSlots + layout.dock).filterNotNull().filterNot(::isReservedFolderId) + layout.folders.flatMap { it.appIds }

        private fun appsOnHome(layout: HomeLayout) = appIds(layout).toSet()

        private fun boundIdsUnique(layout: HomeLayout) = layout.widgetPlacements.map { it.id }.filter { it >= 0 }.let { it.distinct().size == it.size }

        private fun foldersPlacedOnce(layout: HomeLayout): Boolean {
            val placed = (layout.slots + layout.leadingSlots).filterNotNull().filter(::isReservedFolderId)
            return placed.distinct().size == placed.size && placed.toSet() == layout.folders.map { it.id }.toSet()
        }

        /** [items] in the order their keys have in [reference]; keys new to it keep their own order, after those. */
        private fun <T, K> ordered(reference: List<T>, items: List<T>, key: (T) -> K): List<T> {
            val rank = reference.withIndex().associate { (i, item) -> key(item) to i }
            return items.withIndex().sortedWith(compareBy({ rank[key(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        }
    }
}

/** Hidden pages as saved (LauncherModel.persist): page numbers; anything else is skipped, so a bad entry hides nothing. */
internal fun hiddenPagesFromJson(array: org.json.JSONArray?): Set<Int> =
    array?.let { a -> (0 until a.length()).mapNotNullTo(mutableSetOf()) { i -> a.optInt(i, -1).takeIf { it >= 0 } } }.orEmpty()

/** Home's side of hidden pages: what Home draws, and the helpers the model and Home use to speak Home's numbering. */
internal object HomePages {
    fun view(state: LauncherState): PageView = PageView.of(state.layout, state.hiddenPages)

    /**
     * What Home draws: while a Focus limits Home's pages, exactly upstream's filtered copy (editing is locked then);
     * otherwise the shown pages, with each page's icon size and labels following its page. [LauncherState.hiddenPages]
     * holds only pages that exist, so Home can translate a real page number ([focusHomePage]).
     */
    fun effective(state: LauncherState): LauncherState {
        if (FocusPages.lockingFocus(state) != null) return FocusPages.effective(state)
        val view = view(state)
        if (!view.active) return if (state.hiddenPages.isEmpty()) state else state.copy(hiddenPages = emptySet())
        val layout = view.layout
        return state.copy(homeSlots = layout.slots, widgetPlacements = layout.widgetPlacements, folders = layout.folders,
            widgetRestores = layout.widgetRestores, minPages = layout.minPages, hiddenPages = view.hidden,
            pageStyles = view.shown.mapIndexedNotNull { page, real -> state.pageStyles[real]?.let { page to it } }.toMap())
    }

    /** How many pages Home shows (in [effective]); upstream's count while a Focus limits them. */
    fun shownPages(state: LauncherState): Int = if (FocusPages.lockingFocus(state) != null) state.homePages else view(state).shown.size

    /** Cells of hidden pages: nothing new is put there (an app arriving goes to a page Home shows). */
    fun hiddenCells(state: LauncherState): Set<Int> =
        view(state).hidden.flatMapTo(mutableSetOf()) { page -> (0 until HOME_CELLS).map { homeCellIndex(page, it) } }

    /**
     * Add to Home ([pinned]) or Remove from Home on the pages Home shows, as upstream's setPinned does on the whole
     * layout: the first free cell Home shows, or a new page after the last one. The saved slots are returned; they're
     * the old ones when the change can't be merged safely.
     */
    fun pin(state: LauncherState, id: String, pinned: Boolean): List<String?> {
        val view = view(state)
        val home = view.layout
        val blocked = home.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }.filterTo(mutableSetOf()) { it >= 0 }
        val merged = view.merge(home.copy(slots = pinHomeApp(home.slots, id, pinned, blocked, state.homeAppRows))) ?: return state.homeSlots
        // Pinning never touches folders; a merge that did (an app leaving a hidden folder) is left to a real edit.
        return if (merged.folders == state.folders) merged.slots else state.homeSlots
    }

    /**
     * The page a Focus opens Home on, in Home's numbering: [home] is what Home draws ([effective]); null when the
     * Focus has no page or its page is hidden.
     */
    fun focusHomePage(mode: FocusMode?, home: LauncherState): Int? {
        val hidden = home.hiddenPages
        val real = FocusModes.homePage(mode, home.homePages + hidden.size) ?: return null
        return if (real in hidden) null else real - hidden.count { it < real }
    }
}
