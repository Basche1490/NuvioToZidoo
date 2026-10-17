package io.github.basche1490.nuviotozidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZidooParsingTest {

    @Test
    fun playingStatus() {
        val status = parseZidooStatus(
            """{"status":200,"video":{"status":1,"currentPosition":438994,"duration":7448480,"title":"x"}}"""
        )
        assertEquals(ZidooPlayStatus(7_448_480L, 438_994L, ZidooPlayState.PLAYING), status)
    }

    @Test
    fun zeroIsPausedNotStopped() {
        val status = parseZidooStatus("""{"status":200,"video":{"status":0,"currentPosition":5000,"duration":60000}}""")
        assertEquals(ZidooPlayState.PAUSED, status?.state)
    }

    @Test
    fun otherVideoStatusIsStopped() {
        val status = parseZidooStatus("""{"status":200,"video":{"status":-1,"currentPosition":0,"duration":60000}}""")
        assertEquals(ZidooPlayState.STOPPED, status?.state)
    }

    @Test
    fun nothingLoadedIsNull() {
        assertNull(parseZidooStatus("""{"status":806,"msg":"resource does not exist"}"""))
        assertNull(parseZidooStatus("""{"status":200}"""))
        assertNull(parseZidooStatus("not json"))
        assertNull(parseZidooStatus("[1,2]"))
    }

    @Test
    fun okBody() {
        assertTrue(isZidooOk("""{"status":200}"""))
        assertFalse(isZidooOk("""{"status":500}"""))
        assertFalse(isZidooOk(""))
    }
}
