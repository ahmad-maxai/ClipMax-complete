# ClipMax-Android

Official native Android application and IME (Input Method Editor) service for **ClipMax** (v0.5.1).

This repository contains the complete native Kotlin codebase, Android resources, Gradle build configuration, and adaptive launcher icons generated from the official ClipMax logo. It is structured for both local Android Studio development and cloud APK compilation through **Newly AI**, **GitHub Actions**, or other Android CI/CD pipelines.

---

## Project Specifications

- **Package ID / Namespace**: `com.clipmax.app`
- **Application Label**: ClipMax
- **Target Android Version**: Android 15 (API level 35)
- **Minimum Supported Android**: Android 8.0 (API level 26)
- **Language**: Kotlin 2.0.21
- **Android Gradle Plugin (AGP)**: 8.7.3
- **Gradle Version**: 8.9 (via included Gradle Wrapper)
- **JDK Compatibility**: Java 17

---

## Native Architecture & Features

ClipMax is a private, offline-first clipboard manager and system input method:

1. **`ClipMaxImeService.kt`** (`com.clipmax.app.ClipMaxImeService`):
   - Registered as a system Input Method protected by `android.permission.BIND_INPUT_METHOD`.
   - Inserts saved clips directly into any third-party app (WhatsApp, Chrome, Notes, SMS, etc.).
   - Inserts text using surrogate-safe bounded chunks of up to 50,000 UTF-16 code units per `commitText` call to prevent binder transaction overflows (`TransactionTooLargeException`).
   - Displays real-time progress and supports cancellation during chunk insertion.
   - Built-in compact search keypad for filtering snippets on the fly.
   - Automatic capture suppression when focused on secure password fields (`TYPE_TEXT_VARIATION_PASSWORD`).

2. **`MainActivity.kt`** (`com.clipmax.app.MainActivity`):
   - Native Android Activity for library management, manual clip editing, and settings.
   - Storage Access Framework (SAF) integration for importing `.txt` files.
   - ZIP backup export and merge-restore with a 128 MiB boundary check.

3. **`ClipStore.kt` & `TextChunker.kt`**:
   - Device-only sandbox persistence without network permissions or external accounts.
   - Surrogate-safe chunking that preserves high/low surrogate pairs (emoji and CJK characters).

4. **`CapturePreferences.kt` & `CaptureExpiryReceiver.kt`**:
   - Retention configuration (Off, 1h, 6h, 24h, 72h, Forever).
   - Inexact alarm scheduling for expiring temporary capture cards.

---

## Building a Debug APK

### Method 1: Cloud Build with Newly AI
1. Create a new GitHub repository named `ClipMax-Android`.
2. Push this repository to GitHub:
   ```bash
   git init -b main
   git add .
   git commit -m "Initial commit of native ClipMax Android project"
   git remote add origin https://github.com/<your-username>/ClipMax-Android.git
   git push -u origin main
   ```
3. In Newly AI, connect your GitHub repository `https://github.com/<your-username>/ClipMax-Android`.
4. Newly AI will detect the Gradle project configuration and build the installable debug APK (`app-debug.apk`).

### Method 2: Automatic GitHub Actions Build
This repository includes `.github/workflows/build-apk.yml`. Every push or pull request to `main` triggers a GitHub Actions runner with JDK 17 to build the APK. The resulting `ClipMax-v0.5.1-debug-apk` will be available in the **Actions** tab as a downloadable artifact.

### Method 3: Local Android Studio / Terminal Build
Prerequisites: JDK 17 and Android SDK 35 installed.

```bash
# Build from repository root
chmod +x gradlew
./gradlew assembleDebug

# Output APK path:
# android/app/build/outputs/apk/debug/app-debug.apk
```

Or open the `android/` directory directly in Android Studio.

---

## Verification & Integrity Checks

- **Zero sample / dummy snippets**: Default storage initializes empty.
- **No telemetry or tracking**: Zero analytics, advertising, or background network calls.
- **Native Android implementation**: Pure Kotlin and Android platform APIs (`InputMethodService`, `Activity`, `BroadcastReceiver`). No webview wrappers.
