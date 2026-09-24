package com.mccal.folio

import com.mccal.folio.PagesTestKit.reload
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Hidden Home pages (HomePages.kt): Home's view of the saved layout, and every Home edit written back through it. */
class HomePagesTest {
    private val folder = PagesTestKit.folderId(9)

    /** Page 0: a, a2 and widget 0. Page 1, hidden: b, a folder (f, g) and widget 1. Page 2: c and widget 2. */
    private fun three(): LauncherState = PagesTestKit.simple(listOf("a", "a2"), listOf("b", folder), listOf("c"), hidden = setOf(1)).copy(
        folders = listOf(FolderEntry(folder, "Fun", listOf("f", "g"))), dock = listOf("d", null, null, null),
        widgetPlacements = listOf(WidgetPlacement(0, 100, 0, 0, 0, 2, 2), WidgetPlacement(1, 101, 1, 0, 0, 2, 2),
            WidgetPlacement(2, 102, 2, 2, 0, 2, 2), WidgetPlacement(3, DATE_WIDGET, -1, 0, 0, 2, 2)),
        pageStyles = mapOf(1 to PageStyle(.82f), 2 to PageStyle(1.14f)), homeRows = BASE_APP_ROWS)

    private fun cell(page: Int, local: Int) = homeCellIndex(page, local)
    private fun <T> Random.pick(list: List<T>) = list[nextInt(list.size)]

    @Test fun `with nothing hidden Home is the saved layout and an edit passes straight through`() {
        val state = PagesTestKit.state(Random(1), 3)
        val layout = state.layout
        val view = PageView.of(layout, emptySet())
        assertSame(layout, view.layout)
        val edited = dropApp(layout, "com.new/.Main", DropTarget.Home(cell(1, 20)))
        assertSame(edited, view.merge(edited))
        assertSame(state, HomePages.effective(state))
        assertEquals(3, HomePages.shownPages(state))
        assertTrue(HomePages.hiddenCells(state).isEmpty())
        // A page number that doesn't exist hides nothing.
        assertEquals(3, HomePages.view(state.copy(hiddenPages = setOf(7))).shown.size)
    }

    @Test fun `hidden pages leave Home, the shown ones renumbered with their widgets`() {
        val state = three()
        val view = HomePages.view(state)
        assertEquals(listOf(0, 2), view.shown)
        assertEquals(setOf(1), view.hidden)
        val home = view.layout
        assertEquals(listOf("a", "a2"), PageView.cells(home, 0).filterNotNull())
        assertEquals(listOf("c"), PageView.cells(home, 1).filterNotNull())
        assertEquals(listOf(0 to 0, 2 to 1, 3 to -1), home.widgetPlacements.map { it.slot to it.page })
        // The hidden page's folder is off Home with it; the dock and the unfolded-only page stay.
        assertTrue(home.folders.isEmpty())
        assertEquals(state.dock, home.dock)
        assertEquals(2, home.pageCount)
        val drawn = HomePages.effective(state)
        assertEquals(2, drawn.homePages)
        assertEquals(mapOf(1 to PageStyle(1.14f)), drawn.pageStyles)
        assertEquals(setOf(1), drawn.hiddenPages)
        assertEquals(2, HomePages.shownPages(state))
        assertEquals((0 until HOME_CELLS).map { cell(1, it) }.toSet(), HomePages.hiddenCells(state))
    }

    @Test fun `a move on Home lands on the real pages`() {
        val state = three()
        val view = HomePages.view(state)
        // c, on Home's page 1 (real page 2), goes to Home's page 0.
        val merged = view.merge(dropApp(view.layout, "c", DropTarget.Home(cell(0, 12))))!!
        assertEquals("c", merged.slots[cell(0, 12)])
        assertNull(merged.slots.getOrNull(cell(2, 8)))
        assertEquals(PageView.cells(state.layout, 1), PageView.cells(merged, 1))
    }

