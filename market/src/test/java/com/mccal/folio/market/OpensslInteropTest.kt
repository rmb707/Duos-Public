package com.mccal.folio.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A source signed by `openssl`, not by Java.
 *
 * The publishing side signs with openssl (`tools/build.py` in folio-packages, and the GitHub Action that runs it),
 * while every other test here signs with `java.security`. They agree today - ECDSA P-256, SHA-256, a DER signature in
 * base64 - and this fixture is here so they keep agreeing: if a change to [SourceKey] broke it, every source anyone
 * publishes would stop working at once.
 *
 * The fixture was made with a throwaway key; nothing signs anything real with it.
 */
class OpensslInteropTest {
    private val dir = File(javaClass.classLoader.getResource("openssl/entry.json")!!.toURI()).parentFile

    @Test fun `a package signed by the publishing tool verifies the way the phone does`() {
        val entry = org.json.JSONObject(File(dir, "signed-package.json").readText())
        val block = entry.getJSONObject("signedBy")
        val signature = AuthorSignature(block.getString("key"), block.getString("signature"))
        val id = entry.getString("id")
        val version = requireNotNull(DebVersion.parse(entry.getString("version")))
        val sha = entry.getString("sha256")

        assertTrue("tools/build.py's author signature has to verify here", signature.verifies(id, version, sha))
        // And it's bound to these bytes and this version, not just to the key.
        assertTrue(!signature.verifies(id, version, "b".repeat(64)))
        assertTrue(!signature.verifies("dev.someone.else", version, sha))
        assertTrue(!signature.verifies(id, requireNotNull(DebVersion.parse("9.9.9")), sha))
    }

    @Test fun `a foliopkg the tool signed carries proof of who made it`() {
        val bytes = File(dir, "signed-file.foliopkg").readBytes()
        val opened = PackageArchive.read(bytes)
        assertTrue("$opened", opened is PackageArchive.Result.Ok)
        val files = (opened as PackageArchive.Result.Ok).files
        // Read straight from the file: this is about the signature, and the template package names a capability
        // this build hasn't got, which is a different check entirely.
        val manifest = org.json.JSONObject(files["manifest.json"]!!.decodeToString())
        val id = manifest.getString("id")
        val version = requireNotNull(DebVersion.parse(manifest.getString("version")))

        val authors = AuthorTrust(MemoryStore())
        val first = authors.checkFiles(id, version, files)
        assertTrue("a file signed by tools/build.py has to verify here: $first", first is AuthorTrust.Result.FirstTime)

        // The same package with one file changed is not that package any more.
        val edited = files + ("depiction.json" to "{}".toByteArray())
        assertEquals(AuthorTrust.Result.Broken, authors.checkFiles(id, version, edited))
    }

    @Test fun `an entry signed by openssl verifies, and an edited one doesn't`() {
        val key = requireNotNull(SourceKey.parse(File(dir, "key.pub").readText().trim()))
        val entry = File(dir, "entry.json").readBytes()
        val signature = File(dir, "entry.json.sig").readText().trim()

        assertTrue("openssl's signature has to verify the way Folio verifies", key.verifies(entry, signature))
        // The key id in the file is the one Folio works out from the key itself.
        assertEquals(key.keyId, (SourceEntry.parse(entry.decodeToString()) as ParseResult.Ok).value.keyId)
        // One byte different is a different file.
        assertFalse(key.verifies(entry.decodeToString().replace("\"format\": 1", "\"format\":  1").toByteArray(), signature))
    }
}
