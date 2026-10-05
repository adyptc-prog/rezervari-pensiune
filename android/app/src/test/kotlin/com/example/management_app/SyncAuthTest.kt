package com.example.management_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAuthTest {

    private val code = "K7QM-2XPA"
    private val msg = """PEN:A:{"s":"abc","n":"Ion"}"""

    @Test
    fun `mesajul semnat e acceptat cu acelasi cod`() {
        val signed = SyncAuth.sign(code, msg)
        assertTrue(signed.startsWith("PEN:S:"))
        assertEquals(msg, SyncAuth.verify(code, signed))
    }

    @Test
    fun `codul e normalizat (litere mici, spatii, cratime)`() {
        val signed = SyncAuth.sign(code, msg)
        assertEquals(msg, SyncAuth.verify("k7qm 2xpa", signed))
    }

    @Test
    fun `alt cod e respins`() {
        assertNull(SyncAuth.verify("ZZZZ9999", SyncAuth.sign(code, msg)))
    }

    @Test
    fun `mesajul modificat e respins`() {
        val signed = SyncAuth.sign(code, msg)
        assertNull(SyncAuth.verify(code, signed.replace("Ion", "Hacker")))
    }

    @Test
    fun `mesajele nesemnate sau trunchiate sunt respinse`() {
        assertNull(SyncAuth.verify(code, msg))
        assertNull(SyncAuth.verify(code, "PEN:S:abc:$msg"))
        assertNull(SyncAuth.verify(code, "PEN:S:"))
        assertNull(SyncAuth.verify(null, SyncAuth.sign(code, msg)))
    }

    @Test
    fun `codurile prea scurte nu sunt valide`() {
        assertFalse(SyncAuth.isValidCode("ABC-123"))
        assertFalse(SyncAuth.isValidCode(null))
        assertTrue(SyncAuth.isValidCode("abcd-1234"))
    }

    @Test
    fun `semnatura e determinista (aceeasi pe ambele telefoane)`() {
        assertEquals(SyncAuth.sign(code, msg), SyncAuth.sign("k7qm2xpa", msg))
    }
}
