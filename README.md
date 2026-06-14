# Volume Power App

**Is your phone's power button broken?** It happens more often than you might think: after years of use, a drop, or simple wear, the Power key can stop responding. A repair at a service center often costs as much as a used phone, and in the meantime you're left with a device that's still perfectly usable but with no convenient way to lock the screen or wake it up.

**Volume Power App** offers a software solution: it remaps the **Volume up** key so it does the Power button's job:
- Screen on → locks the phone;
- Screen off → wakes the phone.

It's not an exact replacement for the hardware button (accessibility permissions, a persistent notification, and some volume trade-offs are required — see [Limitations](#important-limitations-android)), but for anyone with a broken Power button it's often enough to get by until a repair or phone replacement.

In technical terms: an Android app that starts a background service and closes immediately; from that point on, **Volume +** no longer raises the volume but handles screen lock and wake.

## What the app does (summary)

1. Tap the **Volume Power App** icon once.
2. A **foreground service** starts (persistent notification) and the activity closes: you don't stay "inside" the app.
3. After the initial setup, the **Volume +** key no longer raises the volume but:
   - **screen on** → locks the device (like "stand-by" / screen off with lock);
   - **screen off** → tries to turn the screen on (wake lock).

The **Volume down** button is not modified and continues to lower the volume normally.

> **Note:** If multimedia audio is playing in the background (e.g. music from Spotify, YouTube Music, or a podcast), **wake with Volume + does not work**: Android routes volume keys to the active media session instead of this app.

## Architecture

The app consists of four main components and one helper:

```
┌─────────────────┐
│   MainActivity  │  Startup: notification permission → start FGS → (if needed) accessibility settings → finish()
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────┐
│ VolumePowerForegroundService    │  Ongoing notification + MediaSession + VolumeProvider + silent AudioTrack
└──┬──────────────────────────────┘
   │   ▲ routes Volume +/− when screen is OFF (via MediaSession)
   │   │
   │   └─► ScreenWaker.wakeScreen(ctx) ◄──┐
   │                                       │
   ▼                                       │
┌──────────────────────────────────┐       │
│ VolumePowerAccessibilityService  │───────┘  onKeyEvent(): intercepts Volume + when screen is ON
└──────────────────────────────────┘
   │
   ▼
┌──────────────┐
│ WakeActivity │  Transparent activity with setTurnScreenOn(true) / setShowWhenLocked(true)
└──────────────┘
```

### Why two paths for Volume + (accessibility + MediaSession)

Android delivers key events in two different ways depending on screen state:

- **Screen on/unlocked:** the AccessibilityService receives `onKeyEvent()` and runs `GLOBAL_ACTION_LOCK_SCREEN`. Works everywhere.
- **Screen off (lock screen):** on MIUI/EMUI/OneUI the system **does not** call the accessibility service's `onKeyEvent()`: the key is handled at a lower level (PhoneWindowManager) and routed directly to the audio subsystem. To intercept it, the app registers an active `MediaSessionCompat` + `VolumeProviderCompat` REMOTE. The system delegates volume control to our `onAdjustVolume()`. To be "the current music session" and win routing, on `ACTION_SCREEN_OFF` an `AudioTrack` with zero PCM samples is started (real silence, volume 0, `MODE_STATIC` in an infinite loop = negligible CPU cost).

### MainActivity

- Transparent activity and `excludeFromRecents`: the user doesn't see it in the task switcher after launch.
- On Android 13+ requests **Notifications** permission (required for the foreground service).
- Starts `VolumePowerForegroundService` and exits with `finish()`.
- If the accessibility service isn't active yet, shows a message and opens **Settings → Accessibility**.

### VolumePowerForegroundService

- Type: `foregroundServiceType="mediaPlayback|specialUse"` (Android 14+), with subtype declared in the manifest.
- Shows a **non-dismissible** notification while the service is active (tap → accessibility settings).
- `START_STICKY`: if the system kills the process, Android may restart the service.
- Registers an active `MediaSessionCompat` with `PLAYING` state and `setPlaybackToRemote(VolumeProviderCompat)`: when the system needs to adjust volume it calls our `onAdjustVolume(direction)`.
  - `ADJUST_RAISE` → `ScreenWaker.wakeScreen()` (volume + never raises the volume).
  - `ADJUST_LOWER` → delegates to `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_LOWER, FLAG_SHOW_UI)` (volume − works normally).
- On `ACTION_SCREEN_OFF`: acquires a `PARTIAL_WAKE_LOCK`, reactivates the session, and starts a silent `AudioTrack` in `MODE_STATIC` infinite loop. On `ACTION_SCREEN_ON` releases everything.

### VolumePowerAccessibilityService

This is the core of the remapping. Android **does not allow** normal apps to read or intercept hardware volume keys without an **AccessibilityService** with a key filter:

