package com.example.management_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsSyncReceiverTest {

    private lateinit var context: Context
    private val receiver = SmsSyncReceiver()
    private val partner = "+40722000111"
    private val codeB1 = "AAAA2222"
    private val codeB2 = "BBBB3333"
    private val msg = """PEN:A:{"s":"abc","n":"Ion","c":"2026-10-06T10:00"}"""
    private val sent = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val boards = JSONArray()
            .put(JSONObject().put("id", "b1").put("name", "Tabel 1"))
            .put(JSONObject().put("id", "b2").put("name", "Tabel 2"))
        // Același partener pe două tabele, cu coduri diferite.
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.management_boards", boards.toString())
            .putString("flutter.sync_partner_phone_b1", "0722000111")
            .putString("flutter.sync_secret_b1", codeB1)
            .putString("flutter.sync_partner_phone_b2", "0722 000 111")
            .putString("flutter.sync_secret_b2", codeB2)
            .commit()
        SmsSender.testSink = { phone, message -> sent.add(phone to message) }
    }

    @After
    fun tearDown() {
        SmsSender.testSink = null
    }

    private fun queue(): List<JSONObject> {
        val arr = JSONArray(SmsSyncReceiver.snapshot(context))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun `mesajul nesemnat de la partener e ignorat`() {
        receiver.handleSms(context, partner, msg)
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `mesajul semnat ajunge in tabelul al carui cod il valideaza`() {
        receiver.handleSms(context, partner, SyncAuth.sign(codeB2, msg))
        val q = queue()
        assertEquals(1, q.size)
        assertEquals("b2", q[0].getString("board"))
        assertEquals(msg, q[0].getString("msg"))
        assertEquals(SmsSyncReceiver.ORIGIN_PARTNER, q[0].getString("origin"))
    }

    @Test
    fun `semnatura corecta de la alt numar e ignorata`() {
        receiver.handleSms(context, "+40799999999", SyncAuth.sign(codeB1, msg))
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `mesajul modificat pe drum e ignorat`() {
        val signed = SyncAuth.sign(codeB1, msg).replace("Ion", "Eve")
        receiver.handleSms(context, partner, signed)
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `trimiterea semneaza cu codul tabelului`() {
        assertTrue(SmsSyncReceiver.sendSigned(context, "b1", msg))
        assertEquals("0722000111", sent.single().first)
        assertEquals(msg, SyncAuth.verify(codeB1, sent.single().second))
    }

    @Test
    fun `fara cod de imperechere nu se trimite nimic`() {
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .remove("flutter.sync_secret_b1").commit()
        assertFalse(SmsSyncReceiver.sendSigned(context, "b1", msg))
        assertTrue(sent.isEmpty())
    }
}
