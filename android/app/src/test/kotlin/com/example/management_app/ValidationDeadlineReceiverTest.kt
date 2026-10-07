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
import java.time.LocalDateTime

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

    private fun seedUnpaidBooking(
        syncId: String,
        // Implicit: check-in peste 2 zile (rezervare încă neîncepută).
        startsAt: LocalDateTime = LocalDateTime.now().plusDays(2).withHour(14).withMinute(0),
    ): Int {
        val alarmId = AlarmScheduler.validationAlarmId(syncId)
        val item = JSONObject()
            .put("syncId", syncId)
            .put("number", 1)
            .put("name", "Client")
            .put("description", "")
            .put("createdAt", startsAt.minusDays(9).toString())
            .put("startsAt", startsAt.toString())
            .put("expiresAt", startsAt.plusDays(2).withHour(11).toString())
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

    @Test
    fun `sejurul deja inceput nu e anulat si clientul nu primeste SMS`() {
        val sent = mutableListOf<String>()
        SmsSender.testSink = { _, msg -> sent.add(msg) }
        try {
            val syncId = syncIdWithHashSign(negative = false)
            val alarmId = seedUnpaidBooking(syncId, startsAt = LocalDateTime.now().minusDays(1))

            assertEquals(emptyList<String>(), fireAndWait(alarmId))
            assertEquals(emptyList<String>(), sent)
        } finally {
            SmsSender.testSink = null
        }
    }

    @Test
    fun `sejur viitor vs inceput`() {
        val now = LocalDateTime.of(2026, 10, 8, 12, 0)
        fun item(start: LocalDateTime?, end: LocalDateTime?) =
            BookedItem("b1", "s", "", "", listOf("0712345678"), end, start, false)
        assertTrue(ValidationDeadlineReceiver.stayIsInFuture(item(now.plusHours(1), now.plusDays(2)), now))
        assertEquals(false, ValidationDeadlineReceiver.stayIsInFuture(item(now.minusHours(1), now.plusDays(2)), now))
        // Fără check-in: o noapte înainte de check-out.
        assertEquals(false, ValidationDeadlineReceiver.stayIsInFuture(item(null, now.plusHours(5)), now))
        assertTrue(ValidationDeadlineReceiver.stayIsInFuture(item(null, now.plusDays(3)), now))
    }
}
