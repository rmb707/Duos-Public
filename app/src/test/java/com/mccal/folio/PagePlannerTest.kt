package com.mccal.folio

import com.mccal.folio.PagesTestKit.inventory
import com.mccal.folio.PagesTestKit.page
import com.mccal.folio.PagesTestKit.permutations
import com.mccal.folio.PagesTestKit.reload
import com.mccal.folio.PagesTestKit.subsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Edit Pages (PageEdits.kt): reorder, hide, remove, and Undo, on the owner's real kind of Home. */
class PagePlannerTest {
    private fun applied(state: LauncherState, draft: PagesDraft): PagePlanner.Outcome.Applied {
        val outcome = PagePlanner.apply(state, draft)
        assertTrue("expected the edit to apply, got $outcome", outcome is PagePlanner.Outcome.Applied)
        return outcome as PagePlanner.Outcome.Applied
    }

    private fun refusal(state: LauncherState, draft: PagesDraft) = (PagePlanner.apply(state, draft) as? PagePlanner.Outcome.Refused)?.reason

    /** Every check that must hold after pages only moved (and were hidden or shown): nothing lost, doubled or changed. */
    private fun assertMovedWhole(before: LauncherState, draft: PagesDraft, result: PagePlanner.Outcome.Applied) {
        val after = result.state
        val map = result.pageMap
        assertEquals(inventory(before.layout), inventory(after.layout))
        for (old in 0 until before.layout.pageCount) assertEquals("page $old", page(before.layout, old), page(after.layout, map.getValue(old)))
        assertEquals(before.leadingSlots, after.leadingSlots)
        assertEquals(before.dock, after.dock)
        assertEquals(before.folders, after.folders)
        assertEquals(before.widgetPlacements.filter { it.page < 0 }, after.widgetPlacements.filter { it.page < 0 })
        assertEquals(before.widgetStacks, after.widgetStacks)
        assertTrue(after.widgetStacks.keys.all { slot -> after.widgetPlacements.any { it.slot == slot } })
        assertEquals(draft.hidden.map { map.getValue(it) }.toSet(), after.hiddenPages)
        assertEquals(before.pageStyles.mapKeys { map.getValue(it.key) }, after.pageStyles)
        before.focusModes.zip(after.focusModes).forEach { (was, now) ->
            assertEquals(was.pages?.map { map.getValue(it) }?.toSet(), now.pages)
            assertEquals(was.homePage?.let { map.getValue(it) }, now.homePage)
        }
        assertEquals(before.layout.pageCount, after.layout.pageCount)
        // What's saved loads again, unchanged: the loader rejects a whole Home for one bad placement.
        val loaded = reload(after)
        assertEquals(after.layout, loaded.layout)
        assertEquals(after.hiddenPages, loaded.hiddenPages)
        assertEquals(after.pageStyles, loaded.pageStyles)
        assertEquals(after.focusModes, loaded.focusModes)
        assertEquals(after.widgetStacks, loaded.widgetStacks)
    }

    @Test fun `every order of every hidden choice keeps each thing once and moves each page whole`() {
        var cases = 0
        for (pages in 1..5) for (seed in 0 until 3) {
            val state = PagesTestKit.state(Random(pages * 31 + seed), pages)
            val hiddenChoices = subsets((0 until pages).toList()).filter { it.size < pages }
                .let { if (pages == 5) it.shuffled(Random(seed)).take(6) else it }
            for (order in permutations((0 until pages).toList())) for (hidden in hiddenChoices) {
                val draft = PagesDraft(order, hidden, emptySet(), pages)
                assertMovedWhole(state, draft, applied(state.copy(hiddenPages = setOf(0).takeIf { pages > 1 }.orEmpty()), draft))
                cases++
            }
        }
        assertTrue("ran $cases cases", cases > 2_000)
    }

    @Test fun `an empty page comes out from anywhere, the pages after it moving up`() {
        for (pages in 2..5) for (gone in 0 until pages) {
            val state = PagesTestKit.state(Random(gone * 7 + pages), pages, empty = setOf(gone))
            val draft = PagesDraft.of(state).without(gone)!!
            val result = applied(state, draft)
            val after = result.state
            assertEquals(inventory(state.layout), inventory(after.layout))
            assertEquals(pages - 1, after.layout.pageCount)
            for (old in (0 until pages) - gone) assertEquals(page(state.layout, old), page(after.layout, if (old < gone) old else old - 1))
            assertEquals(state.pageStyles.filterKeys { it != gone }.mapKeys { if (it.key < gone) it.key else it.key - 1 }, after.pageStyles)
            assertEquals(after.layout, reload(after).layout)
        }
    }

