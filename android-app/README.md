# Crypto Signal Radar (Android)

Native Android app that monitors Binance spot pairs and alerts on your per-window thresholds across 1m, 5m, 15m, 1h, 1d, 1w, and 1mo windows. Includes a foreground service and optional floating overlay panel.

## Open in Android Studio

1) Open Android Studio.
2) Choose **Open** and select the `android-app` folder.
3) Let Gradle sync finish.

## Run on your phone

1) Connect your phone with USB debugging enabled.
2) Press **Run** in Android Studio and select your device.
3) Accept notification permission when prompted.

## Floating panel permission

To enable the floating panel:
- Open the app.
- Turn on **Floating panel**.
- Allow **Display over other apps** when Android opens the permission screen.

## Prepare for Google Play

Google Play requires a signed release bundle (`.aab`) and a developer account.

Current package id (applicationId): `com.crypto.signalradar`.
Before publishing, you may change it to your own unique id in `android-app\app\build.gradle.kts`, and bump `versionCode` for each update.

### Play Store assets

Use these files as templates when filling out the Play Console:

- `android-app/PLAY_STORE_LISTING.md`
- `android-app/DATA_SAFETY.md`
- `android-app/PRIVACY_POLICY.md` (host this as a public URL)

Privacy policy HTML is in `docs/privacy-policy.html` for GitHub Pages.

### 1) Create a keystore (one time)

From the `android-app` folder, run:
- `keytool -genkeypair -v -keystore signal-radar.jks -keyalg RSA -keysize 2048 -validity 10000 -alias signalradar`

### 2) Create keystore.properties

1) Copy `keystore.properties.example` to `keystore.properties`.
2) Update with your values, for example:

```properties
storeFile=signal-radar.jks
storePassword=your_store_password
keyAlias=signalradar
keyPassword=your_key_password
```

### 3) Build the release bundle

- `gradlew.bat bundleRelease`
- Output: `android-app\app\build\outputs\bundle\release\app-release.aab`

### 4) Upload to Play Console

1) Create a Google Play Developer account.
2) Create a new app entry.
3) Upload the `.aab` and complete the listing.
4) Use **Internal testing** or **Closed testing** to install from the Play Store quickly.

## Notes

- The service runs as a foreground service while monitoring.
- Alerts also appear as notifications.
