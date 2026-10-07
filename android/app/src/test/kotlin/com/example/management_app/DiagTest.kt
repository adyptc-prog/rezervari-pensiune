package com.example.management_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagTest {

    @After
    fun tearDown() {
        Diag.verbose = BuildConfig.DEBUG
        SmsSender.testSink = null
    }

    private fun logText() = ShadowLog.getLogsForTag("PenDiag").joinToString("\n") { it.msg }

    @Test
    fun `numerele de telefon sunt mascate`() {
        assertEquals("***678", Diag.mask("+40 712 345 678"))
        assertEquals("***", Diag.mask("12"))
        assertEquals("***", Diag.mask(null))
    }

    @Test
    fun `in release mesajele informative nu apar, erorile da`() {
        Diag.verbose = false
        Diag.i("info")
        Diag.w("avertisment")
        Diag.e("eroare")
        assertEquals("eroare", logText())
    }

    @Test
    fun `botul nu scrie in jurnal numarul complet sau textul SMS-ului`() {
        Diag.verbose = true
        SmsSender.testSink = { _, _ -> }
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.management_boards",
                JSONArray().put(JSONObject().put("id", "b1").put("name", "Salon")).toString())
            .putBoolean("flutter.booking_enabled_b1", true)
            .commit()

        ClientBookingReceiver().handleMessage(context, "+40712345678", "liber secretul-meu")
        SmsSender.send(context, "+40712345678", "text")

        val log = logText()
        assertTrue(log.isNotEmpty())
        assertFalse(log.contains("712345678"))
        assertFalse(log.contains("secretul"))
    }
}
