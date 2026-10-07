package com.example.management_app

import android.util.Log

/**
 * Jurnalul de diagnostic al aplicației ("PenDiag").
 *
 * - Mesajele informative/avertismentele apar doar în build-ul debug.
 * - Erorile apar și în release (necesare la depanare), dar fără date
 *   personale: numerele de telefon se trec prin [mask], iar conținutul
 *   SMS-urilor nu se scrie deloc.
 */
object Diag {
    private const val TAG = "PenDiag"

    // Doar testele îl schimbă.
    @Volatile
    internal var verbose: Boolean = BuildConfig.DEBUG

    fun i(msg: String) {
        if (verbose) Log.i(TAG, msg)
    }

    fun w(msg: String) {
        if (verbose) Log.w(TAG, msg)
    }

    fun e(msg: String, error: Throwable? = null) {
        Log.e(TAG, msg, error)
    }

    /** „+40712345678” → „***678” — destul cât să deosebești două numere. */
    fun mask(phone: String?): String {
        val digits = phone.orEmpty().filter { it.isDigit() }
        return if (digits.length <= 3) "***" else "***${digits.takeLast(3)}"
    }
}
