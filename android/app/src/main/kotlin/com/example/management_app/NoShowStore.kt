package com.example.management_app

import android.content.Context
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * Neprezentările clienților, pentru botul SMS (blocarea rezervărilor).
 *
 * Aplicația (no_show.dart) calculează bilele din toate tabelele și scrie un
 * rezumat { "<ultimele 9 cifre>": ["ISO", ...] } în „flutter.no_show_summary”,
 * plus pragul în „flutter.no_show_block_threshold” (0 = niciodată). Aici se
 * recalculează doar fereastra de 6 luni, ca o bilă să expire la timp chiar
 * dacă aplicația nu mai e deschisă.
 */
object NoShowStore {

    const val DEFAULT_THRESHOLD = 3
    private const val WINDOW_DAYS = 180L

    /** Cheia clientului: ultimele 9 cifre, la fel ca clientKey() din Dart. */
    fun clientKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 7) return ""
        return if (digits.length <= 9) digits else digits.takeLast(9)
    }

    /** Câte neprezentări din ultimele 6 luni are [summaryJson] pentru [key]. */
    fun count(summaryJson: String?, key: String, now: LocalDateTime): Int {
        if (key.isEmpty() || summaryJson.isNullOrBlank()) return 0
        val dates = try {
            JSONObject(summaryJson).optJSONArray(key)
        } catch (_: Exception) {
            null
        } ?: return 0
        val cutoff = now.minusDays(WINDOW_DAYS)
        var n = 0
        for (i in 0 until dates.length()) {
            val at = try {
                LocalDateTime.parse(dates.optString(i))
            } catch (_: Exception) {
                null
            } ?: continue
            if (at.isAfter(cutoff)) n++
        }
        return n
    }

    fun isBlocked(count: Int, threshold: Int): Boolean = threshold > 0 && count >= threshold

    fun threshold(context: Context): Int {
        val v = prefs(context).all["flutter.no_show_block_threshold"] as? Number
        return v?.toInt() ?: DEFAULT_THRESHOLD
    }

    fun countFor(context: Context, phone: String): Int =
        count(prefs(context).getString("flutter.no_show_summary", null), clientKey(phone), LocalDateTime.now())

    private fun prefs(context: Context) =
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
}
