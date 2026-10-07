package com.example.management_app

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Pornirea aplicației în fundal pe telefoanele Xiaomi (MIUI / HyperOS).
 *
 * Botul SMS și sincronizarea rulează nativ, fără ca aplicația să fie
 * deschisă — dar pe Xiaomi, după ce aplicația e glisată din „Recente”,
 * sistemul nu o mai pornește pentru un SMS primit decât dacă are permisiunea
 * „Pornire automată” (Autostart). Implicit e refuzată, iar Android nu oferă
 * o cerere standard pentru ea — utilizatorul trebuie trimis în ecranul MIUI.
 */
object BackgroundStart {

    // Operația AppOps internă MIUI pentru „Pornire automată” (nu e publică).
    private const val OP_MIUI_AUTOSTART = 10008

    const val ALLOWED = "allowed"
    const val DENIED = "denied"
    const val UNKNOWN = "unknown"
    const val NOT_APPLICABLE = "notApplicable"

    fun isXiaomi(manufacturer: String = Build.MANUFACTURER): Boolean =
        manufacturer.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Starea „Pornire automată”: allowed / denied / unknown, sau notApplicable în afara Xiaomi. */
    fun autostartState(context: Context): String {
        if (!isXiaomi()) return NOT_APPLICABLE
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, String::class.java,
            )
            val mode = method.invoke(
                ops, OP_MIUI_AUTOSTART, context.applicationInfo.uid, context.packageName,
            ) as Int
            if (mode == AppOpsManager.MODE_ALLOWED) ALLOWED else DENIED
        } catch (e: Exception) {
            Diag.e("BackgroundStart: autostart state unavailable", e)
            UNKNOWN
        }
    }

    /** Deschide ecranul „Pornire automată”; dacă nu există, pagina aplicației din Setări. */
    fun openAutostartSettings(activity: Activity): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", activity.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)),
        )
        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Ecranul nu există pe această versiune — încercăm următorul.
            }
        }
        return false
    }
}
