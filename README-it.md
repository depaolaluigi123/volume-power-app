# Volume Power App

**Il pulsante di accensione del tuo telefono si è rotto?** Succede più spesso di quanto si pensi: dopo anni di utilizzo, una caduta o semplice usura, il tasto Power può smettere di rispondere. La riparazione in assistenza costa spesso quanto un telefono usato, nel frattempo resti con un dispositivo ancora perfettamente utilizzabile ma senza un modo comodo per bloccare lo schermo o risvegliarlo.

**Volume Power App** propone una soluzione software: rimappa il tasto **Volume su** in modo che faccia il lavoro del Power:
- Schermo acceso --> blocca il telefono; 
- Schermo spento --> risveglia il telefono. 
Non è un sostituto identico al tasto hardware (servono permessi di accessibilità, una notifica persistente e qualche compromesso sul volume, vedi [Limitazioni](#limitazioni-importanti-android)), ma per chi ha il Power fuori uso è spesso sufficiente per andare avanti fino alla riparazione o al cambio telefono.

In sintesi tecnica: applicazione Android che avvia un servizio in background e si chiude subito; da quel momento **Volume +** non alza più il volume ma gestisce blocco e sveglia dello schermo.

## Cosa fa l’app (in sintesi)

1. Tocchi l’icona **Volume Power App** una volta.
2. Parte un **servizio in primo piano** (notifica persistente) e l’activity si chiude: non resti “dentro” l’app.
3. Dopo la prima configurazione, il tasto **Volume +** non alza più il volume ma:
   - **schermo acceso** → blocca il dispositivo (come “stand-by” / schermo spento con blocco);
   - **schermo spento** → prova ad accendere lo schermo (wake lock).

Il pulsante **Volume giù** non viene modificato e continua ad abbassare il volume normalmente.

> **Nota:** se è in riproduzione un audio multimediale in background (es. musica da Spotify, YouTube Music o un podcast), **il risveglio con Volume + non funziona**: Android instrada i tasti volume alla sessione multimediale attiva invece che a questa app.

## Architettura

L’app è composta da quattro componenti principali e un helper:

```
┌─────────────────┐
│   MainActivity  │  Avvio: permessi notifica → avvia FGS → (se serve) impostazioni accessibilità → finish()
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────┐
│ VolumePowerForegroundService    │  Notifica ongoing + MediaSession + VolumeProvider + AudioTrack silenzioso
└──┬──────────────────────────────┘
   │   ▲ instrada Volume +/− a schermo SPENTO (via MediaSession)
   │   │
   │   └─► ScreenWaker.wakeScreen(ctx) ◄──┐
   │                                       │
   ▼                                       │
┌──────────────────────────────────┐       │
│ VolumePowerAccessibilityService  │───────┘  onKeyEvent(): intercetta Volume + a schermo ACCESO
└──────────────────────────────────┘
   │
   ▼
┌──────────────┐
│ WakeActivity │  Activity trasparente con setTurnScreenOn(true) / setShowWhenLocked(true)
└──────────────┘
```

### Perché due percorsi per Volume + (accessibility + MediaSession)

Android consegna gli eventi tasto in due modi diversi a seconda dello stato dello schermo:

- **Schermo acceso/sbloccato:** l’AccessibilityService riceve `onKeyEvent()` ed esegue `GLOBAL_ACTION_LOCK_SCREEN`. Funziona ovunque.
- **Schermo spento (lock screen):** su MIUI/EMUI/OneUI il sistema **non** chiama `onKeyEvent()` dell’accessibility service: il tasto viene gestito a livello inferiore (PhoneWindowManager) e instradato direttamente al sottosistema audio. Per intercettarlo l’app registra una `MediaSessionCompat` attiva + `VolumeProviderCompat` REMOTE. Il sistema delega il controllo del volume al nostro `onAdjustVolume()`. Per essere "la session musicale corrente" e vincere il routing, all’`ACTION_SCREEN_OFF` viene avviato un `AudioTrack` con campioni PCM zero (silenzio reale, volume 0, modalità `MODE_STATIC` in loop infinito = costo CPU trascurabile).

### MainActivity

- Activity trasparente e `excludeFromRecents`: l’utente non la vede nel multitasking dopo l’avvio.
- Su Android 13+ chiede il permesso **Notifiche** (necessario per il servizio in primo piano).
- Avvia `VolumePowerForegroundService` e termina con `finish()`.
- Se il servizio di accessibilità non è ancora attivo, mostra un messaggio e apre **Impostazioni → Accessibilità**.

### VolumePowerForegroundService

- Tipo: `foregroundServiceType="mediaPlayback|specialUse"` (Android 14+), con sottotipo dichiarato nel manifest.
- Mostra una notifica **non rimovibile** finché il servizio è attivo (tap → impostazioni accessibilità).
- `START_STICKY`: se il sistema uccide il processo, Android può riavviare il servizio.
- Registra una `MediaSessionCompat` attiva con stato `PLAYING` e `setPlaybackToRemote(VolumeProviderCompat)`: quando il sistema deve regolare il volume chiama il nostro `onAdjustVolume(direction)`.
  - `ADJUST_RAISE` → `ScreenWaker.wakeScreen()` (volume + non alza mai il volume).
  - `ADJUST_LOWER` → delega ad `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_LOWER, FLAG_SHOW_UI)` (volume − funziona normalmente).
- All’`ACTION_SCREEN_OFF`: acquisisce un `PARTIAL_WAKE_LOCK`, riattiva la session e avvia un `AudioTrack` silenzioso in `MODE_STATIC` loop infinito. Allo `ACTION_SCREEN_ON` rilascia tutto.

### VolumePowerAccessibilityService

È il cuore della rimappatura. Android **non permette** alle app normali di leggere o bloccare i tasti hardware del volume senza un **AccessibilityService** con filtro tasti:

- `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents="true"` in `res/xml/accessibility_service_config.xml`.
- In `onKeyEvent()`:
  - se il tasto è **Volume su**, restituisce `true` (evento **consumato** → il volume non cambia);
  - su `ACTION_DOWN`, dopo un **debounce** di 400 ms:
    - `PowerManager.isInteractive == true` → `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`;
    - altrimenti → `WakeLock` con `ACQUIRE_CAUSES_WAKEUP` per accendere lo schermo.

#### Debounce (400 ms)

Il debounce evita che pressioni ravvicinate del tasto Volume su eseguano più azioni di fila (blocco + sveglia, o doppio blocco) per rimbalzo del tasto o eventi duplicati.

- **Dove è dichiarato:** costante `DEBOUNCE_MS = 400L` nel `companion object` di `VolumePowerAccessibilityService.kt`.
- **Dove è usato:** in `handleVolumeUpPress()`: se dall’ultima azione valida sono passati meno di 400 ms, la pressione viene ignorata (`return`); altrimenti si aggiorna `lastToggleAtMs` e si esegue blocco o sveglia.

```kotlin
// VolumePowerAccessibilityService.kt
private const val DEBOUNCE_MS = 400L

if (now - lastToggleAtMs < DEBOUNCE_MS) return
lastToggleAtMs = now
```

Per cambiare la sensibilità, modifica `DEBOUNCE_MS` (valori più alti = meno reattivo ma più stabile).

## Permessi

| Permesso | Motivo |
|----------|--------|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Servizio in background visibile all’utente; il tipo `mediaPlayback` è richiesto da Android 14 quando il servizio riproduce audio (anche silenzioso) |
| `POST_NOTIFICATIONS` | Notifica del servizio (Android 13+) |
| `WAKE_LOCK` | Accensione schermo quando è spento |
| `TURN_SCREEN_ON` | Permesso per accendere lo schermo da Android 14 |
| `MODIFY_AUDIO_SETTINGS` | Necessario per abbassare il volume system stream da `VolumeProvider.onAdjustVolume(ADJUST_LOWER)` |

Non sono richiesti root né permessi di sistema firmati.

## Limitazioni importanti (Android)

Questa non è una copia perfetta del tasto **Power** a livello hardware/firmware:

1. **Servizio di accessibilità obbligatorio**  
   L’utente deve abilitare manualmente «Volume Power App – tasto volume» in Accessibilità. Senza questo passaggio la rimappatura non funziona.

2. **Blocco schermo ≠ spegnimento completo**  
   `GLOBAL_ACTION_LOCK_SCREEN` blocca il dispositivo (PIN/biometria se configurati), come un lock screen. Non spegne la CPU come un vero power-off!

3. **Sveglia con schermo spento — non con audio multimediale attivo**  
   Su molte ROM (MIUI, EMUI, OneUI) gli eventi tasto a schermo spento non arrivano all’AccessibilityService. L’app aggira il problema con una `MediaSessionCompat` + `VolumeProviderCompat` REMOTE + `AudioTrack` silenzioso che assicura che il sistema instradi `onAdjustVolume()` alla nostra app anche dal lock screen. **Tuttavia, se un’altra app sta riproducendo audio multimediale** (Spotify, YouTube Music, un podcast, un video in background, ecc.), Android dà priorità a quella app per i tasti volume: **Volume + non risveglia lo schermo** e agisce solo sulla riproduzione dell’altra app.

4. **Volume su non alza più il volume**  
   Finché il servizio di accessibilità è attivo, **Volume +** non modifica il volume. Per alzarlo serve il menu volume a schermo, le cuffie, o disattivare temporaneamente il servizio.

5. **Batteria e ottimizzazioni**  
   Alcuni produttori (Xiaomi, Huawei, Samsung, ecc.) uccidono i servizi in background. Può essere necessario escludere l’app dalle ottimizzazioni batteria (vedi `GUIDA.md`).

## Scaricare il codice sorgente

Repository: [https://github.com/depaolaluigi123/Volume-Power-App](https://github.com/depaolaluigi123/Volume-Power-App)

```bash
git clone https://github.com/depaolaluigi123/Volume-Power-App.git
cd Volume-Power-App
```

Per compilare e installare l’app, vedi [`README-USER-GUIDE-it.md`](README-USER-GUIDE-it.md).

## Requisiti

- Android **8.0 (API 28)** o superiore (`GLOBAL_ACTION_LOCK_SCREEN`).
- Consigliato: Android 12+ per comportamento prevedibile dei servizi in primo piano.

## Struttura progetto

```
VolumePowerApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/volumepower/app/
│   │   ├── MainActivity.kt
│   │   ├── VolumePowerForegroundService.kt   ← MediaSession + VolumeProvider + AudioTrack silenzioso
│   │   ├── VolumePowerAccessibilityService.kt ← onKeyEvent a schermo acceso
│   │   ├── WakeActivity.kt                    ← activity trasparente che accende il pannello
│   │   ├── ScreenWaker.kt                     ← helper condiviso (WakeLock + WakeActivity)
│   │   ├── AccessibilityUtils.kt
│   │   └── MiuiUtils.kt
│   └── res/xml/accessibility_service_config.xml
├── README.md          ← questo file (funzionamento)
└── GUIDA.md           ← installazione e test
```

## Disattivare l’app

1. Impostazioni → **Accessibilità** → disattiva «Volume Power App».
2. Trascina via la notifica e ferma il servizio, oppure **Impostazioni → App → Volume Power App → Arresta forzatamente**.
3. Opzionale: disinstalla l’app.

Dopo la disattivazione del servizio di accessibilità, **Volume +** torna al comportamento standard.


---

## Licenza

Copyright (C) 2026 Luigi De Paola

Volume Power App è software libero: puoi redistribuirlo e/o modificarlo secondo i
termini della GNU General Public License v3.0 (GPL-3.0).


