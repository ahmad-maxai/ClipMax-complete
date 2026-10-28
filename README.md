ClipMax

ClipMax is an Android clipboard manager with a custom system keyboard (Input Method Editor, or IME). It combines a native Kotlin Android application and keyboard service with a React-based management interface bundled inside the app.

Version: 1.0.1
Version code: 11
Application ID: "com.clipmax.app"
Minimum Android version: Android 8.0 (API 26)

Features

- Save, search, edit, pin, and reuse text clips.
- Access saved clips from the ClipMax keyboard.
- Insert long text using bounded chunks.
- Import text files and export or restore ZIP backups.
- Configure retention for automatically captured clips.
- Use light and dark interface themes.

Feature availability and behavior should be confirmed against the release being tested.

Architecture

- "ClipMaxImeService.kt" provides the native Android keyboard.
- "MainActivity.kt" hosts the bundled management interface in an Android WebView.
- "AndroidBridge.kt" connects the web interface to native app functionality.
- "ClipStore.kt" and related Kotlin classes handle local clip storage and text processing.
- "android/app/src/main/assets/web/" contains the web assets packaged into the Android application.
- "src/" contains the React/TypeScript source used to build the web interface.

External resource references should be removed from packaged HTML where possible so the app does not depend on remote resources.

Project and build requirements

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Gradle Wrapper 8.9
- Android Gradle Plugin 8.7.3
- Node.js and npm are needed only when rebuilding the web interface from "src/".

Build the debug APK

From the repository root, run:

cd android
chmod +x gradlew
./gradlew --no-daemon assembleDebug

Expected output:

"android/app/build/outputs/apk/debug/app-debug.apk"

This is a debug build for testing, not a signed production release. The first build requires internet access to download Gradle and dependencies.

GitHub Actions

Check ".github/workflows/build-apk.yml" for the exact triggers and artifact retention settings. Open the repository's Actions tab to find completed builds.

Licensing and F-Droid

The repository contains a GPL version 3 license file and a preliminary third-party license inventory. The inventory is a review aid; dependency versions, bundled assets, and their notices still need to be verified before release.

F-Droid publication has not been completed or approved. Remaining work includes checking third-party licenses and asset provenance, removing external font references, validating the source build, and preparing appropriate release metadata.

- "F-Droid submission guide" (https://f-droid.org/docs/Submitting_to_F-Droid/)
- "F-Droid build metadata reference" (https://f-droid.org/docs/Build_Metadata_Reference/)
- "Reproducible builds" (https://f-droid.org/docs/Reproducible_Builds/)

See ""FDROID_READINESS_CHECKLIST.md"" (FDROID_READINESS_CHECKLIST.md) and ""THIRD_PARTY_LICENSES.md"" (THIRD_PARTY_LICENSES.md) for the current review notes.
