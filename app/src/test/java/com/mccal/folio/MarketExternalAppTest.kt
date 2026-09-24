package com.mccal.folio

import androidx.test.core.app.ApplicationProvider
import com.mccal.folio.market.ExternalSource
import com.mccal.folio.market.PackageManifest
import com.mccal.folio.market.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An app Folio points at rather than installs.
 *
 * The line that matters is the one Folio doesn't cross: it can install an APK - Software Update does - and it
 * never does so for a package a source named. These check the pointing, and that a listing can't smuggle a
 * download past it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarketExternalAppTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun manifest(via: String): PackageManifest {
        val json = """
            {
              "format": 1, "id": "com.mccal.keyd", "name": "Keyd", "version": "0.1.0",
              "author": { "name": "Folio" }, "minFolio": "0.7.0", "section": "tweaks",
              "kind": ["externalApp"], "permissions": [], "via": [$via]
            }
        """.trimIndent()
        return when (val r = PackageManifest.parse(json)) {
            is ParseResult.Ok -> r.value
            is ParseResult.Invalid -> error("manifest fixture is wrong: " + r.errors)
            is ParseResult.Unsupported -> error("manifest fixture needs a newer Folio: " + r.needs)
        }
    }

    @Test fun `each store gets the address it actually uses`() {
        assertEquals(
            "market://details?id=com.mccal.keyd",
            MarketExternalApp.uriFor(ExternalSource(ExternalSource.Store.PLAY_STORE, "com.mccal.keyd", null)),
        )
        assertEquals(
            "https://f-droid.org/packages/com.mccal.keyd/",
            MarketExternalApp.uriFor(ExternalSource(ExternalSource.Store.FDROID, "com.mccal.keyd", null)),
        )
        assertEquals(
            "obtainium://add/https://github.com/McCal-Codes/folio-keyd",
            MarketExternalApp.uriFor(ExternalSource(ExternalSource.Store.OBTAINIUM, null, "https://github.com/McCal-Codes/folio-keyd")),
        )
    }

    @Test fun `a listing with nothing to point at points nowhere`() {
        // The schema requires an id for the two stores and a repo for Obtainium, so this shouldn't arrive - and if
        // it does, an option that leads nowhere is better than one that opens something unexpected.
        assertNull(MarketExternalApp.uriFor(ExternalSource(ExternalSource.Store.PLAY_STORE, null, null)))
        assertNull(MarketExternalApp.uriFor(ExternalSource(ExternalSource.Store.OBTAINIUM, "com.mccal.keyd", null)))
    }

    @Test fun `an external package is recognised, and an ordinary one isn't`() {
        assertTrue(MarketExternalApp.isExternal(manifest("""{ "store": "obtainium", "repoUrl": "https://github.com/McCal-Codes/folio-keyd" }""")))
        val tweak = (PackageManifest.parse(
            """{"format":1,"id":"dev.a.b","name":"B","version":"1.0.0","author":{"name":"A"},"minFolio":"0.7.0","section":"tweaks","kind":["tweakBundle"],"permissions":["tweaks"]}""",
        ) as ParseResult.Ok).value
        assertTrue(!MarketExternalApp.isExternal(tweak))
        assertTrue(!MarketExternalApp.isExternal(null))
    }

    @Test fun `an app nobody has installed is not on the phone`() {
        val keys = manifest("""{ "store": "playStore", "id": "com.mccal.keyd.nothere" }""")
        assertNull(MarketExternalApp.installedAppId(context, keys))
        // A listing with no app id at all - Obtainium only - has nothing to look for, and says so rather than
        // guessing from the package id, which is Folio's name for it and not Android's.
        val obtainium = manifest("""{ "store": "obtainium", "repoUrl": "https://github.com/McCal-Codes/folio-keyd" }""")
        assertNull(MarketExternalApp.installedAppId(context, obtainium))
    }

    @Test fun `an Obtainium listing can still say which app it installs`() {
        // The trap this guards: `id` is only *required* for Play and F-Droid, so an Obtainium-only listing that
        // leaves it out parses and publishes happily - and Folio can then never tell the app is installed, so
        // the button says Get for ever. Keyd is Obtainium-only, so this is Keyd's case exactly.
        val both = manifest(
            """{ "store": "obtainium", "repoUrl": "https://github.com/McCal-Codes/folio-keyd", "id": "${context.packageName}" }""",
        )
        assertEquals(
            "obtainium://add/https://github.com/McCal-Codes/folio-keyd",
            MarketExternalApp.uriFor(both.via.single()),
        )
        assertEquals("and Folio can see it", context.packageName, MarketExternalApp.installedAppId(context, both))
    }

    @Test fun `the keyboard list is asked once per generation, not once per row`() {
        val keys = manifest("""{ "store": "playStore", "id": "com.mccal.keyd" }""")
        // Same generation: the shared part of the lookup is worked out once, however many rows ask.
        repeat(5) { MarketExternalApp.installedAppId(context, keys, generation = 1) }
        // And a new generation - Folio came back to the front - asks again, because the answer can have changed.
        assertNull(MarketExternalApp.installedAppId(context, keys, generation = 2))
    }

    @Test fun `an app with no way in goes to its own settings page, not the keyboard ones`() {
        fun lastStarted() = org.robolectric.Shadows.shadowOf(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).nextStartedActivity

        // An app with a launcher icon opens the app.
        assertTrue(MarketExternalApp.open(context, context.packageName))
        assertEquals(android.content.Intent.ACTION_MAIN, lastStarted().action)

        // One with no launcher activity and no input method used to land in Android's keyboard settings, for no
        // reason at all. Its own page in settings always exists, so that is where it goes.
        val elsewhere = "com.example.no.launcher"
        assertTrue(!MarketExternalApp.isKeyboard(context, elsewhere))
        assertTrue(MarketExternalApp.open(context, elsewhere))
        val started = lastStarted()
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:$elsewhere", started.data.toString())
    }

    @Test fun `Folio finds an app that is installed`() {
        // Robolectric's own package stands in for one that is really there.
        val mine = manifest("""{ "store": "playStore", "id": "${context.packageName}" }""")
        assertEquals(context.packageName, MarketExternalApp.installedAppId(context, mine))
    }

    @Test fun `no number is ever handed out twice`() {
        // The keyboard list is cached against this number, and the cache outlives the screen that asks. A counter
        // that started again at 0 each time the store opened meant the second visit asked the same question as
        // the first and got the first visit's answer - so a keyboard installed in between stayed "not yet" until
        // Folio was killed. These are minted here now, and they only go up.
        val seen = (1..50).map { MarketExternalApp.appsChanged() }
        assertEquals(seen.size, seen.toSet().size)
        assertEquals(seen.sorted(), seen)
    }
}
