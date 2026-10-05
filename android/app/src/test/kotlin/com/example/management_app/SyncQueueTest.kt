package com.example.management_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncQueueTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun ids(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    private fun msgs(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("msg") }
    }

    @Test
    fun `mesajul sosit in timpul procesarii nu se pierde`() {
        SmsSyncReceiver.enqueue(context, "b1", "PEN:A:{\"s\":\"a\"}")
        val read = SmsSyncReceiver.snapshot(context)

        // Un SMS nou sosește cât timp Flutter încă procesează ce a citit.
        SmsSyncReceiver.enqueue(context, "b1", "PEN:D:b")
        SmsSyncReceiver.acknowledge(context, ids(read))

        assertEquals(listOf("PEN:D:b"), msgs(SmsSyncReceiver.snapshot(context)))
    }

    @Test
    fun `fiecare intrare are un id unic`() {
        SmsSyncReceiver.enqueue(context, "b1", "PEN:D:x")
        SmsSyncReceiver.enqueue(context, "b1", "PEN:D:x")
        val all = ids(SmsSyncReceiver.snapshot(context))
        assertEquals(2, all.toSet().size)
    }

    @Test
    fun `intrarile vechi fara id primesc unul stabil si pot fi confirmate`() {
        context.getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SmsSyncReceiver.QUEUE_KEY, """["PEN:D:x",{"board":"b2","msg":"PEN:D:y"}]""")
            .commit()

        val first = SmsSyncReceiver.snapshot(context)
        assertEquals(listOf("PEN:D:x", "PEN:D:y"), msgs(first))
        // Același id la citiri repetate — altfel confirmarea n-ar găsi intrarea.
        assertEquals(ids(first), ids(SmsSyncReceiver.snapshot(context)))

        SmsSyncReceiver.acknowledge(context, ids(first))
        assertEquals("[]", SmsSyncReceiver.snapshot(context))
    }

    @Test
    fun `coada corupta nu blocheaza mesajele noi`() {
        context.getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SmsSyncReceiver.QUEUE_KEY, "{corupt")
            .commit()

        SmsSyncReceiver.enqueue(context, "b1", "PEN:D:z")
        assertEquals(listOf("PEN:D:z"), msgs(SmsSyncReceiver.snapshot(context)))
    }

    @Test
    fun `confirmarea unor id-uri necunoscute nu sterge nimic`() {
        SmsSyncReceiver.enqueue(context, "b1", "PEN:D:z")
        SmsSyncReceiver.acknowledge(context, listOf("necunoscut"))
        assertTrue(msgs(SmsSyncReceiver.snapshot(context)).contains("PEN:D:z"))
    }

    @Test
    fun `schimbarile native sunt marcate locale, cele de la partener nu`() {
        SmsSyncReceiver.enqueue(context, "b1", "PEN:D:bot")
        SmsSyncReceiver.enqueue(context, "b1", "PEN:D:p", SmsSyncReceiver.ORIGIN_PARTNER)
        val arr = JSONArray(SmsSyncReceiver.snapshot(context))
        assertEquals("local", arr.getJSONObject(0).getString("origin"))
        assertEquals("partner", arr.getJSONObject(1).getString("origin"))
    }
}
