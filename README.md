# ClipMax

Official repository for **ClipMax** (v1.0.1) — a private, offline-first Android clipboard manager and system keyboard (Input Method Editor, or IME).

ClipMax combines a high-performance native Kotlin Input Method service with an offline-bundled management interface. It is designed to keep saved text strictly on the device, handle large payloads safely, and make text reuse seamless across any Android application.

---

## Project Specifications

- **Application ID / Package**: `com.clipmax.app`
- **Current Version**: `1.0.1` (`versionCode 11`)
- **Minimum Supported Android**: Android 8.0 (API level 26)
- **Target Android Version**: Android 15 (API level 35)
- **Compile SDK**: API 35 (Android 15)
- **Build Tools**: 35.0.0
- **Android Gradle Plugin**: 8.7.3
- **Gradle Wrapper**: 8.9
- **JDK Compatibility**: Java 17
- **License**: GNU General Public License v3.0 ([`LICENSE`](LICENSE))

---

## Architecture & Core Features

ClipMax employs a dual-tier architecture that separates system-critical input operations from library management:

### 1. Native System Input Method (`ClipMaxImeService.kt`)
- **Pure Native Kotlin Service**: Registered as an Android Input Method protected by `android.permission.BIND_INPUT_METHOD`.
- **Gboard-Inspired Virtual Keyboard**: Complete QWERTY touch layout with responsive letter popups, symbol panels, and tactile haptic feedback. Popups render on the root overlay frame to prevent edge or toolbar clipping on row-1 keys (`Q`–`P`).
- **50,000-Unit Chunked Text Insertion**: Large text snippets are inserted in bounded UTF-16 chunks (up to 50,000 code units per `commitText` call) to prevent Android IPC binder transaction overflows (`TransactionTooLargeException`). Includes live insertion progress and user cancellation.
- **Compact Saved-Clips Panel**: A vertically scrollable clipboard browser designed for small viewports. Collapsed cards measure ~51dp and display single-line monospace previews with character and size metadata, ensuring at least **2 to 3 cards are simultaneously visible** at all times.
- **Single-Card Expansion**: Tapping a card expands it to reveal equal-width, accessible action buttons: **Insert**, **Edit**, **Pin/Unpin**, and **Delete** (with inline confirmation). Only one card expands at a time to preserve vertical browsing space.
- **Hardware-Accelerated Vector Icons**: Uses a custom `IconDrawable` engine for monochrome, theme-aware vector graphics. Zero decorative Unicode emojis.
- **Dual Typing Modes**:
  - `Typing: Host` (default): All keystrokes commit directly to the active destination text field.
  - `Typing: Filter`: Keystrokes filter saved snippets in real-time without leaking text into the host application.
- **Privacy & Automatic Capture**: Automatically captures copied text and selection updates during active non-password sessions. Automatic capture is completely suppressed in password fields (`TYPE_TEXT_VARIATION_PASSWORD`).

### 2. Management Application & Offline Web Interface (`MainActivity.kt` & Bundled Assets)
- **Offline WebView Container**: `MainActivity.kt` hosts a local WebView (`androidx.webkit:webkit:1.12.1`) rendering bundled React and Tailwind assets from `android/app/src/main/assets/web/`.
- **Zero Internet Permissions**: `AndroidManifest.xml` does **not** declare `android.permission.INTERNET`. The app cannot establish network connections, send analytics, or fetch remote resources at runtime.
- **Bidirectional Native Bridge (`AndroidBridge.kt`)**: Connects the web UI to the native Kotlin storage engine via `@JavascriptInterface` for instant clip updates, pin toggling, and theme synchronization.
- **Storage Access Framework (SAF)**: Native file picker integration for importing plain-text files (`.txt`).
- **ZIP Backup & Restore**: Create full archive backups of saved clips or merge-restore previous archives with a 128 MiB boundary check.

### 3. Storage & Persistence (`ClipStore.kt`)
- **Atomic File Storage**: Metadata is atomically replaced, and content files are isolated to prevent partial-write corruption on sudden power loss.
- **Newest-First Chronological Ordering**: Saved items are sorted with newest creations first (`createdAt` descending), with pinned items floating to the top. Chunks from the same capture share timestamps and preserve ascending serial order (`Chunk 001`, `Chunk 002`) via title tiebreakers.
- **Retention & Cleanup (`CapturePreferences.kt`)**: User-selectable auto-expiry periods (Off, 1h, 6h, 24h, 72h, or Forever) managed via local preferences and inexact `AlarmManager` schedules.

