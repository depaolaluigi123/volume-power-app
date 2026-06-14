# Guide: installing and testing Volume Power App

## Prerequisites on your PC

1. **Android Studio** (recommended) *or* Android SDK + JDK 17 from the command line.
2. **JDK 17** (required by Gradle 8 and AGP 8.2).
3. USB cable or wireless debugging connection for your phone.

### Downloading the repository

Clone the project from GitHub:

```bash
git clone https://github.com/depaolaluigi123/Volume-Power-App.git
cd Volume-Power-App
```

Alternatively, download a ZIP from [Releases](https://github.com/depaolaluigi123/Volume-Power-App/releases) and extract it.

### Opening the project in Android Studio

1. Launch Android Studio → **Open** → select the `Volume-Power-App` folder.
2. Wait for Gradle sync (dependencies download on first launch).
3. Connect your phone with **USB debugging** enabled (see below).

### Building from the terminal (optional)

From the project root, with `ANDROID_HOME` configured:

```bash
cd Volume-Power-App
./gradlew assembleDebug
```

The APK is located at:

`app/build/outputs/apk/debug/app-debug.apk`

> If the `gradlew` script is missing, generate the wrapper from Android Studio (**File → Sync Project with Gradle Files**) or with `gradle wrapper` if you have Gradle installed globally.

---

## Preparing your phone

### 1. Developer options and USB debugging

1. **Settings → About phone** → tap **Build number** 7 times.
2. **Settings → Developer options**:
   - enable **USB debugging**;
   - (optional) **Install via USB** / **USB debugging (Security settings)** on some brands.

### 2. Installing from Android Studio

1. Select the device in the top toolbar.
2. Click **Run** (green triangle) or press **Shift+F10**.
3. Accept the installation on your phone if prompted.

### 3. Manual APK installation

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalls while keeping data (if the app is already installed).

To uninstall:

```bash
adb uninstall com.volumepower.app
```

---

## First-run setup (required)

The app **does not work** by simply opening the icon: you must enable the accessibility service.

### Step 1 – Launch the app

1. Tap **Volume Power App** in the app drawer.
2. The app opens and closes immediately (normal behavior).
3. A **persistent notification** appears: “Volume Power App active”.

### Step 2 – Notification permission (Android 13+)

If prompted, allow **notifications** for the app.

### Step 3 – Enable accessibility

1. **Accessibility settings** open automatically (or go manually: **Settings → Accessibility**).
2. Find **Volume Power App – volume key** (or “Volume Power App”).
3. Turn it on and confirm Android’s security warning.

Without this step, the Volume up key **will continue to raise the volume**.

### Step 4 – Battery optimization (recommended)

On many phones, the service is killed in the background.

1. **Settings → Apps → Volume Power App → Battery**.
2. Choose **Unrestricted** / **No restrictions** / **Don't optimize** (wording varies by manufacturer).

On Xiaomi/MIUI, Huawei, Oppo, and Samsung, also check:

- auto-start allowed;
- lock the app in recent apps (padlock icon in the multitasking view).

---

## Troubleshooting

| Problem | What to do |
|---------|------------|
| Volume + still raises the volume | Accessibility not enabled → repeat Step 3 |
| No notification | Notification permission denied; restart the app |
| Works then stops | Battery optimization / manufacturer app killer |
| “Force stop” | Reopen the app; re-enable accessibility if it was turned off |
| Lock does not work | Requires Android 8+; some custom ROMs restrict `LOCK_SCREEN` |