    @Test fun `removing a hidden page takes its apps and widgets off Home and moves its folders to a shown page`() {
        val folder = PagesTestKit.folderId(1)
        val base = PagesTestKit.simple(listOf("a", "b"), listOf("c", folder, "d"), listOf("e"), hidden = setOf(1))
        val widget = WidgetPlacement(4, 101, 1, 0, 0, 2, 2)
        val state = base.copy(folders = listOf(FolderEntry(folder, "Games", listOf("f", "g"))),
            widgetPlacements = listOf(WidgetPlacement(3, 100, 0, 0, 0, 2, 2), widget), widgetStacks = mapOf(4 to listOf(102)),
            pageStyles = mapOf(1 to PageStyle(.82f), 2 to PageStyle(1.14f)),
            focusModes = DEFAULT_FOCUS_MODES.map { if (it.id == "work") it.copy(pages = setOf(1), homePage = 1) else it })
        val result = applied(state, PagesDraft.of(state).without(1)!!)
        val after = result.state
        assertEquals(listOf("c", "d"), result.offHome)
        assertEquals(listOf(4), result.removedWidgets)
        assertEquals(listOf(folder), result.movedFolders)
        // Everything but the removed page's own apps and widget is still there, once; the folder is whole on page 0.
        assertEquals(inventory(state.layout).filterNot { it == "app c" || it == "app d" || it.startsWith("widget 4 ") }, inventory(after.layout))
        assertEquals(0, homeCellPage(after.homeSlots.indexOf(folder)))
        assertEquals(listOf("f", "g"), after.folders.single().appIds)
        assertEquals(2, after.layout.pageCount)
        assertEquals(PageView.cells(state.layout, 2), PageView.cells(after.layout, 1))
        assertTrue(after.hiddenPages.isEmpty())
        assertEquals(mapOf(1 to PageStyle(1.14f)), after.pageStyles)
        // A Focus that showed only the removed page shows every page again; its opening page is gone with it.
        val work = after.focusModes.first { it.id == "work" }
        assertNull(work.pages); assertNull(work.homePage)
        // The widget's stack is the model's to keep while Undo can bring it back (applyPages); the planner leaves it.
        assertEquals(mapOf(4 to listOf(102)), after.widgetStacks)
        assertEquals(after.layout, reload(after.copy(widgetStacks = emptyMap())).layout)
    }

    @Test fun `removing any hidden or empty pages takes off Home only their apps and widgets, and keeps every folder`() {
        var cases = 0
        for (seed in 0 until 200) {
            val random = Random(seed)
            val pages = random.nextInt(2, 7)
            val empty = (0 until pages).filter { random.nextInt(4) == 0 }.toSet()
            val state = PagesTestKit.state(random, pages, empty = empty)
            var draft = PagesDraft.of(state)
            (0 until pages).shuffled(random).take(random.nextInt(0, pages)).forEach { page -> draft = draft.withHidden(page, true) ?: draft }
            val order = draft.order.shuffled(random)
            draft = draft.copy(order = order)
            order.filter { PagePlanner.removable(state.layout, draft, it) && random.nextBoolean() }.forEach { page -> draft = draft.without(page) ?: draft }
            val result = applied(state, draft)
            val after = result.state
            val leaving = draft.removed.flatMap { page -> PageView.cells(state.layout, page).filterNotNull() }
            assertEquals(leaving.filterNot(::isReservedFolderId).sorted(), result.offHome.sorted())
            assertEquals(state.widgetPlacements.filter { it.page in draft.removed }.map { it.slot }.sorted(), result.removedWidgets.sorted())
            val gone = result.offHome.map { "app $it" } + state.widgetPlacements.filter { it.slot in result.removedWidgets }
                .map { "widget ${it.slot} ${it.id} ${it.spanX}x${it.spanY}" }
            assertEquals(inventory(state.layout).toMutableList().apply { gone.forEach { remove(it) } }, inventory(after.layout))
            // Folders are never removed: each one moved off a removed page lands, whole, on a page Home shows.
            assertEquals(state.folders, after.folders)
            result.movedFolders.forEach { id -> assertTrue(homeCellPage(after.homeSlots.indexOf(id)) !in after.hiddenPages) }
            // Kept pages move whole; at least one shows; everything loads again.
            for (old in draft.order) assertEquals(page(state.layout, old).second, page(after.layout, result.pageMap.getValue(old)).second)
            assertTrue(after.layout.pageCount > after.hiddenPages.size)
            assertEquals(after.layout, reload(after).layout)
            cases++
        }
        assertEquals(200, cases)
    }

