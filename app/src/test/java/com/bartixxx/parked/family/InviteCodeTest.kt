package com.bartixxx.parked.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeTest {

    @Test
    fun `normalizes lowercase, dashes and spaces`() {
        assertEquals("ABCDEFGHJK", InviteCode.normalize("abcde-fgh jk"))
    }

    @Test
    fun `maps ambiguous characters to Crockford digits`() {
        assertEquals("0123456789", InviteCode.normalize("OIZ3456789"))
    }

    @Test
    fun `rejects wrong length`() {
        assertNull(InviteCode.normalize("ABC"))
        assertNull(InviteCode.normalize("ABCDEFGHJKM"))
    }

    @Test
    fun `rejects letters outside the alphabet`() {
        assertNull(InviteCode.normalize("ABCDEFGHJU"))
        assertNull(InviteCode.normalize("ABCDEFGHJ!"))
    }

    @Test
    fun `formats with a dash in the middle`() {
        assertEquals("ABCDE-FGHJK", InviteCode.format("ABCDEFGHJK"))
        assertEquals("SHORT", InviteCode.format("SHORT"))
    }

    @Test
    fun `isComplete only for normalized codes`() {
        assertTrue(InviteCode.isComplete("abcde-fghjk"))
        assertFalse(InviteCode.isComplete("abcde"))
        assertFalse(InviteCode.isComplete("abcde-fghju"))
    }
}