    @Test fun `a drop past the last page Home shows opens a new page after every real one`() {
        val state = three()
        val view = HomePages.view(state)
        val merged = view.merge(dropApp(view.layout, "com.new/.Main", DropTarget.Home(cell(2, 8))))!!
        assertEquals("com.new/.Main", merged.slots[cell(3, 8)])
        assertEquals(4, merged.pageCount)
        assertEquals(PageView.cells(state.layout, 1), PageView.cells(merged, 1))
        val widget = view.merge(placeWidget(view.layout, WidgetPlacement(7, 200, 2, 0, 0, 2, 2)))!!
        assertEquals(3, widget.placement(7)!!.page)
    }

    @Test fun `apps pushed along never spill onto a hidden page`() {
        // Page 0 full in every row Home shows, page 1 hidden, page 2 shown.
        val cells = MutableList<String?>(3 * HOME_CELLS) { null }
        repeat(visibleHomeRows(BASE_APP_ROWS) * GRID_COLUMNS) { cells[cell(0, it)] = "full$it" }
        cells[cell(1, 8)] = "hidden"
        cells[cell(2, 8)] = "later"
        val state = LauncherState(homeSlots = cells, widgetPlacements = emptyList(), minPages = 3, hiddenPages = setOf(1), homeRows = BASE_APP_ROWS)
        val view = HomePages.view(state)
        val merged = view.merge(dropApp(view.layout, "com.new/.Main", DropTarget.Home(cell(0, 0)), state.homeAppRows))!!
        assertEquals("com.new/.Main", merged.slots[cell(0, 0)])
        assertEquals(PageView.cells(state.layout, 1), PageView.cells(merged, 1))
        assertEquals("full23", merged.slots[cell(2, 0)])
        assertEquals(PageView.appIds(state.layout).toSet() + "com.new/.Main", PageView.appIds(merged).toSet())
    }

    @Test fun `an app brought to Home from a hidden page leaves it`() {
        val state = three()
        val view = HomePages.view(state)
        val merged = view.merge(dropApp(view.layout, "b", DropTarget.Home(cell(0, 13))))!!
        assertEquals("b", merged.slots[cell(0, 13)])
        assertNull(merged.slots[cell(1, 8)])
        assertEquals(1, PageView.appIds(merged).count { it == "b" })
    }

    @Test fun `an app taken from a hidden folder leaves it, and a folder left with one app becomes that app`() {
        val state = three()
        val view = HomePages.view(state)
        val toDock = view.merge(dropApp(view.layout, "f", DropTarget.Dock(1)))!!
        assertEquals("f", toDock.dock[1])
        assertEquals("g", toDock.slots[cell(1, 9)])
        assertTrue(toDock.folders.isEmpty())
        val newFolder = PagesTestKit.folderId(10)
        val grouped = view.merge(createFolder(view.layout, FolderEntry(newFolder, "Pair", emptyList()), "a", "g", cell(0, 8)))!!
        assertEquals(listOf("a", "g"), grouped.folder(newFolder)!!.appIds)
        assertEquals("f", grouped.slots[cell(1, 9)])
        assertNull(grouped.folder(folder))
        assertEquals(PageView.appIds(state.layout).toSet(), PageView.appIds(grouped).toSet())
    }

    @Test fun `Add to Home uses the first free cell Home shows, never a hidden page`() {
        val state = three()
        val slots = HomePages.pin(state, "com.new/.Main", true)
        assertEquals(cell(0, 2), slots.indexOf("com.new/.Main"))
        // With page 0 full, the next page Home shows (real page 2), not the hidden page 1.
        val full = state.copy(homeSlots = state.homeSlots.toMutableList().apply {
            (0 until visibleHomeRows(BASE_APP_ROWS) * GRID_COLUMNS).filter { it !in setOf(0, 1, 4, 5, 8, 9) }.forEach { set(cell(0, it), "x$it") }
        })
        assertEquals(2, homeCellPage(HomePages.pin(full, "com.new/.Main", true).indexOf("com.new/.Main")))
        // An app on the hidden page moves to Home rather than being there twice; removing leaves the hidden page alone.
        val moved = HomePages.pin(state, "b", true)
        assertEquals(1, moved.count { it == "b" })
        assertEquals(0, homeCellPage(moved.indexOf("b")))
        assertEquals(state.homeSlots, HomePages.pin(state, "b", false))
    }

