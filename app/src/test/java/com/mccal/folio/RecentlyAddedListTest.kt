package com.mccal.folio

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recently Added arrives a moment after the App Library's list is drawn: finding it asks Android when each app was
 * installed, off the main thread. This renders the real list on the JVM (Robolectric, no phone) and checks it then
 * shows at the top of the list rather than above the screen, and that the letter strip still finds its headings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class RecentlyAddedListTest {
    @get:Rule val compose = createComposeRule()

    private val icon = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private fun app(label: String) = AppEntry("org.example.${label.lowercase()}/.Main", label, icon)
    private val apps = listOf("Alpha", "Atlas", "Bank", "Camera", "Chess", "Drive", "Email", "Files", "Maps", "Notes", "Photos",
        "Radio", "Shop", "Tasks", "Video", "Wallet", "Weather", "Zoom").map(::app)
    private val sections = LibraryIndex.sections(apps) { it.label }

    /** AppLibrary's list while browsing: its Downloading row (no height while nothing downloads), then the sections. */
    private fun showList(state: LazyListState, recent: () -> List<AppEntry>?) = compose.setContent {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            item("downloading") { }
            libraryListSections(sections, 1, glass = false, dark = false, drag = null, page = null,
                onLaunchFrom = { _, _ -> }, onActions = {}, recentlyAdded = recent())
        }
    }

    @Test fun `arriving late, Recently Added shows at the top of the list, above A`() {
        val state = LazyListState()
        var recent by mutableStateOf<List<AppEntry>?>(emptyList())
        showList(state) { recent }
        compose.onNodeWithText("Recently Added").assertDoesNotExist()
        compose.runOnIdle { recent = listOf(apps[9], apps[3]) } // the lookup comes back: Notes, then Camera
        compose.onNodeWithText("Recently Added").assertIsDisplayed()
        compose.onNodeWithTag("library-recent-${apps[9].id}").assertIsDisplayed()
        compose.onNodeWithTag("library-recent-${apps[3].id}").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("list-recent-anchor", state.layoutInfo.visibleItemsInfo.first().key)
            // The letter strip counts back from the end of the list; Recently Added above the letters doesn't move them.
            val headings = LibraryIndex.headingIndices(sections.map { it.first to it.second.size }, 1, state.layoutInfo.totalItemsCount)
            val a = state.layoutInfo.visibleItemsInfo.first { it.key == "list-heading-A" }
            assertEquals(headings.getValue("A"), a.index)
        }
        // Alpha's own row in the A–Z list keeps its tag: an app shown twice has one "library-app-" row.
        compose.onNodeWithTag("library-app-${apps[0].id}").assertIsDisplayed()
    }

    @Test fun `searching or on the tiles there's no section and no anchor`() {
        val state = LazyListState()
        showList(state) { null }
        compose.onNodeWithText("Recently Added").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(state.layoutInfo.visibleItemsInfo.none { (it.key as? String)?.startsWith("list-recent") == true })
            assertEquals("list-heading-A", state.layoutInfo.visibleItemsInfo.first().key)
        }
    }

    /** What the anchor is for: a lazy list holds on to its first visible row when rows arrive above it. */
    @Test fun `without an anchor, a row arriving above the first one lands off screen`() {
        val state = LazyListState()
        var late by mutableStateOf(false)
        compose.setContent {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                item("empty") { }
                if (late) item("late") { Text("late", Modifier.fillMaxWidth().height(60.dp)) }
                items(40, key = { it }) { Text("row $it", Modifier.fillMaxWidth().height(60.dp)) }
            }
        }
        compose.runOnIdle { late = true }
        compose.runOnIdle {
            assertEquals(0, state.layoutInfo.visibleItemsInfo.first().key)
            assertTrue(state.layoutInfo.visibleItemsInfo.none { it.key == "late" })
        }
    }
}
