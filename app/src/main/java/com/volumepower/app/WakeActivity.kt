package com.volumepower.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity trasparente che si limita ad accendere lo schermo.
 *
 * Usa sia l'API moderna (setShowWhenLocked / setTurnScreenOn, API 27+) sia i
 * vecchi flag della Window: alcune ROM (MIUI in particolare) richiedono
 * entrambi per accendere effettivamente il pannello dal lock screen.
 *
 * Si chiude da sola dopo un breve delay: ha solo il compito di "far apparire"
 * un'activity nel momento in cui il pannello deve accendersi; il lock screen
 * rimane in primo piano subito dopo, come desiderato dal Test C.
 */
class WakeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "WakeActivity.onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        handler.postDelayed({ finish() }, FINISH_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VolumePowerApp"
        private const val FINISH_DELAY_MS = 600L

        fun start(context: Context) {
            try {
                context.startActivity(
                    Intent(context, WakeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "WakeActivity.start fallito", e)
            }
        }
    }
}
