# Guida: installazione e test di Volume Power App

## Prerequisiti sul PC

1. **Android Studio** (consigliato) *oppure* SDK Android + JDK 17 da riga di comando.
2. **JDK 17** (richiesto da Gradle 8 e AGP 8.2).
3. Cavo USB o connessione wireless debugging per il telefono.

### Scaricare il repository

Clona il progetto da GitHub:

```bash
git clone https://github.com/depaolaluigi123/Volume-Power-App.git
cd Volume-Power-App
```

In alternativa, scarica lo ZIP da [Releases](https://github.com/depaolaluigi123/Volume-Power-App/releases) ed estrailo.

### Aprire il progetto in Android Studio

1. Avvia Android Studio → **Open** → cartella `Volume-Power-App`.
2. Attendi la sincronizzazione Gradle (download dipendenze al primo avvio).
3. Collega il telefono con **Debug USB** attivo (vedi sotto).

### Compilare da terminale (opzionale)

Dalla root del progetto, con `ANDROID_HOME` configurato:

```bash
cd Volume-Power-App
./gradlew assembleDebug
```

L’APK si trova in:

`app/build/outputs/apk/debug/app-debug.apk`

> Se manca lo script `gradlew`, genera il wrapper da Android Studio (**File → Sync Project with Gradle Files**) oppure con `gradle wrapper` se hai Gradle installato globalmente.

---

## Preparare il telefono

### 1. Opzioni sviluppatore e debug USB

1. **Impostazioni → Info telefono** → tocca **Numero build** 7 volte.
2. **Impostazioni → Opzioni sviluppatore**:
   - attiva **Debug USB**;
   - (opzionale) **Installa via USB** / **USB debugging (Security settings)** su alcuni marchi.

### 2. Installazione da Android Studio

1. Seleziona il dispositivo nella barra in alto.
2. Clic **Run** (triangolo verde) oppure **Shift+F10**.
3. Accetta l’installazione sul telefono se richiesto.

### 3. Installazione manuale dell’APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalla mantenendo i dati (se già presente).

Per disinstallare:

```bash
adb uninstall com.volumepower.app
```

---

## Configurazione al primo avvio (obbligatoria)

L’app **non funziona** solo aprendo l’icona: serve abilitare il servizio di accessibilità.

### Passo 1 – Avvia l’app

1. Tocca **Volume Power App** nel drawer.
2. L’app si apre e si chiude in un attimo (comportamento normale).
3. Compare una **notifica persistente** «Volume Power App attivo».

### Passo 2 – Permesso notifiche (Android 13+)

Se richiesto, consenti le **notifiche** per l’app.

### Passo 3 – Abilita accessibilità

1. Si aprono le **Impostazioni di accessibilità** (oppure vai manualmente: **Impostazioni → Accessibilità**).
2. Cerca **Volume Power App – tasto volume** (o «Volume Power App»).
3. Attivalo e conferma l’avviso di sicurezza di Android.

Senza questo passaggio il tasto Volume su **continuerà ad alzare il volume**.

### Passo 4 – Ottimizzazione batteria (consigliato)

Su molti telefoni il servizio viene chiuso in background.

1. **Impostazioni → App → Volume Power App → Batteria**.
2. Scegli **Non limitata** / **Senza restrizioni** / **Non ottimizzare** (testo varia per marca).

Su Xiaomi/MIUI, Huawei, Oppo, Samsung controlla anche:

- avvio automatico consentito;
- blocco app in memoria recenti (icona lucchetto nel multitasking).

---

## Risoluzione problemi

| Problema | Cosa fare |
|----------|-----------|
| Volume + alza ancora il volume | Accessibilità non attiva → ripeti Passo 3 |
| Nessuna notifica | Permesso notifiche negato; riavvia l’app |
| Funziona poi smette | Ottimizzazione batteria / kill app del produttore |
| «Arresta forzatamente» | Riapri l’app; riattiva accessibilità se spenta |
| Blocco non funziona | Serve Android 8+; alcune ROM personalizzate limitano `LOCK_SCREEN` |

