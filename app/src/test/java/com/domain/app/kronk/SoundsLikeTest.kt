package com.domain.app.kronk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundsLikeTest {

    private val mates = listOf("Pargo", "Jye Smith", "Ana", "Anna Lee", "Tal Moss", "Mango")

    private fun find(heard: String) = SoundsLike.find(heard, mates) { listOf(it) }.map { it.item }

    @Test fun heardAsAnotherWord() {
        assertEquals(listOf("Pargo"), find("pago"))
        assertEquals(listOf("Pargo"), find("par go"))
        assertEquals(listOf("Pargo"), find("pargo"))
    }

    @Test fun exactWinsOverSoundsLike() {
        assertEquals(listOf("Ana"), find("ana"))
        assertEquals(listOf("Jye Smith"), find("jye"))
        assertEquals(listOf("Tal Moss"), find("tal moss"))
    }

    @Test fun closeButNotTheSame() {
        assertEquals(listOf("Mango"), find("mangle"))
    }

    @Test fun nothingLikeIt() {
        assertTrue(find("frederick").isEmpty())
        assertTrue(find("").isEmpty())
    }

    @Test fun kronkSoundsLikeItsMishearings() {
        val kronk = SoundsLike.key("kronk")
        listOf("cronk", "crank", "krunk").forEach { assertEquals(it, kronk, SoundsLike.key(it)) }
    }
}
