# ClipMax

ClipMax is an Android clipboard manager with a dedicated system keyboard (Input Method Editor, or IME). It is designed to keep saved text on the device and make long snippets easier to reuse in other apps.

## Current project details

- Android application ID: `com.clipmax.app`
- Current Gradle version fields: `versionCode 11`, `versionName 1.0.1`
- Minimum Android SDK: 26 (Android 8.0)
- Target Android SDK: 35
- Main native language: Kotlin
- Web UI assets are bundled into the Android app; this project is not purely native Kotlin.
- The Android app manifest does not declare `INTERNET` permission in the current inspected version.

## Main capabilities

- Save and manage text snippets locally.
- Use the ClipMax keyboard to insert saved snippets into other apps.
- Import `.txt` files through Android's Storage Access Framework.
- Export and restore ZIP backups.
- Split long text into bounded chunks before insertion.
- Configure retention/expiry for captured clipboard entries.

Feature details and behavior should be verified against the current source and on a physical device before each release.

## Build

The project uses Gradle. With a compatible JDK 17 and Android SDK installed, run from the repository root:

```bash
chmod +x gradlew
./gradlew assembleDebug
```

Expected debug APK path:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

A successful debug build is not, by itself, a release or F-Droid readiness check.

## Licensing

The project is proposed for distribution under the GNU General Public License, version 3. See [`LICENSE`](LICENSE) for the complete license text.

Before publishing a release, the project maintainer must confirm that they have the rights to license all ClipMax-authored code and assets under GPL-3.0 and must review third-party code, fonts, icons, and other bundled assets. Third-party components remain subject to their own applicable licenses and notices.

## F-Droid status

This repository is **not yet confirmed F-Droid-ready**. Before submitting, update and verify the README, audit all bundled assets and dependencies, ensure all required source is available, remove or bundle any remote-only assets as appropriate, and create a tested release tag with matching source and build metadata.