    @Test fun `widgets move to real pages and the hidden page's widgets stay put`() {
        val state = three()
        val view = HomePages.view(state)
        val moved = view.merge(moveWidget(view.layout, 2, cell(0, 2)))!!
        assertEquals(WidgetPlacement(2, 102, 0, 2, 0, 2, 2), moved.placement(2))
        assertEquals(state.layout.placement(1), moved.placement(1))
        // An edit that reused the hidden widget's slot would lose one of them: refused.
        assertNull(view.merge(placeWidget(view.layout, WidgetPlacement(1, 300, 0, 2, 0, 2, 2))))
    }

    @Test fun `while a Focus chooses the pages, Home shows exactly what upstream shows`() {
        val work = DEFAULT_FOCUS_MODES.first { it.id == "work" }.copy(pages = setOf(0, 1))
        val state = three().copy(focusModes = DEFAULT_FOCUS_MODES.map { if (it.id == "work") work else it }, activeFocus = "work")
        assertEquals(FocusPages.effective(state), HomePages.effective(state))
        assertEquals(state.homePages, HomePages.shownPages(state))
    }

    @Test fun `a Focus opening page is found in Home's numbering`() {
        val drawn = HomePages.effective(three())
        val sleep = DEFAULT_FOCUS_MODES.first { it.id == "sleep" }
        assertEquals(1, HomePages.focusHomePage(sleep.copy(homePage = 2), drawn))
        assertEquals(0, HomePages.focusHomePage(sleep.copy(homePage = 0), drawn))
        assertNull(HomePages.focusHomePage(sleep.copy(homePage = 1), drawn))
        assertNull(HomePages.focusHomePage(sleep, drawn))
        // Nothing hidden: exactly upstream's answer.
        val plain = PagesTestKit.simple(listOf("a"), listOf("b"))
        assertEquals(FocusModes.homePage(sleep.copy(homePage = 1), 2), HomePages.focusHomePage(sleep.copy(homePage = 1), plain))
    }

    @Test fun `hidden pages are saved and read back, and a bad entry hides nothing`() {
        val state = three()
        val loaded = reload(state)
        assertEquals(setOf(1), loaded.hiddenPages)
        assertEquals(state.layout, loaded.layout)
        assertEquals(setOf(0, 2), hiddenPagesFromJson(JSONArray("""[2, -1, "x", 0]""")))
        assertTrue(hiddenPagesFromJson(null).isEmpty())
    }

