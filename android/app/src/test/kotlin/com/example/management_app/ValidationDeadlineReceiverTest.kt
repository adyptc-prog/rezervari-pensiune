package com.example.management_app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ValidationDeadlineReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun syncIdWithHashSign(negative: Boolean): String {
        var i = 0
        while (true) {
            val candidate = "rezervare$i".padEnd(16, 'x')
            if ((candidate.hashCode() < 0) == negative) return candidate
            i++
        }
    }

    private fun seedUnpaidBooking(syncId: String): Int {
        val alarmId = AlarmScheduler.validationAlarmId(syncId)
        val item = JSONObject()
            .put("syncId", syncId)
            .put("number", 1)
            .put("name", "Client")
            .put("description", "")
            .put("createdAt", "2026-10-01T10:00:00.000")
            .put("startsAt", "2026-10-10T14:00:00.000")
            .put("expiresAt", "2026-10-12T11:00:00.000")
            .put("phoneNumber", "0712345678")
            .put("validated", false)
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.management_boards",
                JSONArray().put(JSONObject().put("id", "b1").put("name", "Pensiune")).toString())
            .putString("flutter.management_items_b1", JSONArray().put(item).toString())
            .putString("flutter.validation_alarm_$alarmId",
                JSONObject().put("board", "b1").put("sync", syncId).toString())
            .commit()
        return alarmId
    }

    private fun queuedMessages(): List<String> {
        val raw = context.getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SmsSyncReceiver.QUEUE_KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("msg") }
    }

    // Receiverul lucrează pe un fir separat (goAsync) — așteptăm rezultatul.
    private fun fireAndWait(alarmId: Int): List<String> {
        val intent = Intent(context, ValidationDeadlineReceiver::class.java)
            .putExtra("validation_alarm_id", alarmId)
        context.sendBroadcast(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val msgs = queuedMessages()
            if (msgs.isNotEmpty()) return msgs
            Thread.sleep(20)
        }
        return queuedMessages()
    }

    @Test
    fun `rezervarea neplatita e anulata si cand ID-ul alarmei e negativ`() {
        val syncId = syncIdWithHashSign(negative = true)
        val alarmId = seedUnpaidBooking(syncId)
        assertTrue(alarmId < 0)

        assertEquals(listOf("PEN:D:$syncId"), fireAndWait(alarmId))
    }

    @Test
    fun `rezervarea neplatita e anulata cu ID pozitiv`() {
        val syncId = syncIdWithHashSign(negative = false)
        val alarmId = seedUnpaidBooking(syncId)
        assertTrue(alarmId >= 0)

        assertEquals(listOf("PEN:D:$syncId"), fireAndWait(alarmId))
    }
}