- `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents="true"` in `res/xml/accessibility_service_config.xml`.
- In `onKeyEvent()`:
  - if the key is **Volume up**, returns `true` (event **consumed** → volume doesn't change);
  - on `ACTION_DOWN`, after a **debounce** of 400 ms:
    - `PowerManager.isInteractive == true` → `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`;
    - otherwise → `WakeLock` with `ACQUIRE_CAUSES_WAKEUP` to turn the screen on.

#### Debounce (400 ms)

The debounce prevents rapid Volume up presses from triggering multiple actions in a row (lock + wake, or double lock) due to key bounce or duplicate events.

- **Where it's declared:** constant `DEBOUNCE_MS = 400L` in the `companion object` of `VolumePowerAccessibilityService.kt`.
- **Where it's used:** in `handleVolumeUpPress()`: if less than 400 ms have passed since the last valid action, the press is ignored (`return`); otherwise `lastToggleAtMs` is updated and lock or wake is performed.

```kotlin
// VolumePowerAccessibilityService.kt
private const val DEBOUNCE_MS = 400L

if (now - lastToggleAtMs < DEBOUNCE_MS) return
lastToggleAtMs = now
```

To change sensitivity, modify `DEBOUNCE_MS` (higher values = less responsive but more stable).

## Permissions

| Permission | Reason |
|------------|--------|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background service visible to the user; the `mediaPlayback` type is required by Android 14 when the service plays audio (even silent) |
| `POST_NOTIFICATIONS` | Service notification (Android 13+) |
| `WAKE_LOCK` | Turn screen on when it's off |
| `TURN_SCREEN_ON` | Permission to turn the screen on from Android 14 |
| `MODIFY_AUDIO_SETTINGS` | Required to lower the system stream volume from `VolumeProvider.onAdjustVolume(ADJUST_LOWER)` |

Root and signed system permissions are not required.

## Important limitations (Android)

This is not a perfect copy of the **Power** button at the hardware/firmware level:

1. **Accessibility service required**  
   The user must manually enable "Volume Power App – volume key" in Accessibility. Without this step, remapping won't work.

2. **Screen lock ≠ full shutdown**  
   `GLOBAL_ACTION_LOCK_SCREEN` locks the device (PIN/biometrics if configured), like a lock screen. It doesn't power off the CPU like a real power-off!

3. **Wake with screen off — not while media is playing**  
   On many ROMs (MIUI, EMUI, OneUI) key events with the screen off don't reach the AccessibilityService. The app works around this with a `MediaSessionCompat` + `VolumeProviderCompat` REMOTE + silent `AudioTrack` that ensures the system routes `onAdjustVolume()` to our app even from the lock screen. **However, if another app is actively playing multimedia audio** (Spotify, YouTube Music, a podcast, a video in the background, etc.), Android gives that app priority for volume keys: **Volume + will not wake the screen** and will only affect the other app's playback.

4. **Volume up no longer raises volume**  
   While the accessibility service is active, **Volume +** doesn't change the volume. To raise it, use the on-screen volume menu, headphones, or temporarily disable the service.

5. **Battery and optimizations**  
   Some manufacturers (Xiaomi, Huawei, Samsung, etc.) kill background services. You may need to exclude the app from battery optimizations (see `GUIDA-UTENTE.md`).

## Getting the source code

Repository: [https://github.com/depaolaluigi123/Volume-Power-App](https://github.com/depaolaluigi123/Volume-Power-App)

```bash
git clone https://github.com/depaolaluigi123/Volume-Power-App.git
cd Volume-Power-App
```

For build and installation instructions, see [`README-USER-GUIDE.md`](README-USER-GUIDE.md).

## Requirements

- Android **8.0 (API 28)** or higher (`GLOBAL_ACTION_LOCK_SCREEN`).
- Recommended: Android 12+ for predictable foreground service behavior.

## Project structure

```
VolumePowerApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/volumepower/app/
│   │   ├── MainActivity.kt
│   │   ├── VolumePowerForegroundService.kt   ← MediaSession + VolumeProvider + silent AudioTrack
│   │   ├── VolumePowerAccessibilityService.kt ← onKeyEvent when screen is on
│   │   ├── WakeActivity.kt                    ← transparent activity that turns the panel on
│   │   ├── ScreenWaker.kt                     ← shared helper (WakeLock + WakeActivity)
│   │   ├── AccessibilityUtils.kt
│   │   └── MiuiUtils.kt
│   └── res/xml/accessibility_service_config.xml
├── README.md          ← this file (how it works)
└── GUIDA-UTENTE.md    ← installation and testing
```

## Disabling the app

1. Settings → **Accessibility** → disable "Volume Power App".
2. Swipe away the notification and stop the service, or **Settings → Apps → Volume Power App → Force stop**.
3. Optional: uninstall the app.

After disabling the accessibility service, **Volume +** returns to standard behavior.


---

## License

Copyright (C) 2026 Luigi De Paola

Volume Power App is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License v3.0 (GPL-3.0).



