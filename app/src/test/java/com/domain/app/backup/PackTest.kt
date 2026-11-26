package com.domain.app.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PackTest {
    private fun text(date: Long, body: String, address: String = "+61400000000") = buildJsonObject {
        put("address", address); put("body", body); put("date", date); put("type", 1)
    }

    private fun contact(name: String, number: String) = buildJsonObject {
        put("name", name)
        put("details", JsonArray(listOf(buildJsonObject { put("mimetype", "vnd.android.cursor.item/phone_v2"); put("data1", number) })))
    }

    @Test
    fun textsAreBundledByMonth() {
        val nov = 1_763_942_400_000L // 2025-11-24
        val dec = 1_764_892_800_000L // 2025-12-05
        val bundles = Pack.group(Pack.TEXTS, listOf(text(nov, "a"), text(dec, "b"), text(nov + 1, "c")))
        assertEquals(setOf("texts:2025-11", "texts:2025-12"), bundles.keys)
        assertEquals(2, bundles["texts:2025-11"]!!.size)
        assertEquals("texts", Pack.kindOf("texts:2025-11"))
    }

    @Test
    fun contactsAreOneBundle() {
        assertEquals(setOf("contacts"), Pack.group(Pack.CONTACTS, listOf(contact("A", "1"), contact("B", "2"))).keys)
        assertEquals(emptyMap<String, List<JsonObject>>(), Pack.group(Pack.CONTACTS, emptyList()))
    }

    @Test
    fun aWipedPhoneNeverShrinksTheBackup() {
        val pod = listOf(text(1, "old"), text(2, "older"))
        val phone = listOf(text(3, "new"))
        assertEquals(listOf("old", "older", "new"), Pack.merge(Pack.TEXTS, phone, pod).map { it["body"].toString().trim('"') })
    }

    @Test
    fun theSameTextIsKeptOnce() {
        val merged = Pack.merge(Pack.TEXTS, listOf(text(1, "hi")), listOf(text(1, "hi")))
        assertEquals(1, merged.size)
    }

    @Test
    fun anEditedContactReplacesTheOldOne() {
        val merged = Pack.merge(Pack.CONTACTS, listOf(contact("Ana", "2")), listOf(contact("ana ", "1"), contact("Bo", "3")))
        assertEquals(2, merged.size)
        assertEquals(contact("Ana", "2"), merged.first())
    }

    @Test
    fun hashIgnoresOrderButNotContent() {
        val a = listOf(text(1, "x"), text(2, "y"))
        assertEquals(Pack.hash(Pack.TEXTS, a), Pack.hash(Pack.TEXTS, a.reversed()))
        assertNotEquals(Pack.hash(Pack.TEXTS, a), Pack.hash(Pack.TEXTS, listOf(text(1, "x"), text(2, "z"))))
    }

    @Test
    fun bundlesAndTheIndexRoundTrip() {
        val items = listOf(text(1, "x"), contact("A", "1"))
        assertEquals(items, Pack.decode(Pack.encode(items)))
        val index = Pack.Index(mapOf("texts:2025-11" to 3, "texts:2025-12" to 4, "calls:2025-11" to 2), 99, "Pixel")
        val back = Pack.Index.decode(index.encode())
        assertEquals(index, back)
        assertEquals(7, back.total(Pack.TEXTS))
        assertEquals(0, back.total(Pack.CONTACTS))
    }
}