---

## Project Structure

```text
ClipMax-complete/
├── android/                             # Native Android Gradle Project
│   ├── app/
│   │   ├── src/main/java/com/clipmax/app/
│   │   │   ├── ClipMaxImeService.kt    # Native IME service & keyboard
│   │   │   ├── MainActivity.kt         # Android Activity host & WebView
│   │   │   ├── AndroidBridge.kt        # JS-to-Kotlin interface bridge
│   │   │   ├── ClipStore.kt            # Atomic local filesystem storage
│   │   │   ├── TextChunker.kt          # Surrogate-safe 50k UTF-16 chunking
│   │   │   └── CapturePreferences.kt   # Retention and clipboard settings
│   │   ├── src/main/assets/web/        # Bundled offline web UI assets
│   │   ├── src/main/AndroidManifest.xml# Android Manifest (zero network perms)
│   │   └── build.gradle.kts            # App module Gradle configuration
│   ├── build.gradle.kts                # Root Android build configuration
│   └── gradlew                         # Gradle wrapper executable
├── src/                                # Web UI React/TypeScript source
│   ├── components/                     # Screens, primitives, and keyboard preview
│   └── lib/                            # Storage, chunking, and bridge helpers
├── fastlane/metadata/android/en-US/    # F-Droid Fastlane metadata
├── .github/workflows/build-apk.yml     # GitHub Actions CI build workflow
├── LICENSE                             # GNU General Public License v3.0
└── README.md                           # Project documentation
```

---

## Building from Source

### Prerequisites
- **JDK 17** (Temurin or OpenJDK recommended)
- **Android SDK Platform 35** and **Build Tools 35.0.0**
- **Node.js 20+** (only required when modifying the web UI in `src/`)

### Step 1: Build Web UI Assets (Optional)
If you made changes to the React source in `src/`, compile the web assets and copy them to the Android assets directory:
```bash
npm install
npm run build
rm -rf android/app/src/main/assets/web
cp -r dist android/app/src/main/assets/web
```

### Step 2: Build the Android Debug APK
Run the Gradle wrapper from the repository root:
```bash
chmod +x gradlew
./gradlew assembleDebug
```
Or run directly from the `android/` directory:
```bash
cd android
chmod +x gradlew
./gradlew assembleDebug
```

### Expected Output Path
```text
android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Automated CI/CD (GitHub Actions)

This repository includes a continuous integration workflow defined in [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml):
- **Triggers**: On every push to `main`, pull request, or manual trigger (`workflow_dispatch`).
- **Environment**: Ubuntu runner with OpenJDK 17.
- **Task**: Executes `./gradlew --no-daemon assembleDebug` inside the `android/` directory.
- **Artifact**: Uploads the resulting installable APK as a downloadable ZIP named **`ClipMax-debug-APK`** (retained for 14 days in the GitHub Actions run summary).

---

## Licensing & Third-Party Components

ClipMax is free software licensed under the **GNU General Public License v3.0** ([`LICENSE`](LICENSE)).

All third-party libraries and runtime components are compatible open-source projects:
- **AndroidX WebKit** (`androidx.webkit:webkit`): Apache License 2.0
- **React & React DOM**: MIT License
- **Lucide Icons** (`lucide-react`): ISC License
- **JSZip** (`jszip`): MIT / GPL-3.0 dual license
- **Tailwind CSS**: MIT License
- **JUnit 4** (`junit:junit`, test-only): Eclipse Public License 1.0

See [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) for full notices and license texts.

---

## F-Droid Readiness Status

This repository is currently being prepared for submission to F-Droid:
- [x] **Zero Tracking & Telemetry**: No analytics, trackers, or advertising SDKs.
- [x] **Zero Network Access**: `android.permission.INTERNET` is completely omitted from `AndroidManifest.xml`.
- [x] **Pure Open-Source Dependencies**: All build and runtime dependencies use approved FSDG-compatible licenses.
- [x] **Fastlane Metadata**: Initial localized metadata files are populated in `fastlane/metadata/android/en-US/`.
- [ ] **External Font Reference Cleanup**: Review and remove external Google Fonts links in `index.html` in favor of native system fonts to ensure 100% offline compliance.
- [ ] **Release Tag & Tarball**: Create an annotated release tag matching `versionCode 11` / `versionName 1.0.1` prior to opening the F-Droid RFP / merge request.

Track ongoing audit tasks in [`FDROID_READINESS_CHECKLIST.md`](FDROID_READINESS_CHECKLIST.md).
