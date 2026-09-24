package com.mccal.folio.market

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedTextTest {
    private fun read(value: Any?, max: Int = 400): Pair<LocalizedText?, List<String>> {
        val problems = mutableListOf<String>()
        return LocalizedText.read(value, max) { problems += it } to problems
    }

    private val icons = read(JSONObject("""{"en":"Sunset Icons","es":"Iconos Atardecer","pt-BR":"Ícones Pôr do Sol","zh-Hant":"日落圖示"}""")).first!!

    @Test fun `picks the exact tag, then the language, then a regional variant, then English`() {
        assertEquals("Ícones Pôr do Sol", icons.resolve(listOf("pt-BR")))
        assertEquals("Ícones Pôr do Sol", icons.resolve(listOf("pt-br")))
        assertEquals("Ícones Pôr do Sol", icons.resolve(listOf("pt-PT")))
        assertEquals("Iconos Atardecer", icons.resolve(listOf("es-MX")))
        assertEquals("日落圖示", icons.resolve(listOf("zh-Hant-TW", "en")))
        assertEquals("Sunset Icons", icons.resolve(listOf("de-DE", "fr")))
        assertEquals("Sunset Icons", icons.resolve(emptyList()))
        assertEquals("Iconos Atardecer", icons.resolve(listOf("", "de", "es")))
    }

    @Test fun `a plain string is English`() {
        val (text, problems) = read("Cabinet")
        assertTrue(problems.isEmpty())
        assertEquals("Cabinet", text!!.resolve(listOf("fr")))
        assertEquals(setOf("en"), text.languages)
    }

    @Test fun `length counts characters, not UTF-16 units`() {
        assertNotNull(read("😀".repeat(40), 40).first)
        assertNull(read("😀".repeat(41), 40).first)
        assertNull(read("").first)
    }

    @Test fun `rejects missing English, bad tags, wrong types and case clashes`() {
        for (json in listOf("""{"es":"Hola"}""", """{"en":"Hi","EN":"Hi"}""", """{"en":"Hi","english":"Hi"}""", """{"en":1}""",
                "{\"en\":\"Hi\",\"en-us\":\"a\",\"en-US\":\"b\"}", """{"en":""}""", """{}""")) {
            val (text, problems) = read(JSONObject(json))
            assertNull(json, text)
            assertTrue(json, problems.isNotEmpty())
        }
        assertNull(read(42).first)
        assertNull(read(null).first)
        assertNull(read(JSONObject.NULL).first)
    }

    @Test fun `caps the number of languages`() {
        val many = JSONObject().put("en", "x")
        ('a'..'z').flatMap { a -> ('a'..'c').map { b -> "$a$b" } }.take(LocalizedText.MAX_LANGUAGES).forEach { many.put(it, "x") }
        assertNull(read(many).first)
    }
}
