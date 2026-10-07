package com.example.management_app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NoShowStoreTest {

    @Test
    fun `cheia clientului e aceeasi ca in aplicatie`() {
        assertEquals("712345678", NoShowStore.clientKey("+40712345678"))
        assertEquals("712345678", NoShowStore.clientKey("0712345678"))
        assertEquals("", NoShowStore.clientKey("1234"))
    }

    @Test
    fun `numara doar neprezentarile din ultimele 6 luni`() {
        val now = LocalDateTime.of(2030, 6, 1, 12, 0)
        val summary = JSONObject().put("712345678", JSONArray()
            .put("2030-05-01T10:00:00.000")
            .put("2030-01-10T10:00:00.000")
            .put("2029-11-01T10:00:00.000") // expirată
            .put("nu e data")).toString()
        assertEquals(2, NoShowStore.count(summary, "712345678", now))
        assertEquals(0, NoShowStore.count(summary, "799999999", now))
        assertEquals(0, NoShowStore.count(null, "712345678", now))
    }

    @Test
    fun `pragul 0 inseamna niciodata`() {
        assertTrue(NoShowStore.isBlocked(3, 3))
        assertFalse(NoShowStore.isBlocked(2, 3))
        assertFalse(NoShowStore.isBlocked(9, 0))
    }
}
