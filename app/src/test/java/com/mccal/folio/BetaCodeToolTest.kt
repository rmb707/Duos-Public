package com.mccal.folio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A code from the tool that really mints them, checked by the code that really reads them.
 *
 * [BetaCodesTest] mints in Kotlin, which proves the reader is self-consistent and nothing else: the alphabet, the
 * byte order, the epoch and the shape of the signature all live twice, once here and once in
 * `scripts/beta-code.py`. A change to either that the other didn't follow would leave every code McCal has handed
 * out unreadable, and no test would say so. So this one runs the script.
 *
 * The same idea as `OpensslInteropTest` in `:market`, and for the same reason: the signing side isn't Java.
 */
class BetaCodeToolTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "CHANGELOG.md").exists() }
    private val script = File(root, "scripts/beta-code.py")

    private fun run(vararg command: String, dir: File): Pair<Int, String> {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(90, TimeUnit.SECONDS)
        return process.exitValue() to output
    }

    @Test fun `a code the minting script makes is one this build accepts`() {
        assumeTrue("the minting script isn't in this checkout", script.isFile)
        val dir = File.createTempFile("beta-code", "").let { it.delete(); it.mkdirs(); it }
        try {
            val key = File(dir, "key.pem")
            val (made, keyOutput) = run("python3", script.absolutePath, "newkey", "--key", key.path, dir = dir)
            assumeTrue("python3 and openssl are needed to mint a code", made == 0)

            // The script prints the line that goes into BetaKeys.SUPPORTER; the app reads the key out of it.
            val publicKey = Regex("""SUPPORTER = "([^"]+)"""").find(keyOutput)?.groupValues?.get(1)
            assertTrue("newkey should print the public key to paste in: $keyOutput", publicKey != null)

            val (minted, codes) = run(
                "python3", script.absolutePath, "mint", "--key", key.path,
                "--scopes", "beta,keys", "--tier", "3", "--expires", "2027-03-01", "--count", "2", dir = dir,
            )
            assertEquals(codes, 0, minted)
            val lines = codes.trim().lines().filter { it.isNotBlank() }
            assertEquals("two codes were asked for", 2, lines.size)

            val keys = listOf(publicKey!!)
            for (line in lines) {
                val result = BetaCodes.verify(line, keys, java.time.LocalDate.of(2026, 9, 19))
                val code = (result as? BetaCodes.Result.Valid)?.code
                assertTrue("the script's code should verify, said $result", code != null)
                assertEquals(setOf(BetaCodes.SCOPE_BETA, BetaCodes.SCOPE_KEYS), code!!.scopes)
                assertEquals(3, code.tier)
                assertEquals(java.time.LocalDate.of(2027, 3, 1), code.expires)
            }
            // Two codes are two codes: the serial is what a withdrawal names, so it can't be the same every time.
            assertTrue("codes should differ", lines[0] != lines[1])

            // And the day after it expires, the same code is refused rather than quietly still working.
            val late = BetaCodes.verify(lines[0], keys, java.time.LocalDate.of(2027, 3, 2))
            assertTrue("$late", late is BetaCodes.Result.Expired)
        } finally {
            dir.deleteRecursively()
        }
    }
}
