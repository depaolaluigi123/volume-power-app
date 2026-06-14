package com.volumepower.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Intercetta Volume su quando lo schermo e' acceso/sbloccato: in quel caso
 * Android consegna correttamente onKeyEvent al servizio di accessibilita'
 * e possiamo eseguire performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN).
 *
 * NB: a schermo SPENTO molte ROM (MIUI, EMUI, OneUI) NON consegnano l'evento
 * tasti al servizio di accessibilita': il routing del volume viene fatto a
 * livello inferiore. Per quel caso il risveglio e' gestito dal MediaSession
 * dichiarato in [VolumePowerForegroundService].
 */
class VolumePowerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastToggleAtMs = 0L
    private var wakeOverlay: View? = null
    private var windowManager: WindowManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Non necessario per la rimappatura tasti; richiesto dall'API.
    }

    override fun onInterrupt() {
        removeWakeOverlay()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            handleVolumeUpDown()
        }
        // Consuma DOWN e UP per non alterare il volume e per stream coerente (MIUI).
        return true
    }

    private fun handleVolumeUpDown() {
        val now = System.currentTimeMillis()
        if (now - lastToggleAtMs < DEBOUNCE_MS) return
        // Il debounce è un filtro temporale: se premi Volume + più volte 
        // in rapida successione, l’app esegue una sola azione (blocco o 
        // sveglia), non una per ogni pressione.
        // DEBOUNCE_MS = 400 (ms) significa: ignora una nuova pressione se 
        // l’ultima azione valida è stata meno di 400 millisecondi fa.
        // DEBOUNCE_MS è dichiarata di sotto, nel companion object.

        lastToggleAtMs = now

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (shouldLockScreen(powerManager)) {
            Log.d(TAG, "Volume+ -> blocco")
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            Log.d(TAG, "Volume+ -> sveglia (via AccessibilityService)")
            wakeScreen()
        }
    }

    /** Schermo acceso e interattivo -> blocco. */
    private fun shouldLockScreen(powerManager: PowerManager): Boolean {
        if (!powerManager.isInteractive) return false
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY).state == Display.STATE_ON
    }

    /**
     * Risveglio dal contesto AccessibilityService: oltre al normale ScreenWaker,
     * possiamo aggiungere un overlay TYPE_ACCESSIBILITY_OVERLAY (privilegio
     * esclusivo dei servizi di accessibilita') che e' il modo piu' aggressivo
     * per forzare l'accensione del pannello su alcune ROM.
     */
    private fun wakeScreen() {
        ScreenWaker.wakeScreen(this)
        showAccessibilityWakeOverlay()
        handler.postDelayed({ removeWakeOverlay() }, OVERLAY_TIMEOUT_MS)
    }

    private fun showAccessibilityWakeOverlay() {
        removeWakeOverlay()
        try {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = manager
            val overlay = View(this)
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            manager.addView(overlay, params)
            wakeOverlay = overlay
        } catch (e: Exception) {
            Log.w(TAG, "Overlay accessibilita' non disponibile", e)
        }
    }

    private fun removeWakeOverlay() {
        val overlay = wakeOverlay ?: return
        try {
            windowManager?.removeView(overlay)
        } catch (_: IllegalArgumentException) {
            // Overlay gia' rimosso.
        }
        wakeOverlay = null
        windowManager = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeWakeOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VolumePowerApp"
        private const val DEBOUNCE_MS = 400L
        private const val OVERLAY_TIMEOUT_MS = 1_500L
    }
}
