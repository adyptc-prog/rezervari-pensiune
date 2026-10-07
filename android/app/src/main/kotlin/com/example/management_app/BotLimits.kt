package com.example.management_app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Limitele botului de rezervări prin SMS — logică pură, testabilă pe JVM.
 * Fiecare răspuns al botului e un SMS plătit de proprietar, iar oricine
 * cunoaște numărul îi poate scrie; fără limite, cineva putea genera sute de
 * SMS-uri sau ocupa toate orele libere.
 *
 * Starea (persistată de ClientBookingReceiver în ClientBookingPrefs):
 *   { "perNumber": { "<cifre>": [ts, ts, ...] }, "day": "yyyy-MM-dd", "dayCount": n,
 *     "limitNotice": { "<cifre>": ts } }
 */
object BotLimits {

    const val MAX_COMMANDS_PER_NUMBER_PER_HOUR = 15
    const val MAX_REPLIES_PER_DAY = 100
    const val MAX_ACTIVE_BOOKINGS_PER_NUMBER = 2

    private const val HOUR_MS = 60L * 60L * 1000L

    // Doar numere de telefon reale: fără expeditori alfanumerici (bănci,
    // operatori) și fără numere scurte (servicii premium, coduri de 4-6 cifre).
    private val PHONE_RE = Regex("^\\+?\\d{9,15}$")

    fun isReplyableSender(sender: String): Boolean =
        PHONE_RE.matches(sender.filterNot { it == ' ' || it == '-' })

    enum class Decision { ALLOW, NUMBER_LIMIT, DAILY_LIMIT }

    /**
     * Înregistrează o comandă de la [senderDigits] și întoarce dacă botul are
     * voie să răspundă. Comenzile respinse nu se numără (nu prelungesc
     * blocarea). [state] e modificat pe loc.
     */
    fun register(state: JSONObject, senderDigits: String, nowMs: Long, today: String): Decision {
        if (state.optString("day") != today) {
            state.put("day", today)
            state.put("dayCount", 0)
        }
        val perNumber = state.optJSONObject("perNumber") ?: JSONObject().also { state.put("perNumber", it) }
        prune(perNumber, nowMs)

        val recent = perNumber.optJSONArray(senderDigits) ?: JSONArray()
        if (recent.length() >= MAX_COMMANDS_PER_NUMBER_PER_HOUR) return Decision.NUMBER_LIMIT
        if (state.optInt("dayCount", 0) >= MAX_REPLIES_PER_DAY) return Decision.DAILY_LIMIT

        recent.put(nowMs)
        perNumber.put(senderDigits, recent)
        state.put("dayCount", state.optInt("dayCount", 0) + 1)
        return Decision.ALLOW
    }

    // Păstrează doar comenzile din ultima oră (starea nu crește la infinit).
    private fun prune(perNumber: JSONObject, nowMs: Long) {
        val keys = perNumber.keys().asSequence().toList()
        for (key in keys) {
            val arr = perNumber.optJSONArray(key) ?: run { perNumber.remove(key); continue }
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val ts = arr.optLong(i, 0L)
                if (nowMs - ts < HOUR_MS) kept.put(ts)
            }
            if (kept.length() == 0) perNumber.remove(key) else perNumber.put(key, kept)
        }
    }

    /**
     * Peste limita pe număr, clientul primește O SINGURĂ explicație pe oră
     * (altfel ar crede că botul s-a stricat); repetările de după ea rămân
     * fără răspuns, ca să nu se poată genera SMS-uri la nesfârșit. Nici
     * explicația nu trece peste plafonul zilnic. Întoarce momentul (ms) de
     * la care numărul poate scrie din nou, sau null dacă nu trimitem nimic.
     * [state] e modificat pe loc.
     */
    fun limitNotice(state: JSONObject, senderDigits: String, nowMs: Long): Long? {
        val notices = state.optJSONObject("limitNotice") ?: JSONObject().also { state.put("limitNotice", it) }
        for (key in notices.keys().asSequence().toList()) {
            if (nowMs - notices.optLong(key, 0L) >= HOUR_MS) notices.remove(key)
        }
        if (notices.has(senderDigits)) return null
        if (state.optInt("dayCount", 0) >= MAX_REPLIES_PER_DAY) return null
        val recent = state.optJSONObject("perNumber")?.optJSONArray(senderDigits) ?: return null
        if (recent.length() == 0) return null
        var oldest = Long.MAX_VALUE
        for (i in 0 until recent.length()) oldest = minOf(oldest, recent.optLong(i, nowMs))
        notices.put(senderDigits, nowMs)
        state.put("dayCount", state.optInt("dayCount", 0) + 1)
        return oldest + HOUR_MS
    }

    fun canBookMore(activeBookings: Int): Boolean = activeBookings < MAX_ACTIVE_BOOKINGS_PER_NUMBER
}