    @Test fun `a folder from a removed page opens a new last page when every shown page is full`() {
        val folder = PagesTestKit.folderId(2)
        val full = List(4 * GRID_COLUMNS) { "full$it" }
        val base = PagesTestKit.simple(full, listOf(folder), hidden = setOf(1))
        // The widget rows are taken too, so page 0 has no free cell at all.
        val state = base.copy(folders = listOf(FolderEntry(folder, "Work", listOf("x", "y"))), homeRows = BASE_APP_ROWS,
            widgetPlacements = listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2), WidgetPlacement(1, DATE_WIDGET, 0, 2, 0, 2, 2)))
        val after = applied(state, PagesDraft.of(state).without(1)!!).state
        assertEquals(1, homeCellPage(after.homeSlots.indexOf(folder)))
        assertTrue(1 !in after.hiddenPages)
        assertEquals(inventory(state.layout), inventory(after.layout))
    }

    @Test fun `what can't be done is refused and nothing changes`() {
        val state = PagesTestKit.simple(listOf("a"), listOf("b"), listOf())
        // A shown page with things on it.
        assertEquals(PagePlanner.Refusal.NOT_EMPTY, refusal(state, PagesDraft(listOf(0, 2), emptySet(), setOf(1), 3)))
        // No page left on Home.
        assertEquals(PagePlanner.Refusal.NO_SHOWN_PAGE, refusal(state, PagesDraft(listOf(0, 1, 2), setOf(0, 1, 2), emptySet(), 3)))
        // Home lost a page while the session was open.
        assertEquals(PagePlanner.Refusal.STALE, refusal(state, PagesDraft(listOf(0, 1, 2, 3), emptySet(), emptySet(), 4)))
        assertEquals(PagePlanner.Refusal.STALE, refusal(state, PagesDraft(listOf(0, 1, 1), emptySet(), emptySet(), 3)))
        // The draft itself never hides or removes the last page Home shows.
        val one = PagesDraft(listOf(0, 1, 2), setOf(0, 1))
        assertNull(one.withHidden(2, true))
        assertNull(one.without(2))
        assertTrue(PagePlanner.removable(state.layout, one, 0))
        assertTrue(!PagePlanner.removable(state.layout, PagesDraft.of(state), 1))
        assertTrue(PagePlanner.removable(state.layout, PagesDraft.of(state), 2))
    }

    @Test fun `a page holding an overflow widget from before Folio 0_6 keeps its number`() {
        // Slot 5 on page 1, under the grid: the loader only takes it on page slot / 3.
        val overflow = WidgetPlacement(5, 42, 1, 0, GRID_ROWS, GRID_COLUMNS, 4)
        val state = PagesTestKit.simple(listOf("a"), listOf("b"), listOf("c")).copy(widgetPlacements = listOf(overflow))
        assertEquals(PagePlanner.Refusal.OLD_WIDGET, refusal(state, PagesDraft(listOf(1, 0, 2), emptySet())))
        assertEquals(PagePlanner.Refusal.OLD_WIDGET, refusal(state, PagesDraft(listOf(1, 2), setOf(0), setOf(0), 3)))
        // Pages after it may move, and the page itself may be hidden, or removed while hidden.
        assertMovedWhole(state, PagesDraft(listOf(0, 1, 2), setOf(1)), applied(state, PagesDraft(listOf(0, 1, 2), setOf(1))))
        val removed = applied(state, PagesDraft(listOf(0, 2), setOf(1), setOf(1), 3)).state
        assertTrue(removed.widgetPlacements.isEmpty())
        assertEquals(removed.layout, reload(removed).layout)
    }

    @Test fun `a page Home gained while editing goes last and shows`() {
        val state = PagesTestKit.simple(listOf("a"), listOf("b"), listOf("c"))
        val result = applied(state, PagesDraft(listOf(1, 0), emptySet(), emptySet(), 2))
        assertEquals(mapOf(1 to 0, 0 to 1, 2 to 2), result.pageMap)
        assertEquals(inventory(state.layout), inventory(result.state.layout))
    }

    @Test fun `the draft moves, hides and removes like the page editor`() {
        val draft = PagesDraft(listOf(0, 1, 2, 3), emptySet())
        assertEquals(listOf(2, 0, 1, 3), draft.move(2, 0).order)
        assertEquals(listOf(1, 2, 0, 3), draft.move(0, 2).order)
        assertEquals(draft, draft.move(5, 0))
        val hidden = draft.withHidden(1, true)!!
        assertEquals(setOf(1), hidden.hidden)
        assertEquals(3, hidden.shownCount)
        val removed = hidden.without(1)!!
        assertEquals(listOf(0, 2, 3), removed.order)
        assertEquals(setOf(1), removed.removed)
        assertEquals(4, removed.pages)
        val state = PagesTestKit.simple(listOf("a"), listOf(), listOf("c"), listOf(), hidden = setOf(3))
        // "Remove this empty page" while a page is hidden: the last page Home shows (page 2 has an app, so nothing).
        assertNull(PagesDraft.removingLastShown(state))
        val trailing = PagesTestKit.simple(listOf("a"), listOf(), listOf("c"), hidden = setOf(2))
        assertEquals(setOf(1), PagesDraft.removingLastShown(trailing)!!.removed)
    }

    @Test fun `undo after any page session gives back the same Home, page settings included`() {
        for (seed in 0 until 150) {
            val random = Random(seed)
            val pages = random.nextInt(1, 7)
            val before = PagesTestKit.state(random, pages, empty = (0 until pages).filter { random.nextInt(4) == 0 }.toSet())
                .let { it.copy(hiddenPages = (0 until pages).filter { random.nextInt(4) == 0 }.toSet().takeIf { h -> h.size < pages }.orEmpty()) }
            var draft = PagesDraft.of(before)
            repeat(random.nextInt(0, 6)) {
                val page = random.nextInt(pages)
                draft = when (random.nextInt(3)) {
                    0 -> draft.move(random.nextInt(draft.order.size), random.nextInt(draft.order.size))
                    1 -> draft.withHidden(page, page !in draft.hidden) ?: draft
                    else -> if (PagePlanner.removable(before.layout, draft, page)) draft.without(page) ?: draft else draft
                }
            }
            val after = applied(before, draft).state
            val undo = PageUndo(before.layout, after.layout, PageSettings.of(before), PageSettings.of(after))
            // What Folio's own Undo does (LauncherModel.undoEdit): the layout's parts go back; then the page settings.
            val layoutBack = after.copy(homeSlots = before.homeSlots, leadingSlots = before.leadingSlots, dock = before.dock,
                widgetPlacements = before.widgetPlacements, folders = before.folders, widgetRestores = before.widgetRestores)
            val undone = PageUndo.restore(undo, before.layout, after.layout, layoutBack)
            assertEquals(before.layout, undone.layout)
            assertEquals(PageSettings.of(before), PageSettings.of(undone))
        }
    }

    @Test fun `undo puts page settings back, but not over a change made since`() {
        val before = PagesTestKit.state(Random(3), 3)
        val result = applied(before, PagesDraft(listOf(2, 0, 1), setOf(1)))
        val after = result.state
        val undo = PageUndo(before.layout, after.layout, PageSettings.of(before), PageSettings.of(after))
        // Undo put the layout back; the page settings follow.
        val undone = PageUndo.restore(undo, before.layout, after.layout, after.copy(homeSlots = before.homeSlots, widgetPlacements = before.widgetPlacements))
        assertEquals(before.hiddenPages, undone.hiddenPages)
        assertEquals(before.pageStyles, undone.pageStyles)
        assertEquals(before.focusModes, undone.focusModes)
        assertEquals(before.minPages, undone.minPages)
        // A Focus changed after the edit keeps that change; a mismatched Undo changes nothing.
        val changed = after.copy(focusModes = after.focusModes.map { if (it.id == "work") it.copy(pages = setOf(0)) else it })
        assertEquals(setOf(0), PageUndo.restore(undo, before.layout, after.layout, changed).focusModes.first { it.id == "work" }.pages)
        assertEquals(after, PageUndo.restore(undo, after.layout, before.layout, after))
        assertEquals(after, PageUndo.restore(null, before.layout, after.layout, after))
    }
}
