package com.example.management_app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFormatTest {

    // Forma reală în care plugin-ul Dart shared_preferences scrie pe Android.
    private val prefs: Map<String, Any?> = mapOf(
        "flutter.management_boards" to """[{"id":"b1","name":"Salon"}]""",
        "flutter.management_items_b1" to """[{"number":1,"name":"Ana"}]""",
        "flutter.management_next_number_b1" to 2L,
        "flutter.booking_enabled_b1" to true,
        "flutter.sync_partner_phone_b1" to "0722000111",
        "flutter.trial_start_date" to "2026-09-01T10:00:00.000",
        "flutter.notif_alarm_12" to """{"title":"t","body":"b"}""",
        "flutter.some_float" to 1.5f,
        "flutter.legacy_set" to setOf("b", "a"),
        "flutter.license_expiry_warned_on" to "2026-9-24",
        "not_flutter_key" to "x",
    )
    private val identity = BackupFormat.Identity("organizator-1", """{"payload":{}}""")

    private fun roundTrip(): BackupFormat.Backup =
        BackupFormat.decode(BackupFormat.encode(prefs, identity, 1234L))

    private fun expectInvalid(content: String) {
        try {
            BackupFormat.decode(content)
            fail("trebuia respins")
        } catch (_: BackupFormat.InvalidBackupException) {
        }
    }

    @Test
    fun `datele si tipurile se pastreaza exact`() {
        val b = roundTrip()
        assertEquals(1234L, b.createdAt)
        assertEquals(prefs["flutter.management_items_b1"], b.prefs["flutter.management_items_b1"])
        assertEquals(2L, b.prefs["flutter.management_next_number_b1"])
        assertTrue(b.prefs["flutter.management_next_number_b1"] is Long)
        assertEquals(true, b.prefs["flutter.booking_enabled_b1"])
        assertEquals(1.5f, b.prefs["flutter.some_float"])
        assertEquals(setOf("a", "b"), b.prefs["flutter.legacy_set"])
        assertEquals(identity, b.identity)
    }

    @Test
    fun `cheile tranzitorii si straine nu intra in backup`() {
        val b = roundTrip()
        assertFalse(b.prefs.containsKey("flutter.license_expiry_warned_on"))
        assertFalse(b.prefs.containsKey("not_flutter_key"))
        // Payload-urile alarmelor intră — AlarmRescheduler le rearmează după restore.
        assertTrue(b.prefs.containsKey("flutter.notif_alarm_12"))
    }

    @Test
    fun `backup fara identitate`() {
        val content = BackupFormat.encode(prefs, BackupFormat.Identity(null, null), 1L)
        val b = BackupFormat.decode(content)
        assertNull(b.identity.businessId)
        assertNull(b.identity.licenseJson)
    }

    @Test
    fun `fisier modificat e respins prin checksum`() {
        val root = JSONObject(BackupFormat.encode(prefs, identity, 1L))
        root.put("data", root.getString("data").replace("Ana", "Ion"))
        expectInvalid(root.toString())
    }

    @Test
    fun `fisiere straine sau corupte sunt respinse`() {
        expectInvalid("nu e json")
        expectInvalid("SQLite format 3\u0000")
        expectInvalid("""{"app":"fidelio","formatVersion":1}""")
        expectInvalid("{}")
    }

    @Test
    fun `versiune de format mai noua e respinsa`() {
        val root = JSONObject(BackupFormat.encode(prefs, identity, 1L))
        root.put("formatVersion", BackupFormat.FORMAT_VERSION + 1)
        expectInvalid(root.toString())
    }

    @Test
    fun `restore inlocuieste datele si pastreaza partenerii actuali`() {
        val current = mapOf(
            "flutter.management_items_b1" to "[]",
            "flutter.management_items_b2" to "[]", // tabel care nu e în backup
            "flutter.sync_partner_phone_b1" to "0733999888",
            "flutter.license_expiry_warned_on" to "2026-9-24",
        )
        val merged = BackupFormat.mergeForRestore(current, roundTrip().prefs, keepSyncPartners = true)
        assertEquals(prefs["flutter.management_items_b1"], merged["flutter.management_items_b1"])
        assertFalse(merged.containsKey("flutter.management_items_b2"))
        assertEquals("0733999888", merged["flutter.sync_partner_phone_b1"])
        assertEquals("2026-9-24", merged["flutter.license_expiry_warned_on"])
    }

    @Test
    fun `restore cu partenerii din backup`() {
        val current = mapOf("flutter.sync_partner_phone_b1" to "0733999888")
        val merged = BackupFormat.mergeForRestore(current, roundTrip().prefs, keepSyncPartners = false)
        assertEquals("0722000111", merged["flutter.sync_partner_phone_b1"])
    }

    @Test
    fun `partenerii actuali absenti din backup raman cand se pastreaza`() {
        val current = mapOf("flutter.sync_partner_phone_b3" to "0744")
        val backup = mapOf<String, Any?>("flutter.sync_partner_phone_b1" to "0722")
        val merged = BackupFormat.mergeForRestore(current, backup, keepSyncPartners = true)
        assertEquals("0744", merged["flutter.sync_partner_phone_b3"])
        assertFalse(merged.containsKey("flutter.sync_partner_phone_b1"))
    }

    @Test
    fun `trial-ul nu se prelungeste prin restore`() {
        val older = "2026-01-01T00:00:00.000"
        val newer = "2026-09-01T10:00:00.000"
        val m1 = BackupFormat.mergeForRestore(
            mapOf("flutter.trial_start_date" to older),
            mapOf("flutter.trial_start_date" to newer), true)
        assertEquals(older, m1["flutter.trial_start_date"])
        val m2 = BackupFormat.mergeForRestore(
            mapOf("flutter.trial_start_date" to newer),
            mapOf("flutter.trial_start_date" to older), true)
        assertEquals(older, m2["flutter.trial_start_date"])
        val m3 = BackupFormat.mergeForRestore(
            mapOf("flutter.trial_start_date" to newer), emptyMap(), true)
        assertEquals(newer, m3["flutter.trial_start_date"])
    }

    @Test
    fun `identitatea se restaureaza fara a pierde o licenta activa`() {
        assertTrue(BackupFormat.shouldRestoreIdentity(currentLicenseActive = false, backupLicenseActive = false))
        assertTrue(BackupFormat.shouldRestoreIdentity(currentLicenseActive = false, backupLicenseActive = true))
        assertTrue(BackupFormat.shouldRestoreIdentity(currentLicenseActive = true, backupLicenseActive = true))
        assertFalse(BackupFormat.shouldRestoreIdentity(currentLicenseActive = true, backupLicenseActive = false))
    }

    @Test
    fun `retentie - se sterg doar backup-urile automate cele mai vechi`() {
        val names = listOf(
            "pensiune_auto_20260101_000000.penbackup",
            "pensiune_auto_20260103_000000.penbackup",
            "pensiune_auto_20260102_000000.penbackup",
            "pensiune_20250101_120000.penbackup", // manual — nu se atinge
            "pensiune_auto_20250101_000000.txt",  // alt tip — nu se atinge
        )
        assertEquals(
            listOf("pensiune_auto_20260101_000000.penbackup"),
            BackupFormat.autoBackupsToDelete(names, keep = 2),
        )
        assertTrue(BackupFormat.autoBackupsToDelete(names, keep = 14).isEmpty())
    }

    @Test
    fun `codul de imperechere urmeaza regula partenerilor la restore`() {
        val current = mapOf("flutter.sync_secret_b1" to "CURENT12")
        val backup = mapOf<String, Any?>("flutter.sync_secret_b1" to "BACKUP12")
        assertEquals("CURENT12", BackupFormat.mergeForRestore(current, backup, keepSyncPartners = true)["flutter.sync_secret_b1"])
        assertEquals("BACKUP12", BackupFormat.mergeForRestore(current, backup, keepSyncPartners = false)["flutter.sync_secret_b1"])
    }
}
