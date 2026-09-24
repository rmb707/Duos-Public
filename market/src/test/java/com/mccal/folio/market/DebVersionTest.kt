package com.mccal.folio.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebVersionTest {
    private fun v(text: String) = requireNotNull(DebVersion.parse(text)) { text }

    private fun assertAscending(vararg texts: String) {
        texts.toList().zipWithNext().forEach { (a, b) ->
            assertTrue("$a < $b", v(a) < v(b))
            assertTrue("$b > $a", v(b) > v(a))
        }
    }

    @Test fun `tilde sorts before the release, letters before symbols (Debian policy order)`() {
        assertAscending("1.0~~", "1.0~~a", "1.0~", "1.0", "1.0a", "1.0+", "1.0.1")
        assertAscending("1.0~beta1", "1.0~beta2", "1.0~rc1", "1.0")
    }

    @Test fun `numbers compare as numbers, at any length`() {
        assertAscending("1.2", "1.10", "1.100")
        assertAscending("9999999999999999999", "99999999999999999999", "100000000000000000000")
    }

    @Test fun `epoch wins, then upstream, then revision`() {
        assertAscending("9.9-9", "1:0.1")
        assertAscending("1.0-1", "1.0-2", "1.0-10", "1.0.1-1")
        assertAscending("1.0-1~bpo1", "1.0-1", "1.0-1+b1")
    }

    @Test fun `dpkg-equal versions are equal with the same hash`() {
        for ((a, b) in listOf("1.0" to "1.00", "1.0" to "0:1.0-0", "1.0" to "1.", "01" to "1", "1.0-0" to "1.0-00", "1a0" to "1a")) {
            assertEquals("$a = $b", 0, v(a).compareTo(v(b)))
            assertEquals(v(a), v(b))
            assertEquals("$a hash", v(a).hashCode(), v(b).hashCode())
        }
    }

    @Test fun `a zero run only disappears at the end`() {
        assertNotEquals(v("1a0b"), v("1ab"))
        assertNotEquals(v("1.2"), v("12."))
        assertTrue(v("1a0b") < v("1ab"))
    }

    @Test fun `rejects anything outside the format`() {
        val bad = listOf("", "a1", "1.0-", "1:", ":1", "-1", "1 0", "1.0\n", "1_0", "1:a", "1.0-a-", "x".repeat(10), "1" + "0".repeat(64))
        for (text in bad) assertNull(text, DebVersion.parse(text))
    }

    @Test fun `keeps the author's text`() {
        assertEquals("2:1.0~beta1-3", v("2:1.0~beta1-3").toString())
    }

    @Test fun `relations match with dpkg operators`() {
        val rel = requireNotNull(PackageRelation.parse("dev.maya.icons (>= 1.2)"))
        assertEquals("dev.maya.icons", rel.id)
        assertTrue(rel.matches(v("1.2")))
        assertTrue(rel.matches(v("1.10")))
        assertTrue(!rel.matches(v("1.2~rc1")))
        assertTrue(requireNotNull(PackageRelation.parse("a.b (<< 2)")).matches(v("1.9")))
        assertTrue(requireNotNull(PackageRelation.parse("a.b (= 1.0)")).matches(v("1.00")))
        assertTrue(requireNotNull(PackageRelation.parse("a.b")).matches(v("0")))
        for (bad in listOf("a.b (> 1.2)", "a.b (>= )", "a.b(>= 1)", "A.b", "a.b (>= 1.2) ", "a.b (>= 1.2)\n", "a.b (>= x)")) {
            assertNull(bad, PackageRelation.parse(bad))
        }
    }

    @Test fun `Folio versions compare numerically and read app version names`() {
        assertTrue(FolioVersion.parse("0.10.0")!! > FolioVersion.parse("0.9.9")!!)
        assertEquals(FolioVersion(0, 7, 0), FolioVersion.fromAppVersion("0.7.0-beta2"))
        assertEquals(FolioVersion(0, 7, 0), FolioVersion.fromAppVersion("0.7.0.dev"))
        assertNull(FolioVersion.fromAppVersion("0.7"))
        assertNull(FolioVersion.fromAppVersion("0.7.0123456789"))
        for (bad in listOf("0.7", "0.7.0.1", "v0.7.0", "0.7.0\n", "1234567890.0.0")) assertNull(bad, FolioVersion.parse(bad))
    }
}