    @Test fun `any Home edit made while pages are hidden keeps the hidden pages and loses or doubles nothing`() {
        var edits = 0
        for (seed in 0 until 60) {
            val random = Random(seed)
            val pages = random.nextInt(2, 6)
            var state = PagesTestKit.state(random, pages)
            state = state.copy(hiddenPages = (0 until pages).filter { random.nextInt(3) == 0 }.toSet().let { if (it.size == pages) it - 0 else it })
            var fresh = 0
            var slot = 1000
            var folders = 100
            repeat(30) {
                val view = HomePages.view(state)
                val home = view.layout
                val apps = PageView.appIds(state.layout)
                val app = if (random.nextInt(4) == 0) "com.fresh${fresh++}/.Main" else random.pick(apps)
                val index = cell(random.nextInt(0, home.pageCount + 1), random.nextInt(HOME_CELLS))
                val rows = state.homeAppRows
                val edited = when (random.nextInt(8)) {
                    0, 1 -> dropApp(home, app, DropTarget.Home(index), rows)
                    2 -> dropApp(home, app, DropTarget.Dock(random.nextInt(4)), rows)
                    3 -> removePlacement(home, DropTarget.Home(index))
                    4 -> createFolder(home, FolderEntry(PagesTestKit.folderId(folders++), "New", emptyList()), app, random.pick(apps), index)
                    5 -> home.widgetPlacements.filter { it.page >= 0 }.takeIf { it.isNotEmpty() }?.let { moveWidget(home, random.pick(it).slot, index) } ?: home
                    6 -> placeWidget(home, WidgetPlacement(slot++, 5000 + slot, random.nextInt(0, home.pageCount + 1), random.nextInt(3), random.nextInt(8), 2, 2))
                    else -> home.folders.filter { it.id in home.slots }.takeIf { it.isNotEmpty() }?.let { shown ->
                        val f = random.pick(shown)
                        removeAppFromFolder(home, f.id, random.pick(f.appIds), random.pick(listOf(DropTarget.Home(index), DropTarget.Dock(random.nextInt(4)), DropTarget.Remove)), rows)
                    } ?: home
                }
                val merged = view.merge(edited)
                assertNotNull("seed $seed: a Home edit was refused", merged)
                assertSafe(state, view, edited, merged!!)
                state = state.copy(homeSlots = merged.slots, dock = merged.dock, leadingSlots = merged.leadingSlots,
                    widgetPlacements = merged.widgetPlacements, folders = merged.folders, widgetRestores = merged.widgetRestores)
                edits++
                // Now and then a page is hidden or shown again between edits.
                if (random.nextInt(5) == 0) {
                    val page = random.nextInt(state.layout.pageCount)
                    val next = if (page in state.hiddenPages) state.hiddenPages - page else state.hiddenPages + page
                    if (next.size < state.layout.pageCount) state = state.copy(hiddenPages = next)
                }
            }
        }
        assertTrue("ran $edits edits", edits == 60 * 30)
    }

    /** The one-place rule and nothing else: what [edited] did to Home, written back, and the hidden pages as they were. */
    private fun assertSafe(before: LauncherState, view: PageView, edited: HomeLayout, merged: HomeLayout) {
        val real = before.layout
        val onHome = PageView.appIds(edited).toSet()
        val removedByEdit = PageView.appIds(view.layout).toSet() - onHome
        assertEquals(PageView.appIds(real).toSet() - removedByEdit + onHome, PageView.appIds(merged).toSet())
        val apps = PageView.appIds(merged)
        assertEquals("an app is on Home twice", apps.size, apps.distinct().size)
        val slotsRemoved = view.layout.widgetPlacements.map { it.slot }.toSet() - edited.widgetPlacements.map { it.slot }.toSet()
        assertEquals(real.widgetPlacements.map { it.slot }.toSet() - slotsRemoved + edited.widgetPlacements.map { it.slot },
            merged.widgetPlacements.map { it.slot }.toSet())
        for (page in view.hidden) {
            assertEquals(real.widgetPlacements.filter { it.page == page }, merged.widgetPlacements.filter { it.page == page })
            PageView.cells(real, page).zip(PageView.cells(merged, page)).forEach { (was, now) ->
                val ok = was == now || (now == null && was in onHome) || (was != null && isReservedFolderId(was) &&
                    merged.folder(was) == null && (now == null || real.folder(was)!!.appIds.contains(now)))
                assertTrue("hidden page $page: $was became $now", ok)
            }
        }
        for (page in 0 until maxOf(edited.pageCount, view.shown.size))
            assertEquals(PageView.cells(edited, page), PageView.cells(merged, view.realPage(page)))
        val placed = (merged.slots + merged.leadingSlots).filterNotNull().filter(::isReservedFolderId)
        assertEquals(placed.size, placed.distinct().size)
        assertEquals(placed.toSet(), merged.folders.map { it.id }.toSet())
        val saved = before.copy(homeSlots = merged.slots, dock = merged.dock, leadingSlots = merged.leadingSlots,
            widgetPlacements = merged.widgetPlacements, folders = merged.folders)
        assertEquals(merged, reload(saved).layout)
    }
}
