package com.example.management_app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotLimitsTest {

    private val hour = 60L * 60L * 1000L

    @Test
    fun `un numar poate trimite cel mult MAX comenzi pe ora`() {
        val state = JSONObject()
        repeat(BotLimits.MAX_COMMANDS_PER_NUMBER_PER_HOUR) {
            assertEquals(BotLimits.Decision.ALLOW, BotLimits.register(state, "0712345678", 1000L + it, "2026-10-06"))
        }
        assertEquals(BotLimits.Decision.NUMBER_LIMIT, BotLimits.register(state, "0712345678", 2000L, "2026-10-06"))
        // Alt număr nu e afectat.
        assertEquals(BotLimits.Decision.ALLOW, BotLimits.register(state, "0799999999", 2000L, "2026-10-06"))
    }

    @Test
    fun `limita pe numar expira dupa o ora`() {
        val state = JSONObject()
        repeat(BotLimits.MAX_COMMANDS_PER_NUMBER_PER_HOUR) { BotLimits.register(state, "0712345678", 1000L, "2026-10-06") }
        assertEquals(BotLimits.Decision.ALLOW, BotLimits.register(state, "0712345678", 1000L + hour, "2026-10-06"))
    }

    @Test
    fun `cel mult 100 de raspunsuri pe zi in total, resetate a doua zi`() {
        val state = JSONObject()
        for (i in 0 until 100) {
            // Numere diferite, ca să nu atingem limita pe număr.
            assertEquals(BotLimits.Decision.ALLOW, BotLimits.register(state, "07000000%02d".format(i), 1000L, "2026-10-06"))
        }
        assertEquals(BotLimits.Decision.DAILY_LIMIT, BotLimits.register(state, "0711111111", 1000L, "2026-10-06"))
        assertEquals(BotLimits.Decision.ALLOW, BotLimits.register(state, "0711111111", 1000L, "2026-10-07"))
    }

    @Test
    fun `comenzile respinse nu prelungesc blocarea`() {
        val state = JSONObject()
        repeat(BotLimits.MAX_COMMANDS_PER_NUMBER_PER_HOUR) { BotLimits.register(state, "0712345678", 0L, "2026-10-06") }
        repeat(50) { BotLimits.register(state, "0712345678", hour / 2, "2026-10-06") }
        assertEquals(BotLimits.Decision.ALLOW, BotLimits.register(state, "0712345678", hour, "2026-10-06"))
    }

    @Test
    fun `starea veche e curatata`() {
        val state = JSONObject()
        BotLimits.register(state, "0712345678", 0L, "2026-10-06")
        BotLimits.register(state, "0799999999", 2 * hour, "2026-10-06")
        assertFalse(state.getJSONObject("perNumber").has("0712345678"))
    }

    @Test
    fun `botul raspunde doar numerelor de telefon reale`() {
        assertTrue(BotLimits.isReplyableSender("+40712345678"))
        assertTrue(BotLimits.isReplyableSender("0712345678"))
        assertTrue(BotLimits.isReplyableSender("0712 345 678"))
        assertFalse(BotLimits.isReplyableSender("BancaX"))
        assertFalse(BotLimits.isReplyableSender("INFO-SMS"))
        assertFalse(BotLimits.isReplyableSender("1234"))
        assertFalse(BotLimits.isReplyableSender("+4071234567890123"))
    }

    @Test
    fun `cel mult 2 rezervari active pe numar`() {
        assertTrue(BotLimits.canBookMore(0))
        assertTrue(BotLimits.canBookMore(1))
        assertFalse(BotLimits.canBookMore(2))
    }

    @Test
    fun `explicatia la limita vine o data pe ora si spune cand se deblocheaza`() {
        val state = JSONObject()
        val hour = 60L * 60L * 1000L
        repeat(BotLimits.MAX_COMMANDS_PER_NUMBER_PER_HOUR) {
            BotLimits.register(state, "0712345678", 1000L + it, "2026-10-06")
        }
        assertEquals(BotLimits.Decision.NUMBER_LIMIT, BotLimits.register(state, "0712345678", 5000L, "2026-10-06"))
        assertEquals(1000L + hour, BotLimits.limitNotice(state, "0712345678", 5000L))
        assertNull(BotLimits.limitNotice(state, "0712345678", 6000L))
        // După o oră se poate explica din nou.
        assertNotNull(BotLimits.limitNotice(state, "0712345678", 5000L + hour))
    }

    @Test
    fun `explicatia nu trece peste plafonul zilnic`() {
        val state = JSONObject().put("day", "2026-10-06").put("dayCount", BotLimits.MAX_REPLIES_PER_DAY)
            .put("perNumber", JSONObject().put("0712345678", org.json.JSONArray().put(1000L)))
        assertNull(BotLimits.limitNotice(state, "0712345678", 2000L))
    }
}
