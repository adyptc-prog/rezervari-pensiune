package com.example.management_app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * AlarmManager își pierde toate alarmele programate la repornirea
 * telefonului (sau la actualizarea aplicației). Această clasă recalculează
 * orele de declanșare din înregistrările persistate de Flutter, pentru
 * fiecare din cele 10 tabele ("flutter.management_boards" +
 * "flutter.management_items_<boardId>"), și reprogramează alarmele native —
 * fără să fie nevoie ca aplicația Flutter să fie redeschisă.
 *
 * Conținutul notificării / mesajului SMS nu e recalculat aici: el a fost
 * deja scris în SharedPreferences de partea Dart (NotificationService /
 * SmsService) la ultima programare/editare a înregistrării, sub aceleași
 * chei ("flutter.notif_alarm_<id>" / "flutter.sms_alarm_<id>"). Rearmăm
 * doar alarmele pentru care acele chei încă există.
 *
 * ID-urile includ indexul tabelului (0, 1, 2), la fel ca în Dart
 * (NotificationService/SmsService), ca să nu se suprapună între tabele.
 */
object AlarmRescheduler {

    private fun parseIso(raw: String?): Long? {
        if (raw.isNullOrEmpty()) return null
        val pattern = if (raw.contains('.')) {
            "yyyy-MM-dd'T'HH:mm:ss.SSS"
        } else {
            "yyyy-MM-dd'T'HH:mm:ss"
        }
        return try {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            fmt.parse(raw)?.time
        } catch (_: Exception) {
            null
        }
    }

    fun rescheduleAll(context: Context) {
        try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val boardsJson = prefs.getString("flutter.management_boards", null)

            // ID-ul de tabel gol ("") înseamnă: migrarea Dart nu a rulat încă
            // (ex: reboot înainte de prima deschidere a aplicației după
            // actualizare) — folosim cheia veche, fără sufix de tabel.
            val boardIds: List<String> = if (boardsJson != null) {
                val arr = JSONArray(boardsJson)
                (0 until arr.length()).map { arr.getJSONObject(it).optString("id", "") }
            } else {
                listOf("")
            }

            val now = System.currentTimeMillis()
            for ((boardIndex, boardId) in boardIds.withIndex()) {
                val itemsKey = if (boardId.isEmpty()) {
                    "flutter.management_items"
                } else {
                    "flutter.management_items_$boardId"
                }
                val itemsJson = prefs.getString(itemsKey, null) ?: continue
                rescheduleBoard(context, prefs, JSONArray(itemsJson), boardIndex, now)
                if (boardId.isNotEmpty()) {
                    rescheduleValidationAlarms(context, prefs, JSONArray(itemsJson), now)
                }
            }
            for (boardId in boardIds) {
                if (boardId.isNotEmpty()) rescheduleQueuedValidationAlarms(context, prefs, boardId, now)
            }
        } catch (_: Exception) {
            // Date corupte / neașteptate — nu blocăm boot-ul aplicației
        }
        try {
            BotReminders.rescheduleAll(context)
        } catch (_: Exception) {
        }
    }

    private fun rescheduleBoard(
        context: Context,
        prefs: SharedPreferences,
        items: JSONArray,
        boardIndex: Int,
        now: Long,
    ) {
        val notifBase = boardIndex * 1_000_000
        val smsBase   = boardIndex * 10_000_000

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val number = item.optInt("number", -1)
            if (number < 0) continue

            val warningAtMs = if (item.isNull("warningAt")) null else parseIso(item.optString("warningAt"))
            val expiresAtMs = if (item.isNull("expiresAt")) null else parseIso(item.optString("expiresAt"))

            // Notificări push: id = notifBase + number*10+1 (avertizare), +2 (expirare)
            if (warningAtMs != null && warningAtMs > now && expiresAtMs != null) {
                rearmNotif(context, prefs, notifBase + number * 10 + 1, warningAtMs)
            }
            if (expiresAtMs != null && expiresAtMs > now) {
                rearmNotif(context, prefs, notifBase + number * 10 + 2, expiresAtMs)
            }

            // SMS: id-uri avertizare = smsBase + n*100+10..12, expirare = +20..22
            if (warningAtMs != null && warningAtMs > now) {
                for (id in intArrayOf(smsBase + number * 100 + 10, smsBase + number * 100 + 11, smsBase + number * 100 + 12)) {
                    rearmSms(context, prefs, id, warningAtMs)
                }
            }
            if (expiresAtMs != null && expiresAtMs > now) {
                for (id in intArrayOf(smsBase + number * 100 + 20, smsBase + number * 100 + 21, smsBase + number * 100 + 22)) {
                    rearmSms(context, prefs, id, expiresAtMs)
                }
            }
        }
    }

    private fun rearmNotif(context: Context, prefs: SharedPreferences, id: Int, triggerAtMs: Long) {
        if (!prefs.contains("flutter.notif_alarm_$id")) return
        AlarmScheduler.scheduleNotifAlarm(context, id, triggerAtMs)
    }

    private fun rearmSms(context: Context, prefs: SharedPreferences, id: Int, triggerAtMs: Long) {
        if (!prefs.contains("flutter.sms_alarm_$id")) return
        AlarmScheduler.scheduleSmsAlarm(context, id, triggerAtMs)
    }

    // Rearmează termenul de 24h pentru validarea plății —
    // itemii deja validați sau fără syncId/createdAt sunt ignorați. Termenul
    // se recalculează mereu din createdAt (nu se stochează separat), deci
    // rearmarea e idempotentă indiferent de câte ori rulează.
    private fun rescheduleValidationAlarms(
        context: Context, prefs: SharedPreferences, items: JSONArray, now: Long,
    ) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optBoolean("validated", false)) continue
            val syncId = item.optString("syncId", "")
            if (syncId.isEmpty()) continue
            val createdAtMs = parseIso(item.optString("createdAt", "")) ?: continue
            val deadlineMs = createdAtMs + 24L * 60 * 60 * 1000

            val alarmId = AlarmScheduler.validationAlarmId(syncId)
            if (!prefs.contains("flutter.validation_alarm_$alarmId")) continue
            // Dacă termenul a trecut deja cât timp telefonul a fost oprit,
            // declanșăm aproape imediat, ca rezervarea neplătită să fie tot
            // anulată, doar cu întârziere — nu ignorată definitiv.
            AlarmScheduler.scheduleValidationAlarm(context, alarmId, maxOf(deadlineMs, now + 5_000))
        }
    }

    // Rezervările botului încă neprocesate de aplicație (stau în coada de
    // sincronizare, nu în management_items) — termenul lor de 24h s-ar pierde
    // altfel la repornire, iar rezervarea neplătită n-ar mai fi anulată.
    private fun rescheduleQueuedValidationAlarms(
        context: Context, prefs: SharedPreferences, boardId: String, now: Long,
    ) {
        for ((prefix, payload) in BookingSettings.queuedEntries(context, boardId)) {
            if (prefix != "PEN:A:") continue
            val j = try { JSONObject(payload) } catch (_: Exception) { continue }
            val syncId = j.optString("s", "")
            if (syncId.isEmpty()) continue
            val alarmId = AlarmScheduler.validationAlarmId(syncId)
            if (!prefs.contains("flutter.validation_alarm_$alarmId")) continue
            val createdAtMs = try {
                LocalDateTime.parse(j.optString("c", "")).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            } catch (_: Exception) {
                continue
            }
            val deadlineMs = createdAtMs + 24L * 60 * 60 * 1000
            AlarmScheduler.scheduleValidationAlarm(context, alarmId, maxOf(deadlineMs, now + 5_000))
        }
    }
}
