package com.example.management_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bot de rezervări prin SMS pentru clienți.
 *
 *   „liber” / „liber <tabel>”  → pe tabele „interval” (salon): răspunde cu
 *                                 până la 6 ore libere, numerotate. Pe tabele
 *                                 „zile” (pensiune): întreabă mai întâi câte
 *                                 nopți, apoi oferă date disponibile.
 *   „next”                     → următoarea pagină de ore/date libere
 *   un număr (ex. „2”)         → înseamnă, în ordinea priorității (cea mai
 *                                 recentă interacțiune câștigă): număr de
 *                                 nopți cerut, alegere dintr-o ofertă de
 *                                 rezervare, sau alegere dintr-o listă de
 *                                 anulare
 *   „anuleaza” / „anulare”     → caută programările/sejururile viitoare de pe
 *                                 acest număr (create de bot SAU adăugate
 *                                 manual din aplicație) și le anulează —
 *                                 direct dacă e una singură, altfel cere
 *                                 alegerea dintr-o listă
 *
 * Rulează independent de Flutter (ca SmsAlarmReceiver) — citește direct din
 * SharedPreferences prin BookingSettings/FreeSlotCalculator/DayRangeCalculator,
 * ca să răspundă instant chiar dacă aplicația e complet închisă. Rezervarea
 * confirmată/anulată e scrisă în coada de sincronizare existentă ca mesaj
 * „PEN:A:”/„PEN:D:” — Flutter o preia automat la următoarea deschidere, cu
 * logica de sincronizare deja existentă (numerotare, alarme, sloturi libere
 * etc.), fără cod separat pentru asta.
 *
 * Declanșatoarele sunt stricte (mesajul trebuie să înceapă exact cu cuvântul
 * cheie) ca să nu se confunde cu un SMS personal obișnuit care conține
 * întâmplător acel cuvânt undeva în text.
 */
class ClientBookingReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS_NAME        = "ClientBookingPrefs"
        private const val OFFERS_KEY        = "offers"
        private const val OFFER_TTL_MIN     = 20L
        private const val CANCEL_OFFERS_KEY = "cancelOffers"
        private const val RATE_KEY          = "rateLimits"
        private const val CANCEL_OFFER_TTL_MIN = 10L
        // Stare „aștept răspuns cu numărul de nopți” — tabele în modul „zile”,
        // imediat după „liber”. TTL scurt: e un singur pas, nu o navigare.
        private const val NIGHTS_OFFERS_KEY = "nightsOffers"
        private const val NIGHTS_OFFER_TTL_MIN = 5L
        private const val PAGE_SIZE       = 6
        private const val HORIZON_DAYS    = 14
        // Orizont mai lung pentru tabelele „zile” (pensiune) — 14 zile e prea
        // puțin pentru un sejur planificat cu mult timp înainte.
        private const val ZILE_HORIZON_DAYS = 90
        private const val MAX_TOTAL_SLOTS = 200

        // Toate SMS-urile de la clienți se procesează strict în ordinea sosirii
        // (FIFO) pe un singur fir de execuție — evită curse între două cereri
        // aproape simultane (ofertă suprascrisă, rezervare pierdută).
        private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()

        private val ISO_SHORT: DateTimeFormatter        = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        private val DISPLAY_TIME_FMT: DateTimeFormatter  = DateTimeFormatter.ofPattern("HH:mm")
        private val DISPLAY_DATE_FMT: DateTimeFormatter  = DateTimeFormatter.ofPattern("dd.MM")

        private val LIBER_RE   = Regex("^liber(\\s+(\\S+))?$")
        private val NUMBER_RE  = Regex("^(\\d{1,2})$")
        // "anuleaza" / "anulare", eventual urmat de orice alt text (ex. "anuleaza
        // aceasta programare") — normalize() scoate deja diacriticele, deci
        // "anulează" ajunge tot aici.
        private val CANCEL_RE  = Regex("^(anuleaza|anulare)(\\s+.*)?$")
    }

    override fun onReceive(context: Context, intent: Intent) {
        Diag.i("onReceive action=${intent.action}")
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        val bySender = mutableMapOf<String, StringBuilder>()
        for (sms in pdus) {
            val sender = sms.originatingAddress ?: continue
            val body   = sms.messageBody        ?: continue
            bySender.getOrPut(sender) { StringBuilder() }.append(body)
        }
        Diag.i("onReceive senders=${bySender.keys.map { Diag.mask(it) }}")
        if (bySender.isEmpty()) return

        val pending = goAsync()
        val appContext = context.applicationContext
        EXECUTOR.execute {
            try {
                for ((sender, sb) in bySender) {
                    try {
                        handleMessage(appContext, sender, sb.toString())
                    } catch (e: Exception) {
                        Diag.e("handleMessage threw for sender=${Diag.mask(sender)}", e)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun digitsOnly(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    private fun normalize(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{Mn}+"), "").lowercase().trim()
    }

    // internal: apelat direct de testele Robolectric (fără SMS-uri reale).
    internal fun handleMessage(context: Context, sender: String, rawBody: String) {
        Diag.i("handleMessage sender=${Diag.mask(sender)} len=${rawBody.length}")
        // Mesaje de sincronizare — ale acestei aplicații (PEN:) sau ale
        // aplicației Organizator (ORG:), dacă e pe același telefon.
        if (rawBody.startsWith("PEN:") || rawBody.startsWith("ORG:")) {
            Diag.i("handleMessage: ignored, looks like sync message")
            return
        }

        val senderDigits = digitsOnly(sender)
        if (senderDigits.isEmpty()) {
            Diag.w("handleMessage: senderDigits empty, aborting")
            return
        }
        // După expirarea trial-ului, fără licență, botul nu mai răspunde
        // (fiecare răspuns e un SMS trimis de aplicație).
        if (!Entitlement.isActive(context)) {
            Diag.i("handleMessage: no license and trial expired, bot disabled")
            return
        }
        if (!BotLimits.isReplyableSender(sender)) {
            Diag.i("handleMessage: sender is not a phone number, ignoring")
            return
        }
        if (isSyncPartner(context, senderDigits)) {
            Diag.i("handleMessage: sender=${Diag.mask(senderDigits)} matched sync_partner_phone, ignoring")
            return
        }

        val body = normalize(rawBody)
        if (body.isEmpty()) return

        val liberMatch  = LIBER_RE.find(body)
        val cancelMatch = CANCEL_RE.find(body)
        val numberMatch = NUMBER_RE.find(body)
        Diag.i("handleMessage: liberMatch=${liberMatch != null} cancelMatch=${cancelMatch != null} numberMatch=${numberMatch != null}")

        val isCommand = liberMatch != null || body == "next" || cancelMatch != null || numberMatch != null
        if (!isCommand) {
            Diag.i("handleMessage: no pattern matched, ignoring silently (by design)")
            return
        }
        // Fiecare răspuns e un SMS plătit — limităm cât poate cere un număr
        // și cât răspunde botul pe zi. Peste limită: tăcere (un răspuns de
        // refuz ar costa la fel).
        val limit = registerCommand(context, senderDigits)
        if (limit != BotLimits.Decision.ALLOW) {
            Diag.w("handleMessage: rate limited ($limit)")
            return
        }

        when {
            liberMatch != null -> {
                val token = liberMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                startOffer(context, sender, senderDigits, token)
            }
            body == "next" -> continueOffer(context, sender, senderDigits)
            cancelMatch != null -> startCancelFlow(context, sender, senderDigits)
            numberMatch != null -> {
                // Un răspuns numeric poate însemna trei lucruri diferite, în
                // funcție de ce a întrebat ultimul mesaj trimis clientului:
                // câte nopți vrea (tabel „zile”, imediat după „liber”), ce
                // opțiune de rezervare alege, sau ce programare alege să
                // anuleze. Când mai multe sunt active simultan, câștigă cea
                // mai recentă interacțiune.
                val nightsOffer  = getNightsOffer(context, senderDigits)
                val bookingOffer = getOffer(context, senderDigits)
                val cancelOffer  = getCancelOffer(context, senderDigits)
                val choice = numberMatch.groupValues[1].toInt()
                val candidates = listOfNotNull(
                    nightsOffer?.let  { "nights"  to it.optLong("ts", 0L) },
                    bookingOffer?.let { "booking" to it.optLong("ts", 0L) },
                    cancelOffer?.let  { "cancel"  to it.optLong("ts", 0L) },
                )
                when (candidates.maxByOrNull { it.second }?.first) {
                    "nights" -> confirmNights(context, sender, senderDigits, choice)
                    "cancel" -> confirmCancel(context, sender, senderDigits, choice)
                    else     -> confirmOffer(context, sender, senderDigits, choice)
                }
            }
        }
    }

    private fun registerCommand(context: Context, senderDigits: String): BotLimits.Decision {
        val prefs = bookingPrefs(context)
        val state = try {
            JSONObject(prefs.getString(RATE_KEY, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val today = java.time.LocalDate.now().toString()
        val decision = BotLimits.register(state, senderDigits, System.currentTimeMillis(), today)
        prefs.edit().putString(RATE_KEY, state.toString()).apply()
        return decision
    }

    // Plafonul de rezervări active pe număr — altfel cineva putea ocupa toate
    // orele libere cu „liber” + „1” repetat.
    private fun bookingLimitReached(context: Context, sender: String, senderDigits: String): Boolean {
        if (BotLimits.canBookMore(findActiveBookings(context, senderDigits).size)) return false
        sendSms(
            context, sender,
            "Ai deja ${BotLimits.MAX_ACTIVE_BOOKINGS_PER_NUMBER} rezervări active. " +
                "Pentru una nouă, anulează mai întâi una scriind ANULEAZA."
        )
        return true
    }

    // ── Excludere parteneri de sincronizare (device-to-device) ──────────────────
    private fun isSyncPartner(context: Context, senderDigits: String): Boolean {
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val boards = BookingSettings.loadBoards(context)
        val keys = if (boards.isEmpty()) {
            listOf("flutter.sync_partner_phone")
        } else {
            boards.map { "flutter.sync_partner_phone_${it.id}" }
        }
        for (key in keys) {
            val partnerDigits = digitsOnly(prefs.getString(key, null))
            if (partnerDigits.isEmpty()) continue
            val minLen = minOf(senderDigits.length, partnerDigits.length)
            if (minLen < 7) continue
            if (senderDigits.takeLast(minLen) == partnerDigits.takeLast(minLen)) {
                Diag.i("isSyncPartner: MATCH key=$key sender=${Diag.mask(senderDigits)}")
                return true
            }
        }
        return false
    }

    // ── Potrivire tabel după nume ────────────────────────────────────────────────
    private fun matchBoard(boards: List<BoardInfo>, token: String?): BoardInfo? {
        if (token == null) return boards.firstOrNull()
        val t = normalize(token)
        boards.firstOrNull { normalize(it.name) == t }?.let { return it }
        boards.firstOrNull { normalize(it.name).contains(t) || t.contains(normalize(it.name)) }?.let { return it }
        return null
    }

    // ── Stocare ofertă activă per client ─────────────────────────────────────────
    private fun bookingPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadOffers(context: Context): JSONObject = try {
        JSONObject(bookingPrefs(context).getString(OFFERS_KEY, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun saveOffers(context: Context, offers: JSONObject) {
        bookingPrefs(context).edit().putString(OFFERS_KEY, offers.toString()).apply()
    }

    private fun getOffer(context: Context, senderDigits: String): JSONObject? {
        val o = loadOffers(context).optJSONObject(senderDigits) ?: return null
        val ts = o.optLong("ts", 0L)
        if (System.currentTimeMillis() - ts > OFFER_TTL_MIN * 60_000L) return null
        return o
    }

    private fun setOffer(
        context: Context, senderDigits: String, boardId: String, offset: Int, nights: Int? = null,
    ) {
        val offers = loadOffers(context)
        val o = JSONObject()
        o.put("board", boardId)
        o.put("offset", offset)
        o.put("ts", System.currentTimeMillis())
        // Prezența cheii „nights” marchează oferta ca fiind pentru un tabel
        // „zile” — folosită la paginare (NEXT) și la confirmare, ca să știm ce
        // algoritm de calcul sloturi și ce format de mesaj să reutilizăm.
        if (nights != null) o.put("nights", nights)
        offers.put(senderDigits, o)
        saveOffers(context, offers)
    }

    private fun clearOffer(context: Context, senderDigits: String) {
        val offers = loadOffers(context)
        offers.remove(senderDigits)
        saveOffers(context, offers)
    }

    // ── Stocare cerere de anulare activă per client ──────────────────────────────
    // Separată de oferta de rezervare ("offers") ca să nu se confunde un răspuns
    // numeric destinat uneia cu celălalt flux — vezi dezambiguizarea din handleMessage.
    private fun loadCancelOffers(context: Context): JSONObject = try {
        JSONObject(bookingPrefs(context).getString(CANCEL_OFFERS_KEY, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun saveCancelOffers(context: Context, offers: JSONObject) {
        bookingPrefs(context).edit().putString(CANCEL_OFFERS_KEY, offers.toString()).apply()
    }

    private fun getCancelOffer(context: Context, senderDigits: String): JSONObject? {
        val o = loadCancelOffers(context).optJSONObject(senderDigits) ?: return null
        val ts = o.optLong("ts", 0L)
        if (System.currentTimeMillis() - ts > CANCEL_OFFER_TTL_MIN * 60_000L) return null
        return o
    }

    private fun setCancelOffer(context: Context, senderDigits: String, candidates: JSONArray) {
        val offers = loadCancelOffers(context)
        val o = JSONObject()
        o.put("candidates", candidates)
        o.put("ts", System.currentTimeMillis())
        offers.put(senderDigits, o)
        saveCancelOffers(context, offers)
    }

    private fun clearCancelOffer(context: Context, senderDigits: String) {
        val offers = loadCancelOffers(context)
        offers.remove(senderDigits)
        saveCancelOffers(context, offers)
    }

    // ── Stocare „aștept număr de nopți” (tabele mod „zile”) ──────────────────────
    private fun loadNightsOffers(context: Context): JSONObject = try {
        JSONObject(bookingPrefs(context).getString(NIGHTS_OFFERS_KEY, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun saveNightsOffers(context: Context, offers: JSONObject) {
        bookingPrefs(context).edit().putString(NIGHTS_OFFERS_KEY, offers.toString()).apply()
    }

    private fun getNightsOffer(context: Context, senderDigits: String): JSONObject? {
        val o = loadNightsOffers(context).optJSONObject(senderDigits) ?: return null
        val ts = o.optLong("ts", 0L)
        if (System.currentTimeMillis() - ts > NIGHTS_OFFER_TTL_MIN * 60_000L) return null
        return o
    }

    private fun setNightsOffer(context: Context, senderDigits: String, boardId: String) {
        val offers = loadNightsOffers(context)
        val o = JSONObject()
        o.put("board", boardId)
        o.put("ts", System.currentTimeMillis())
        offers.put(senderDigits, o)
        saveNightsOffers(context, offers)
    }

    private fun clearNightsOffer(context: Context, senderDigits: String) {
        val offers = loadNightsOffers(context)
        offers.remove(senderDigits)
        saveNightsOffers(context, offers)
    }

    // ── Flux „liber” / „liber <tabel>” ──────────────────────────────────────────
    private fun startOffer(context: Context, sender: String, senderDigits: String, token: String?) {
        val boards = BookingSettings.loadBoards(context)
        Diag.i("startOffer: boards=${boards.map { it.id }} hasToken=${token != null}")
        if (boards.isEmpty()) {
            Diag.w("startOffer: no boards found, aborting")
            return
        }

        val board = matchBoard(boards, token)
        if (board == null) {
            // Nu trimitem lista tabelelor — numele interne nu sunt pentru oricine.
            sendSms(context, sender, "Nu am găsit tabelul \"$token\". Verifică numele și scrie din nou LIBER.")
            return
        }

        val settings = BookingSettings.loadSettings(context, board.id)
        Diag.i("startOffer: board=${board.id} settings.enabled=${settings.enabled} mode=${settings.mode}")
        if (!settings.enabled) {
            sendSms(context, sender, "Rezervările prin SMS nu sunt active pentru ${board.name}.")
            return
        }

        if (bookingLimitReached(context, sender, senderDigits)) return

        if (settings.mode == BoardMode.ZILE) {
            setNightsOffer(context, senderDigits, board.id)
            sendSms(context, sender, "Câte nopți? Răspunde cu un număr (1-30).")
            return
        }

        sendPage(context, sender, senderDigits, board, settings, offset = 0, isFirstPage = true)
    }

    // ── Confirmare număr de nopți → prima pagină de sejururi disponibile ────────
    private fun confirmNights(context: Context, sender: String, senderDigits: String, nights: Int) {
        val offer = getNightsOffer(context, senderDigits)
        if (offer == null) {
            sendSms(context, sender, "Nu am nicio căutare activă. Scrie LIBER pentru a rezerva un sejur.")
            return
        }
        val boardId = offer.optString("board", "")
        val board = BookingSettings.loadBoards(context).firstOrNull { it.id == boardId }
        clearNightsOffer(context, senderDigits)
        if (board == null) return
        if (nights < 1 || nights > 30) {
            sendSms(context, sender, "Număr de nopți invalid. Scrie LIBER și apoi un număr între 1 și 30.")
            return
        }
        val settings = BookingSettings.loadSettings(context, board.id)
        if (!settings.enabled) return
        sendZilePage(context, sender, senderDigits, board, settings, nights, offset = 0, isFirstPage = true)
    }

    private fun continueOffer(context: Context, sender: String, senderDigits: String) {
        val offer = getOffer(context, senderDigits)
        if (offer == null) {
            sendSms(context, sender, "Nu am nicio căutare activă. Scrie LIBER pentru a vedea orele libere.")
            return
        }
        val boardId = offer.optString("board", "")
        val board = BookingSettings.loadBoards(context).firstOrNull { it.id == boardId }
        if (board == null) {
            clearOffer(context, senderDigits)
            return
        }
        val settings = BookingSettings.loadSettings(context, board.id)
        if (!settings.enabled) {
            clearOffer(context, senderDigits)
            return
        }
        val nextOffset = offer.optInt("offset", 0) + PAGE_SIZE
        if (offer.has("nights")) {
            sendZilePage(context, sender, senderDigits, board, settings,
                offer.optInt("nights", 1), offset = nextOffset, isFirstPage = false)
        } else {
            sendPage(context, sender, senderDigits, board, settings, offset = nextOffset, isFirstPage = false)
        }
    }

    private fun sendPage(
        context: Context,
        sender: String,
        senderDigits: String,
        board: BoardInfo,
        settings: BoardBookingSettings,
        offset: Int,
        isFirstPage: Boolean,
    ) {
        val page = computePage(context, board, settings, offset)

        if (page.slots.isEmpty()) {
            val msg = if (isFirstPage) {
                "Nu sunt ore libere în perioada verificată pentru ${board.name}."
            } else {
                "Nu mai sunt alte ore libere pentru ${board.name}. Scrie LIBER pentru a relua căutarea."
            }
            sendSms(context, sender, msg)
            clearOffer(context, senderDigits)
            return
        }

        setOffer(context, senderDigits, board.id, offset)

        val today = LocalDateTime.now().toLocalDate()
        val lines = page.slots.mapIndexed { i, slot ->
            val dateSuffix = if (slot.start.toLocalDate() != today) " (${slot.start.format(DISPLAY_DATE_FMT)})" else ""
            "${i + 1}. ${slot.start.format(DISPLAY_TIME_FMT)}-${slot.end.format(DISPLAY_TIME_FMT)}$dateSuffix"
        }
        val footer = "Răspunde cu numărul opțiunii pentru rezervare" + (if (page.hasMore) ", sau NEXT pentru alte ore." else ".")
        sendSms(context, sender, "Ore libere ${board.name}:\n${lines.joinToString("\n")}\n$footer")
    }

    private class Page(val slots: List<FreeSlot>, val hasMore: Boolean)

    // Cerem un slot „în plus” față de pagina curentă (peekTarget), doar ca să
    // detectăm dacă mai există ore libere după cele afișate — fără asta,
    // FreeSlotCalculator s-ar opri exact la finalul paginii și nu am putea ști
    // niciodată dacă „NEXT” chiar mai aduce ceva.
    private fun computePage(context: Context, board: BoardInfo, settings: BoardBookingSettings, offset: Int): Page {
        val busy = BookingSettings.loadBusyIntervals(context, board.id, settings.durationMin)
        val peekTarget = minOf(offset + PAGE_SIZE + 1, MAX_TOTAL_SLOTS)
        val all = FreeSlotCalculator.compute(
            busy, settings, LocalDateTime.now(), HORIZON_DAYS,
            maxResults = peekTarget,
        )
        val slots = if (offset < all.size) all.subList(offset, minOf(offset + PAGE_SIZE, all.size)) else emptyList()
        val hasMore = all.size > offset + slots.size
        return Page(slots, hasMore)
    }

    // ── Pagină de sejururi disponibile (tabele „zile”) ──────────────────────────
    private fun sendZilePage(
        context: Context,
        sender: String,
        senderDigits: String,
        board: BoardInfo,
        settings: BoardBookingSettings,
        nights: Int,
        offset: Int,
        isFirstPage: Boolean,
    ) {
        val page = computeZilePage(context, board, settings, nights, offset)
        val nightsWord = if (nights == 1) "noapte" else "nopți"

        if (page.slots.isEmpty()) {
            val msg = if (isFirstPage) {
                "Nu sunt date disponibile pentru $nights $nightsWord la ${board.name} în perioada verificată."
            } else {
                "Nu mai sunt alte date disponibile pentru $nights $nightsWord la ${board.name}. Scrie LIBER pentru o căutare nouă."
            }
            sendSms(context, sender, msg)
            clearOffer(context, senderDigits)
            return
        }

        setOffer(context, senderDigits, board.id, offset, nights = nights)

        val lines = page.slots.mapIndexed { i, slot ->
            "${i + 1}. ${slot.start.format(DISPLAY_DATE_FMT)} → ${slot.end.format(DISPLAY_DATE_FMT)}"
        }
        val footer = "Răspunde cu numărul opțiunii pentru rezervare" + (if (page.hasMore) ", sau NEXT pentru alte date." else ".")
        sendSms(context, sender, "Disponibil $nights $nightsWord la ${board.name}:\n${lines.joinToString("\n")}\n$footer")
    }

    // Aceeași idee de „peek +1” ca la computePage, ca să știm dacă NEXT mai
    // aduce ceva.
    private fun computeZilePage(
        context: Context, board: BoardInfo, settings: BoardBookingSettings, nights: Int, offset: Int,
    ): Page {
        val busy = BookingSettings.loadZileBusyRanges(context, board.id)
        val peekTarget = minOf(offset + PAGE_SIZE + 1, MAX_TOTAL_SLOTS)
        val all = DayRangeCalculator.compute(
            busy, settings, LocalDateTime.now(), ZILE_HORIZON_DAYS, nights,
            maxResults = peekTarget,
        )
        val slots = if (offset < all.size) all.subList(offset, minOf(offset + PAGE_SIZE, all.size)) else emptyList()
        val hasMore = all.size > offset + slots.size
        return Page(slots, hasMore)
    }

    // ── Confirmare opțiune ───────────────────────────────────────────────────────
    private fun confirmOffer(context: Context, sender: String, senderDigits: String, choice: Int) {
        val offer = getOffer(context, senderDigits)
        if (offer == null) {
            sendSms(context, sender, "Nu am nicio ofertă activă pentru tine. Scrie LIBER pentru a vedea orele libere.")
            return
        }
        val boardId = offer.optString("board", "")
        val offset  = offer.optInt("offset", 0)
        val board = BookingSettings.loadBoards(context).firstOrNull { it.id == boardId }
        if (board == null) {
            clearOffer(context, senderDigits)
            return
        }
        val settings = BookingSettings.loadSettings(context, board.id)
        if (bookingLimitReached(context, sender, senderDigits)) {
            clearOffer(context, senderDigits)
            return
        }

        // Prezența cheii „nights” în ofertă marchează un tabel „zile” — alt
        // algoritm de calcul, alt format de confirmare, dar același mecanism
        // de ofertă/paginare/confirmare de mai jos.
        if (offer.has("nights")) {
            val nights = offer.optInt("nights", 1)
            val page = computeZilePage(context, board, settings, nights, offset)
            if (choice < 1 || choice > page.slots.size) {
                sendSms(
                    context, sender,
                    "Opțiune invalidă sau expirată. Răspunde cu un număr din ultimul mesaj primit, " +
                        "sau scrie LIBER pentru o căutare nouă."
                )
                return
            }
            val slot = page.slots[choice - 1]
            clearOffer(context, senderDigits)
            val bookingCreatedAt = LocalDateTime.now()
            val syncId = enqueueBooking(
                context, board.id, sender, slot.start, slot.end,
                createdAt = bookingCreatedAt, includeStartsAt = true,
            )

            // Termen de 24h pentru validarea plății — programat nativ (nu prin
            // Flutter), ca să funcționeze chiar dacă aplicația nu se deschide
            // deloc în acest interval. Dacă rezervarea nu e validată din
            // aplicație până atunci, ValidationDeadlineReceiver o anulează.
            val alarmId = AlarmScheduler.validationAlarmId(syncId)
            context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "flutter.validation_alarm_$alarmId",
                    JSONObject().put("board", board.id).put("sync", syncId).toString(),
                )
                .apply()
            AlarmScheduler.scheduleValidationAlarm(
                context, alarmId, bookingCreatedAt.plusHours(24)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            )

            val nightsWord = if (nights == 1) "noapte" else "nopți"
            val ibanLine = if (settings.iban.isNotBlank()) " în contul ${settings.iban}" else ""
            sendSms(
                context, sender,
                "Rezervarea ta la ${board.name}, ${slot.start.format(DISPLAY_DATE_FMT)} → " +
                    "${slot.end.format(DISPLAY_DATE_FMT)} ($nights $nightsWord), a fost salvată. " +
                    "Achită în maxim 24 de ore$ibanLine, altfel rezervarea va fi anulată automat. " +
                    "Te anunțăm prin SMS după confirmarea plății."
            )
            return
        }

        // Recalculăm oferta curentă din nou (nu memorăm sloturile în sine), ca să
        // reflectăm orice schimbare de la ultimul mesaj — inclusiv o eventuală
        // rezervare făcută între timp de alt client, pe același interval.
        val page = computePage(context, board, settings, offset)

        if (choice < 1 || choice > page.slots.size) {
            sendSms(
                context, sender,
                "Opțiune invalidă sau expirată. Răspunde cu un număr din ultimul mesaj primit, " +
                    "sau scrie LIBER pentru o căutare nouă."
            )
            return
        }

        val slot = page.slots[choice - 1]
        clearOffer(context, senderDigits)
        enqueueBooking(context, board.id, sender, slot.start, slot.end)

        val dateSuffix = if (slot.start.toLocalDate() != LocalDateTime.now().toLocalDate())
            " (${slot.start.format(DISPLAY_DATE_FMT)})" else ""
        sendSms(
            context, sender,
            "Programarea ta la ${board.name} pe ${slot.start.format(DISPLAY_TIME_FMT)}-" +
                "${slot.end.format(DISPLAY_TIME_FMT)}$dateSuffix a fost înregistrată. Te așteptăm!"
        )
    }

    // ── Flux „anuleaza” / „anulare” ─────────────────────────────────────────────
    private class CancelCandidate(
        val boardId: String,
        val syncId: String,
        val label: String,
        val start: LocalDateTime,
    )

    // Potrivire telefon-client identică (pe sufix de cifre) cu cea folosită la
    // excluderea partenerului de sincronizare — tolerează formate diferite
    // (+40712345678 / 0712345678 / cu spații).
    private fun phoneMatches(phones: List<String>, senderDigits: String): Boolean {
        for (raw in phones) {
            val itemDigits = digitsOnly(raw)
            if (itemDigits.isEmpty()) continue
            val minLen = minOf(senderDigits.length, itemDigits.length)
            if (minLen < 7) continue
            if (senderDigits.takeLast(minLen) == itemDigits.takeLast(minLen)) return true
        }
        return false
    }

    // Caută, pe toate tabelele, programările viitoare al căror telefon se
    // potrivește cu expeditorul — indiferent dacă au fost create de bot sau
    // adăugate manual din aplicație (orice înregistrare cu telefon completat).
    private fun findActiveBookings(context: Context, senderDigits: String): List<CancelCandidate> {
        val boards = BookingSettings.loadBoards(context)
        val now = LocalDateTime.now()
        val result = mutableListOf<CancelCandidate>()
        for (board in boards) {
            val settings = BookingSettings.loadSettings(context, board.id)
            val items = BookingSettings.loadBookedItems(context, board.id)
            for (item in items) {
                val end = item.expiresAt ?: continue
                if (end.isBefore(now)) continue
                if (!phoneMatches(item.phones, senderDigits)) continue
                val label: String
                val start: LocalDateTime
                if (settings.mode == BoardMode.ZILE) {
                    start = item.startsAt ?: end.minusDays(1)
                    label = "${board.name} ${start.format(DISPLAY_DATE_FMT)} → ${end.format(DISPLAY_DATE_FMT)}"
                } else {
                    start = end.minusMinutes(settings.durationMin.toLong())
                    val dateSuffix = if (start.toLocalDate() != now.toLocalDate())
                        " (${start.format(DISPLAY_DATE_FMT)})" else ""
                    label = "${board.name} ${start.format(DISPLAY_TIME_FMT)}-${end.format(DISPLAY_TIME_FMT)}$dateSuffix"
                }
                result.add(CancelCandidate(board.id, item.syncId, label, start))
            }
        }
        return result.sortedBy { it.start }
    }

    private fun startCancelFlow(context: Context, sender: String, senderDigits: String) {
        val candidates = findActiveBookings(context, senderDigits)
        when {
            candidates.isEmpty() ->
                sendSms(context, sender, "Nu am găsit nicio programare activă pe acest număr.")
            candidates.size == 1 -> {
                clearCancelOffer(context, senderDigits)
                cancelBooking(context, sender, candidates[0])
            }
            else -> {
                val arr = JSONArray()
                candidates.forEach { c ->
                    val o = JSONObject()
                    o.put("board", c.boardId)
                    o.put("sync", c.syncId)
                    o.put("label", c.label)
                    arr.put(o)
                }
                setCancelOffer(context, senderDigits, arr)
                val lines = candidates.mapIndexed { i, c -> "${i + 1}. ${c.label}" }
                sendSms(
                    context, sender,
                    "Ai mai multe programări active:\n${lines.joinToString("\n")}\n" +
                        "Răspunde cu numărul celei pe care vrei să o anulezi."
                )
            }
        }
    }

    private fun confirmCancel(context: Context, sender: String, senderDigits: String, choice: Int) {
        val offer = getCancelOffer(context, senderDigits)
        if (offer == null) {
            sendSms(context, sender, "Nu am nicio cerere de anulare activă. Scrie ANULEAZA pentru a o relua.")
            return
        }
        val candidates = offer.optJSONArray("candidates") ?: JSONArray()
        if (choice < 1 || choice > candidates.length()) {
            sendSms(
                context, sender,
                "Opțiune invalidă sau expirată. Răspunde cu un număr din ultimul mesaj primit, " +
                    "sau scrie ANULEAZA pentru o căutare nouă."
            )
            return
        }
        val chosen = candidates.getJSONObject(choice - 1)
        clearCancelOffer(context, senderDigits)
        cancelBooking(
            context, sender,
            CancelCandidate(
                boardId = chosen.optString("board"),
                syncId  = chosen.optString("sync"),
                label   = chosen.optString("label"),
                start   = LocalDateTime.now(), // nefolosit după alegere
            )
        )
    }

    // Scrie ștergerea în coada de sincronizare existentă („PEN:D:”) — Flutter o
    // aplică deja complet (șterge înregistrarea, recalculează sloturile libere,
    // reprogramează notificările), fără cod Dart suplimentar.
    private fun cancelBooking(context: Context, sender: String, candidate: CancelCandidate) {
        enqueueSyncMessage(context, candidate.boardId, "PEN:D:${candidate.syncId}")
        // Dacă exista o alarmă de „termen de validare” (mod zile) pentru
        // această rezervare, nu mai are rost — clientul tocmai a anulat-o el
        // însuși, nu are sens să mai primească și un SMS de „anulat pentru
        // neplată” peste câteva ore.
        AlarmScheduler.cancelValidationAlarm(context, AlarmScheduler.validationAlarmId(candidate.syncId))
        sendSms(context, sender, "Programarea ta ${candidate.label} a fost anulată.")
    }

    private fun generateSyncId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    // ── Scrie programarea confirmată în coada de sincronizare existentă ─────────
    // Reutilizează exact protocolul de sincronizare între tabele (mesaj „PEN:A:”)
    // — Flutter va prelua această „programare” la fel ca pe oricare alta primită
    // de la un dispozitiv pereche, cu logica deja existentă de merge/numerotare.
    // createdAt implicit = start, ca la comportamentul original de salon (nu
    // există alt câmp care să reprezinte „ora programării” în formatul compact
    // de sincronizare). Pe tabelele „zile”, apelantul trece createdAt = acum
    // (data reală de creare a rezervării) și includeStartsAt = true, pentru că
    // acolo `start` are propriul câmp dedicat („st” = check-in).
    // Întoarce syncId-ul generat, ca apelantul (ex. confirmOffer, pentru tabele
    // „zile”) să poată programa alarma de termen de validare pentru exact
    // această rezervare.
    private fun enqueueBooking(
        context: Context, boardId: String, sender: String,
        start: LocalDateTime, end: LocalDateTime,
        createdAt: LocalDateTime = start,
        includeStartsAt: Boolean = false,
    ): String {
        val syncId = generateSyncId()
        val item = JSONObject()
        item.put("s", syncId)
        item.put("n", sender)
        item.put("c", createdAt.format(ISO_SHORT))
        item.put("e", end.format(ISO_SHORT))
        item.put("p1", sender)
        // Rezervare făcută de client prin bot: telefonul e al clientului, nu
        // un destinatar de alerte — fără SMS „EXPIRAT” la final.
        item.put("b", true)
        if (includeStartsAt) item.put("st", start.format(ISO_SHORT))

        enqueueSyncMessage(context, boardId, "PEN:A:$item")
        return syncId
    }

    // Scrie un mesaj în coada de sincronizare existentă (folosită atât pentru
    // rezervări noi „PEN:A:”, cât și pentru anulări „PEN:D:”).
    private fun enqueueSyncMessage(context: Context, boardId: String, msg: String) =
        SmsSyncReceiver.enqueue(context, boardId, msg)

    private fun sendSms(context: Context, phone: String, message: String) {
        SmsSender.send(context, phone, message)
    }
}
