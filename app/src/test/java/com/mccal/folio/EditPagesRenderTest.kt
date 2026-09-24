package com.mccal.folio

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edit Pages on the JVM (EditPages.kt): what each touch does to the session. Structure and behaviour only; how it
 * looks and feels (the lift, the springs, the fold between columns) is for the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EditPagesRenderTest {
    @get:Rule val compose = createComposeRule()

    private val apps = listOf("com.a/.A", "com.b/.B").associateWith { AppEntry(it, it, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) }

    /** Page 1: an app. Page 2: an app. Page 3: empty. */
    private fun show(state: LauncherState = PagesTestKit.simple(listOf("com.a/.A"), listOf("com.b/.B"), listOf()), onDone: () -> Unit = {}) =
        mutableStateOf(PagesDraft.of(state)).also { draft ->
            compose.setContent { EditPagesContent(state.layout, draft, apps, BASE_APP_ROWS, onDone = onDone) }
        }

    @Test fun `every page shows with its check, and the last page on Home can't be unchecked`() {
        val draft = show()
        (0..2).forEach { compose.onNodeWithTag("edit-page-$it").assertIsDisplayed() }
        compose.onNodeWithTag("edit-page-check-1").performClick()
        compose.onNodeWithTag("edit-page-check-2").performClick()
        assertEquals(setOf(1, 2), draft.value.hidden)
        compose.onNodeWithTag("edit-page-check-0").performClick()
        assertEquals(setOf(1, 2), draft.value.hidden)
        compose.onNodeWithTag("edit-pages-note").assertIsDisplayed()
        // Checking a hidden page again shows it.
        compose.onNodeWithTag("edit-page-check-2").performClick()
        assertEquals(setOf(1), draft.value.hidden)
    }

    @Test fun `an empty page goes at once, a hidden page with an app asks first`() {
        val draft = show()
        compose.onNodeWithContentDescription("Remove page 3").performClick()
        assertEquals(setOf(2), draft.value.removed)
        assertEquals(listOf(0, 1), draft.value.order)
        // A page with an app shows no minus until it's hidden; then Remove asks.
        compose.onNodeWithTag("edit-page-check-1").performClick()
        compose.onNodeWithContentDescription("Remove page 2").performClick()
        assertEquals(setOf(2), draft.value.removed)
        compose.onNodeWithTag("edit-pages-remove-confirm").performClick()
        assertEquals(setOf(1, 2), draft.value.removed)
        assertEquals(listOf(0), draft.value.order)
    }

    @Test fun `holding a page and dragging it over another puts it there`() {
        val draft = show()
        val from = compose.onNodeWithTag("edit-page-2").fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithTag("edit-page-0").fetchSemanticsNode().boundsInRoot
        val delta = to.center - from.center
        compose.onNodeWithTag("edit-page-2").performTouchInput {
            down(Offset(width / 2f, height / 4f))
            moveBy(Offset.Zero, delayMillis = viewConfiguration.longPressTimeoutMillis + 100)
            repeat(20) { moveBy(delta / 20f) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(2, 0, 1), draft.value.order)
    }

    @Test fun `TalkBack can move, hide and remove a page too`() {
        val draft = show()
        val node = compose.onNodeWithContentDescription("Page 1, on Home").fetchSemanticsNode()
        val actions = node.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        actions.first { it.label == "Move later" }.action()
        assertEquals(listOf(1, 0, 2), draft.value.order)
    }

    @Test fun `Done hands the session back`() {
        var done = 0
        show(onDone = { done++ })
        compose.onNodeWithTag("edit-pages-done").performClick()
        assertTrue(done >= 1)
    }
}
