package com.mccal.folio.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place plain HTTP is allowed, and the wall around it.
 *
 * This is the check a fake `HttpClient` can never make: every other test hands `RepoClient` a stand-in, so the
 * real client's https rule was never exercised, and Local Dev - documented in the SDK, gated to Folio Dev, and
 * tested with a fake - could not fetch anything at all on a real phone. The config allowed the cleartext; the
 * client refused it two layers earlier.
 */
class HttpClientLocalhostTest {
    private fun client(allow: Boolean) = UrlHttpClient(allowLocalhost = allow, open = { url ->
        throw AssertionError("should not have opened a connection to $url")
    })

    @Test fun `the release build refuses plain http, localhost included`() {
        val http = client(allow = false)
        for (url in listOf("http://localhost:8787/index.json", "http://127.0.0.1:8787/index.json", "http://maya.example/index.json")) {
            val result = http.get(url, 4096)
            assertTrue("$url should be refused", result is HttpResult.Failed)
            assertEquals("sources must use https", (result as HttpResult.Failed).message)
        }
    }

    @Test fun `Folio Dev reaches this phone, and nowhere else, in the clear`() {
        val http = client(allow = true)
        // Refused before any socket: the fake `open` would throw if it got that far.
        for (url in listOf("http://maya.example/index.json", "http://127.0.0.2/index.json", "http://localhost.maya.example/x")) {
            assertTrue("$url should still be refused", http.get(url, 4096) is HttpResult.Failed)
        }
        // And these get as far as opening a connection, which is all this can prove without a server.
        for (url in listOf("http://localhost:8787/index.json", "http://127.0.0.1:8787/index.json")) {
            val reached = runCatching { http.get(url, 4096) }.exceptionOrNull()
            assertTrue("$url should have been allowed through to a connection", reached is AssertionError)
        }
    }
}
