# Unbar Android Studio Project

## Prerequisites
1. **Android Studio** (Ladybug or newer) with JDK 17 and Android SDK (API 35).
2. Copy the prebuilt core library from your bundle into:
   `app/libs/unbar-core.aar`
3. *(Optional)* Replace the vector fallback launcher icons in `app/src/main/res/mipmap-*` with the raster PNGs from `assets/icon/android/` in your bundle.

## Verify Core API Signatures
Open `unbar-core-sources.jar` (or inspect `core.Tunnel` in Android Studio's External Libraries) and verify `app/src/main/java/com/breakthebarriers/unbar/data/tunnel/CoreTunnelBridge.kt` matches the exact gomobile-generated parameter types (`Long` vs `Int`, `String` vs comma-separated resolver list).

## Build Debug APK
1. Open the `android/` directory in Android Studio.
2. Click **Sync Project with Gradle Files**.
3. Run **Build -> Build App Bundle(s) / APK(s) -> Build APK(s)** (or `./gradlew assembleDebug`).
4. Output APK: `app/build/outputs/apk/debug/app-universal-debug.apk`.
