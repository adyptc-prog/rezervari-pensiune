package com.example.management_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookingSettingsQueueTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // O intrare stricată (JSON invalid după prefix) ÎNAINTEA unei rezervări
        // valide, încă neprocesate de aplicație.
        val booking = JSONObject()
            .put("s", "valid1")
            .put("n", "0712345678")
            .put("c", "2030-01-10T10:00")
            .put("st", "2030-01-10T14:00")
            .put("e", "2030-01-12T11:00")
            .put("p1", "0712345678")
        val queue = JSONArray()
            .put(JSONObject().put("id", "1").put("board", "b1").put("msg", "PEN:A:{stricat"))
            .put("nu e obiect")
            .put(JSONObject().put("id", "2").put("board", "b1").put("msg", "PEN:A:$booking"))
        context.getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SmsSyncReceiver.QUEUE_KEY, queue.toString())
            .commit()
    }

    @Test
    fun `ora rezervata ramane ocupata chiar daca alta intrare e corupta`() {
        val busy = BookingSettings.loadBusyIntervals(context, "b1", 30)
        assertEquals(listOf(LocalDateTime.of(2030, 1, 12, 11, 0)), busy.map { it.endMin })
    }

    @Test
    fun `sejurul rezervat ramane ocupat chiar daca alta intrare e corupta`() {
        val busy = BookingSettings.loadZileBusyRanges(context, "b1")
        assertEquals(1, busy.size)
        assertEquals(LocalDateTime.of(2030, 1, 10, 14, 0), busy[0].startMin)
    }

    @Test
    fun `rezervarea din coada poate fi anulata chiar daca alta intrare e corupta`() {
        val items = BookingSettings.loadBookedItems(context, "b1")
        assertEquals(listOf("valid1"), items.map { it.syncId })
    }
}
