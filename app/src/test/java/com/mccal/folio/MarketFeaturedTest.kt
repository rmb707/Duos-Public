package com.mccal.folio

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.mccal.folio.market.DebVersion
import com.mccal.folio.market.FeaturedItem
import com.mccal.folio.market.IndexPackage
import com.mccal.folio.market.LocalizedText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Featured carousel moves on by itself until a finger lands on it.
 *
 * It used to stop after one banner, because it watched `isScrollInProgress` to notice a touch — and a carousel
 * advancing itself is a scroll in progress, so its own first move looked like the user's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MarketFeaturedTest {
    @get:Rule val compose = createComposeRule()

    private fun entry(id: String, name: String) = IndexPackage(
        id = id,
        version = requireNotNull(DebVersion.parse("1.0.0")),
        url = null, sha256 = null, size = null, provenance = null,
        manifest = null,
    ).let { it to FeaturedItem(packageId = id, label = LocalizedText.of(name), image = null) }

    @Test fun `it moves on by itself, more than once`() {
        val one = entry("dev.one.a", "One")
        val two = entry("dev.two.b", "Two")
        val three = entry("dev.three.c", "Three")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column {
                MarketFeatured(
                    featured = listOf(one.second, two.second, three.second),
                    packages = listOf(one.first, two.first, three.first),
                    calm = false,
                    onOpen = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(50)
        // Each banner shows the package's id, because these have no manifest to take a name from.
        compose.onNodeWithText("dev.one.a").assertIsDisplayed()

        fun showing(id: String): Boolean =
            compose.onAllNodesWithTextSafe(id).any()

        // Two advances, not one: the bug let the first happen and then stopped.
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertTrue("the second banner should have come round", showing("dev.two.b"))
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertTrue("and the third", showing("dev.three.c"))
    }
}

/** Reads the tree without failing when nothing matches, so a test can ask rather than assert. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafe(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes()
