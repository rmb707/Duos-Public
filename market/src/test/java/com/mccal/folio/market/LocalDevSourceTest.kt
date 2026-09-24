package com.mccal.folio.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The local source someone serves from their own desk while building a package. It's unsigned, so it only exists in
 * Folio Dev, only on this phone, and everything it returns is labelled unsigned.
 */
class LocalDevSourceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").exists() }
    private val index = File(root, "docs/sdk/source/index.json").readText()
    private val local = "http://localhost:8787/"

    private class Host(val files: Map<String, ByteArray>) : HttpClient {
        val requests = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int, etag: String?): HttpResult {
            requests += url
            val bytes = files[url] ?: return HttpResult.Failed("not found", 404)
            return HttpResult.Body(bytes, null)
        }
    }

    private fun client(allow: Boolean, host: Host) = RepoClient(host, SourceStore(MemoryStore()), allowLocalDev = allow) { 1_789_000_000L }

    @Test fun `what it read is still there afterwards`() {
        // The gap that let a whole feature look like it worked: the refresh returned a snapshot and cached only
        // the index, and `cachedSnapshot` wants an entry too. So the source said "updated", showed no package
        // count, and contributed nothing to Packages - and every test until now stopped at the return value.
        val host = Host(mapOf(local + "index.json" to index.toByteArray()))
        val client = client(allow = true, host)
        val fresh = (client.refreshLocalDev(local) as RefreshResult.Updated).snapshot

        val kept = client.cachedSnapshot(local)
        assertTrue("the source should still be there after the refresh", kept != null)
        assertEquals(fresh.index.packages.size, kept!!.index.packages.size)
        assertEquals(fresh.index.name.english, kept.index.name.english)
        assertEquals(fresh.entry.index.sha256, kept.entry.index.sha256)
    }

    @Test fun `Folio Dev reads an unsigned index from this phone and says it's unsigned`() {
        val host = Host(mapOf(local + "index.json" to index.toByteArray()))
        val result = client(allow = true, host).refreshLocalDev(local)
        assertTrue("$result", result is RefreshResult.Updated)
        val snapshot = (result as RefreshResult.Updated).snapshot
        assertEquals("Folio", snapshot.index.name.english)
        assertEquals(listOf(RepoClient.UNSIGNED_NOTE), snapshot.notes)
        // Only the list is fetched: there's no signature or key to ask for.
        assertEquals(listOf("index.json"), host.requests.map { it.removePrefix(local) })
    }

    @Test fun `the release build has no way to read an unsigned source`() {
        val host = Host(mapOf(local + "index.json" to index.toByteArray()))
        val result = client(allow = false, host).refreshLocalDev(local)
        assertEquals(RefreshResult.Reason.INSECURE, (result as RefreshResult.Failed).reason)
        assertTrue("nothing was even fetched", host.requests.isEmpty())
    }

    @Test fun `only this phone counts as local`() {
        val host = Host(emptyMap())
        for (url in listOf("https://maya.example/folio/", "http://192.168.1.10:8787/", "http://example.com/")) {
            val result = client(allow = true, host).refreshLocalDev(url)
            assertEquals(url, RefreshResult.Reason.INSECURE, (result as RefreshResult.Failed).reason)
        }
        assertTrue(host.requests.isEmpty())
    }

    @Test fun `a broken local index is refused rather than half read`() {
        val host = Host(mapOf(local + "index.json" to """{"format":1,"name":"Local"}""".toByteArray()))
        val result = client(allow = true, host).refreshLocalDev(local)
        assertEquals(RefreshResult.Reason.PARSE, (result as RefreshResult.Failed).reason)
    }
}
