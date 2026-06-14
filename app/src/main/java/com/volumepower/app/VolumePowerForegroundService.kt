package com.volumepower.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat

/**
 * Servizio in primo piano che mantiene il processo attivo e, cosa piu' importante,
 * fornisce il canale di consegna degli eventi Volume+ a schermo SPENTO.
 *
 * Su Xiaomi (MIUI) e Huawei (EMUI) l'AccessibilityService NON riceve onKeyEvent
 * quando lo schermo e' spento: il sistema instrada i tasti volume direttamente
 * all'audio HAL, bypassando l'input dispatcher accessibility-aware.
 *
 * Per intercettarli usiamo una MediaSession attiva con un VolumeProviderCompat
 * REMOTE: quando il sistema deve regolare il volume, chiama il nostro
 * onAdjustVolume() invece di alzare/abbassare lo stream audio. Questo funziona
 * anche con schermo spento e su lock screen.
 *
 * Per assicurarci che la nostra sessione sia "quella attualmente in riproduzione"
 * (e quindi venga preferita per il routing dei tasti volume), avviamo un
 * AudioTrack silenzioso quando lo schermo si spegne. Volume zero --> inudibile.
 */
class VolumePowerForegroundService : Service() {

    private var screenOffWakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var silentTrack: AudioTrack? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> onScreenOn()
            }
        }
    }

    private val volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_ABSOLUTE,
        VOLUME_MAX,
        VOLUME_MAX / 2
    ) {
        override fun onAdjustVolume(direction: Int) {
            when (direction) {
                AudioManager.ADJUST_RAISE -> {
                    Log.d(TAG, "VolumeProvider: ADJUST_RAISE -> wake/lock")
                    handleVolumeUp()
                }
                AudioManager.ADJUST_LOWER -> {
                    Log.d(TAG, "VolumeProvider: ADJUST_LOWER -> system volume down")
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                // ADJUST_SAME / 0: nessuna azione.
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerScreenReceiver()
        setupMediaSession()
    }

    
    // START_STICKY è una costante di Service (Android). Dice al sistema: se il servizio 
    // viene terminato per mancanza di memoria, può essere riavviato con un Intent nullo, 
    // finché c’è ancora qualcosa che lo richiede (qui l’avvio da MainActivity).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        stopSilentAudio()
        releaseScreenOffWakeLock()
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // Gia' deregistrato.
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun handleVolumeUp() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            // Lo schermo e' acceso e sbloccato: l'AccessibilityService
            // dovrebbe aver gia' gestito il blocco. Se siamo qui significa
            // che l'accessibilita' non e' attiva, percio' come fallback
            // riavviamo l'attivita' che si limita a "wake" (no-op se schermo gia' acceso).
            // In ogni caso evitiamo di alzare il volume.
            return
        }
        ScreenWaker.wakeScreen(this)
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun setupMediaSession() {
        val session = MediaSessionCompat(this, "VolumePowerSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_PAUSE
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
            setPlaybackToRemote(volumeProvider)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = extractKeyEvent(mediaButtonIntent)
                    if (event != null &&
                        event.action == KeyEvent.ACTION_DOWN &&
                        event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    ) {
                        Log.d(TAG, "MediaSession.onMediaButtonEvent: VOLUME_UP")
                        handleVolumeUp()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            isActive = true
        }
        mediaSession = session
    }

    @Suppress("DEPRECATION")
    private fun extractKeyEvent(intent: Intent): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun onScreenOff() {
        Log.d(TAG, "ACTION_SCREEN_OFF")
        acquireScreenOffWakeLock()
        startSilentAudio()
        // Garantiamo che la sessione sia ATTIVA + PLAYING anche se nel frattempo
        // un'altra app multimediale avesse rubato il focus: il sistema instrada
        // i tasti volume alla sessione "playing" piu' recente.
        mediaSession?.let {
            it.isActive = true
            it.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_PAUSE
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
        }
    }

    private fun onScreenOn() {
        Log.d(TAG, "ACTION_SCREEN_ON / USER_PRESENT")
        releaseScreenOffWakeLock()
        stopSilentAudio()
        // Lasciamo la sessione attiva: vogliamo che VolumeProvider continui a
        // ricevere eventi anche a schermo acceso (l'AccessibilityService li
        // intercetta prima quando attivo, ma cosi' abbiamo un fallback).
    }

    /**
     * AudioTrack silenzioso (zero PCM) caricato in modalita' STATIC e messo in
     * loop infinito: l'overhead CPU e' praticamente nullo perche' i campioni
     * vengono ciclati dal sottosistema audio senza intervento del processo.
     *
     * Lo scopo NON e' suonare: e' segnalare al sistema che la nostra MediaSession
     * sta "riproducendo media" cosi' che i tasti volume vengano instradati al
     * nostro VolumeProvider invece che all'audio HAL di sistema.
     */
    private fun startSilentAudio() {
        if (silentTrack != null) return
        val sampleRate = 22050
        val channelCfg = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val frameSize = 2 // mono 16-bit = 2 byte/frame
        val frames = sampleRate // 1 secondo
        val bufferBytes = frames * frameSize

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelCfg)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack creation failed", e)
            return
        }

        val silence = ByteArray(bufferBytes)
        track.write(silence, 0, silence.size)
        track.setLoopPoints(0, frames, -1)
        try {
            track.setVolume(0f)
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            track.play()
            silentTrack = track
            Log.d(TAG, "Silent audio started")
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack play() failed", e)
            track.release()
        }
    }

    private fun stopSilentAudio() {
        silentTrack?.let { track ->
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            Log.d(TAG, "Silent audio stopped")
        }
        silentTrack = null
    }

    /**
     * Wake lock parziale a schermo spento: su Xiaomi/Redmi evita che il processo
     * vada in deep sleep e quindi non riceva piu' callback dal MediaSession.
     */
    private fun acquireScreenOffWakeLock() {
        releaseScreenOffWakeLock()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        screenOffWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VolumePowerApp::ScreenOff"
        ).apply {
            acquire(SCREEN_OFF_WAKE_LOCK_MS)
        }
    }

    private fun releaseScreenOffWakeLock() {
        screenOffWakeLock?.let {
            if (it.isHeld) it.release()
        }
        screenOffWakeLock = null
    }

    private fun buildNotification(): Notification {
        createChannelIfNeeded()
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Volume Power App attivo")
            .setContentText(
                "Volume su --> blocca/sveglia schermo. Tocca per aprire le impostazioni di accessibilità."
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servizio Volume Power App",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene attiva la rimappatura del tasto volume"
        }
        manager.createNotificationChannel(channel)
    }


    // È il punto d’ingresso per avviare il servizio da fuori, senza creare un’istanza manualmente. Lo chiama MainActivity
    companion object {
        private const val TAG = "VolumePowerApp"
        private const val CHANNEL_ID = "volume_power_service"
        private const val NOTIFICATION_ID = 1
        private const val SCREEN_OFF_WAKE_LOCK_MS = 30 * 60 * 1000L
        private const val VOLUME_MAX = 100

        fun start(context: Context) {
            val intent = Intent(context, VolumePowerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
