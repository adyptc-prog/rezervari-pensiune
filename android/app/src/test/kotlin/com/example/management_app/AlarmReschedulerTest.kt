package com.example.management_app

import android.app.AlarmManager
import android.content.Context
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReschedulerTest {

    private lateinit var context: Context
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.management_boards",
                JSONArray().put(JSONObject().put("id", "b1").put("name", "Pensiune")).toString())
            .putString("flutter.management_items_b1", "[]")
            .commit()
    }

    private fun scheduledAlarms() =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms

    @Test
    fun `termenul rezervarii botului inca din coada e rearmat dupa repornire`() {
        val createdAt = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0)
        val syncId = "botrezervare0001"
        val payload = JSONObject().put("s", syncId).put("n", "+40712345678")
            .put("c", createdAt.format(fmt))
            .put("st", createdAt.plusDays(5).format(fmt))
            .put("e", createdAt.plusDays(6).format(fmt))
            .put("p1", "+40712345678")
        SmsSyncReceiver.enqueue(context, "b1", "PEN:A:$payload")
        val alarmId = AlarmScheduler.validationAlarmId(syncId)
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.validation_alarm_$alarmId",
                JSONObject().put("board", "b1").put("sync", syncId).toString())
            .commit()

        AlarmRescheduler.rescheduleAll(context)

        val expected = createdAt.plusHours(24).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarms = scheduledAlarms()
        assertEquals(1, alarms.size)
        assertEquals(expected, alarms.single().triggerAtTime)
    }

    @Test
    fun `fara cheia termenului (deja procesat) nu se rearmeaza nimic`() {
        val payload = JSONObject().put("s", "altarezervare001")
            .put("c", LocalDateTime.now().format(fmt))
            .put("e", LocalDateTime.now().plusDays(3).format(fmt))
        SmsSyncReceiver.enqueue(context, "b1", "PEN:A:$payload")

        AlarmRescheduler.rescheduleAll(context)

        assertTrue(scheduledAlarms().isEmpty())
    }
}
