package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Gender
import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.RoomAction
import io.github.springthief1123.lovelyspace.core.RoomStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BorrowNamedTest {
    private val genre = io.github.springthief1123.lovelyspace.core.Genres.all.first().key
    private fun full(message: String, age: Int? = 30) = Room(900001, genre, RoomStatus.FULL, RoomAction.PEEK, null, null, Gender.FEMALE, age, null, message)
    private fun listed(message: String, name: String = "合成さくら", age: Int? = 30) =
        Room(900001, genre, RoomStatus.FULL, RoomAction.PEEK, null, name, Gender.FEMALE, age, "合成地域", message)

    @Test fun theNameFromTheSmartphoneListFillsTheSameRoom() {
        val filled = borrowNamed(full("合成の 募集文\nです"), listed("合成の 募集文 です"))
        assertEquals("合成さくら", filled?.name)
        assertEquals("合成地域", filled?.area)
    }

    @Test fun aDifferentMessageOrAgeIsNotTheSameRoom() {
        assertNull(borrowNamed(full("合成の募集文"), listed("別の合成の募集文")))
        assertNull(borrowNamed(full("合成の募集文", age = 25), listed("合成の募集文", age = 31)))
        assertNull(borrowNamed(full(""), listed("")))
    }

    @Test fun onlyLongMessagesMayMatchByTheirStart() {
        val long = "合成".repeat(30)
        assertTrue(sameListedMessage(long + "の続き", "$long…"))
        assertFalse(sameListedMessage("短い合成文の続き", "短い合成文"))
    }
}
