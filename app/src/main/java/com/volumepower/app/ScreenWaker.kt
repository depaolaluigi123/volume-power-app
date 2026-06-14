package com.volumepower.app

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Risveglio schermo condiviso tra ForegroundService (route MediaSession a schermo spento)
 * e AccessibilityService (route onKeyEvent a schermo acceso/sbloccato).
 *
 * Combina tre tecniche perche' nessuna funziona da sola su tutte le ROM (MIUI, EMUI, OneUI):
 * 1) Activity trasparente con setTurnScreenOn(true) / setShowWhenLocked(true): metodo
 *    raccomandato da Android 8.1+.
 * 2) WakeLock SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP: API deprecata ma ancora
 *    indispensabile su Xiaomi/Huawei per forzare effettivamente l'accensione del pannello.
 * 3) Auto-release dopo pochi secondi per non bloccare lo standby.
 */
object ScreenWaker {

    private const val TAG = "VolumePowerApp"
    private const val WAKE_TIMEOUT_MS = 5_000L

    fun wakeScreen(context: Context) {
        Log.d(TAG, "ScreenWaker.wakeScreen()")
        acquireWakeLock(context)
        WakeActivity.start(context)
    }

    private fun acquireWakeLock(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "VolumePowerApp::ScreenWaker"
            )
            wl.acquire(WAKE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire fallito", e)
        }
    }
}
