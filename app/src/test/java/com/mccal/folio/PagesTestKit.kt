package com.mccal.folio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Layouts for the Edit Pages and hidden-page tests (PagePlannerTest, HomePagesTest), and the real loader's verdict. */
internal object PagesTestKit {
    fun folderId(n: Int) = "folder:00000000-0000-0000-0000-%012d".format(n)

    /**
     * A Home of [pages] pages the loader accepts, like a real one: widgets in the widget rows (some bound, some in a
     * Smart Stack), apps in the app rows, now and then a folder, plus the unfolded-only page, the dock, per-page
     * looks and Focus pages. Pages in [empty] have nothing on them; every other page has at least one app.
     */
    fun state(random: Random, pages: Int, empty: Set<Int> = emptySet(), hidden: Set<Int> = emptySet()): LauncherState {
        var app = 0
        fun id() = "com.app${app++}/.Main"
        var slot = 0
        var bound = 100
        var folders = 0
        val cells = MutableList<String?>(pages * HOME_CELLS) { null }
        val placements = mutableListOf<WidgetPlacement>()
        val folderEntries = mutableListOf<FolderEntry>()
        val stacks = mutableMapOf<Int, List<Int>>()
        for (page in 0 until pages) {
            if (page in empty) continue
            if (random.nextBoolean()) {
                placements += WidgetPlacement(slot, if (random.nextBoolean()) CLOCK_WIDGET else bound++, page, 0, 0, 2, 2)
                if (random.nextInt(3) == 0) stacks[slot] = listOf(bound++, bound++)
                slot++
            }
            if (random.nextBoolean()) placements += WidgetPlacement(slot++, bound++, page, 2, 0, 2, 2)
            val free = (2 * GRID_COLUMNS until 6 * GRID_COLUMNS).shuffled(random)
            val used = random.nextInt(1, 9)
            free.take(used).forEach { cells[homeCellIndex(page, it)] = id() }
            if (random.nextInt(3) == 0) {
                val folder = folderId(folders++)
                cells[homeCellIndex(page, free[used])] = folder
                folderEntries += FolderEntry(folder, "Folder $folders", List(random.nextInt(2, 5)) { id() })
            }
        }
        placements += WidgetPlacement(slot++, DATE_WIDGET, -1, 0, 0, 2, 2)
        val leading = List(HOME_CELLS) { if (it == 9) id() else null }
        val dock = listOf(id(), null, id(), null)
        val styles = (0 until pages).filter { random.nextInt(3) == 0 }
            .associateWith { PageStyle(PageStyle.SIZES[random.nextInt(PageStyle.SIZES.size)].second, listOf(null, true, false)[random.nextInt(3)]) }
            .filterValues { !it.isDefault }
        val workPages = (0 until pages).filter { random.nextBoolean() }.toSet().ifEmpty { null }
        val modes = DEFAULT_FOCUS_MODES.map { mode -> when (mode.id) {
            "work" -> mode.copy(pages = workPages, homePage = random.nextInt(pages))
            "sleep" -> mode.copy(homePage = random.nextInt(pages))
            else -> mode
        } }
        return LauncherState(homeSlots = cells.dropLastWhile { it == null }, leadingSlots = leading, dock = dock,
            widgetPlacements = placements, folders = folderEntries, widgetStacks = stacks, minPages = pages,
            pageStyles = styles, focusModes = modes, hiddenPages = hidden, loading = false)
    }

    /** A simple Home for hand-written cases: [pages] as lists of cells from the first app row, nothing else. */
    fun simple(vararg pages: List<String?>, hidden: Set<Int> = emptySet()): LauncherState {
        val cells = MutableList<String?>(pages.size * HOME_CELLS) { null }
        pages.forEachIndexed { page, list -> list.forEachIndexed { i, id -> cells[homeCellIndex(page, 2 * GRID_COLUMNS + i)] = id } }
        return LauncherState(homeSlots = cells.dropLastWhile { it == null }, dock = List(4) { null }, widgetPlacements = emptyList(),
            minPages = pages.size, hiddenPages = hidden, loading = false)
    }

    /** Everything on Home, once per appearance: apps (anywhere, in folders too), placed folders, folder entries, widgets. */
    fun inventory(layout: HomeLayout): List<String> = (PageView.appIds(layout).map { "app $it" } +
        (layout.slots + layout.leadingSlots).filterNotNull().filter(::isReservedFolderId).map { "folder $it" } +
        layout.folders.map { "entry ${it.id} ${it.title} ${it.appIds}" } +
        layout.widgetPlacements.map { "widget ${it.slot} ${it.id} ${it.spanX}x${it.spanY}" }).sorted()

    /** A page as a unit: its cells and its widgets (slot, id, spot and size), for "moved whole" checks. */
    fun page(layout: HomeLayout, page: Int): Pair<List<String?>, List<String>> = PageView.cells(layout, page) to
        layout.widgetPlacements.filter { it.page == page }.map { "${it.slot} ${it.id} ${it.column},${it.row} ${it.spanX}x${it.spanY}" }.sorted()

    /**
     * The saved state as LauncherModel.persist writes it (the parts about Home), read back by the real loader. Throws
     * when the loader would reject it, which on the phone would mean "Saved Home layout could not be read".
     */
    fun reload(state: LauncherState): LauncherState {
        fun ids(list: List<String?>) = JSONArray().also { array -> list.forEach { array.put(it ?: JSONObject.NULL) } }
        val widgets = JSONArray().also { array -> state.widgetPlacements.forEach { w -> array.put(JSONObject().put("slot", w.slot)
            .put("id", w.id).put("page", w.page).put("column", w.column).put("row", w.row).put("spanX", w.spanX).put("spanY", w.spanY)) } }
        val folders = JSONArray().also { array -> state.folders.forEach { f -> array.put(JSONObject().put("id", f.id)
            .put("title", f.title).put("apps", JSONArray(f.appIds))) } }
        val saved = JSONObject().put("schema", STATE_SCHEMA).put("pinned", JSONArray(state.order)).put("homeSlots", ids(state.homeSlots))
            .put("leadingSlots", ids(state.leadingSlots)).put("dock", ids(state.dock)).put("widgets", widgets).put("folders", folders)
            .put("restores", JSONArray()).put("minPages", state.minPages).put("hiddenPages", JSONArray(state.hiddenPages.sorted()))
            .put("focusModes", focusModesToJson(state.focusModes))
            .put("widgetStacks", JSONObject().apply { state.widgetStacks.forEach { (slot, ids) -> put(slot.toString(), JSONArray(ids)) } })
            .put("pageStyles", JSONObject().apply { state.pageStyles.forEach { (page, style) -> put(page.toString(),
                JSONObject().put("scale", style.iconScale.toDouble()).apply { style.labels?.let { put("labels", it) } }) } })
        return decodeLauncherState(saved.toString(), legacyRaw = null)
    }

    /** Every order of [items]. */
    fun <T> permutations(items: List<T>): List<List<T>> = if (items.size <= 1) listOf(items)
        else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }

    /** Every subset of [items]. */
    fun <T> subsets(items: List<T>): List<Set<T>> = (0 until (1 shl items.size)).map { mask -> items.filterIndexed { i, _ -> mask shr i and 1 == 1 }.toSet() }
}
