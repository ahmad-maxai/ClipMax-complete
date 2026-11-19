# ClipMax

Official repository for **ClipMax** (v0.5.1, `versionCode 12`) — a private, offline-first Android clipboard manager with a **bilingual English–Arabic native system keyboard (IME)** and full **English (LTR) / Arabic (`العربية`, RTL) UI localization**.

ClipMax combines a high-performance native Kotlin input method service with an offline-bundled companion interface. It is designed to keep saved text strictly on your device, handle long text payloads safely, provide automatic copy capture with customizable retention and per-item expiry protection, and make text reuse seamless across any Android application.

---

## Project Specifications

- **Application ID / Package**: `com.clipmax.app`
- **Version**: `0.5.1` (`versionCode 12`)
- **Minimum Supported Android**: Android 8.0 (API level 26)
- **Target Android Version**: Android 15 (API level 35)
- **Compile SDK**: API 35 (Android 15)
- **Build Tools**: 35.0.0
- **Android Gradle Plugin**: 8.7.3
- **Gradle Wrapper**: 8.9
- **JDK Compatibility**: Java 17
- **License**: GNU General Public License v3.0 or later ([`GPL-3.0-or-later`](LICENSE))

---

## What ClipMax Does

ClipMax gives your frequently used text snippets, links, templates, and notes a secure, local-first home on Android, allowing you to paste them directly from your keyboard without switching between apps.

### Core Capabilities:
1. **Direct Keyboard Access**: Paste saved clips directly from the ClipMax system keyboard inside any app (messaging, browsers, forms, terminals, and note-taking apps).
2. **Safe Large Text Insertion**: Inserts text in bounded 50,000 UTF-16 code unit chunks, preventing Android IPC transaction overflow crashes (`TransactionTooLargeException`) when pasting very large texts. Includes live insertion progress and cancellation.
3. **Automatic Copy Capture**: Captures copied text and focused editable selections during active non-password keyboard sessions.
4. **Customizable Retention & Expiry**: Set temporary clips to expire automatically (Off, 1h, 6h, 24h, 72h, custom minutes/hours, or Forever).
5. **Per-Item Expiry Protection**: Lock specific clips so that automatic expiry will never delete them, even when a finite retention period is active.
6. **Card Management Tools**: Pin favorites to float them to the top, split large items into halves, merge related clips together, or search by title and content.
7. **Offline Backup & SAF Import**: Import plain text files (`.txt`) via Android's Storage Access Framework or create structured, verifiable ZIP backups with a 128 MiB decompression safety limit.
8. **100% Private & Offline**: Zero network permissions declared (`android.permission.INTERNET` is completely absent). No analytics, no advertising, no telemetry, and no account requirements.

---

## Native System Keyboard (IME)

ClipMax includes a native Android Input Method service (`ClipMaxImeService.kt`) implemented in Kotlin and protected by the system permission `android.permission.BIND_INPUT_METHOD`.

### Keyboard Features:
- **Bilingual English & Arabic Layouts**: Full English QWERTY and standard Arabic (`العربية`) keyboard layouts with quick language switching, Arabic-Indic digit hints, symbol pages, emoji picker, and haptic feedback.
- **Offline English Typing Intelligence**: Local prefix word suggestions and high-confidence autocorrection powered by a verified Apache-2.0 AOSP LatinIME word list (`en_words.txt`), with zero network calls.
- **Bilingual UI & RTL Support**: Companion app and native keyboard sub-panels support both English (LTR) and Arabic (`العربية`, RTL), switchable independently of the active keyboard typing language.
- **Automatic Numeric-Field Detection**: Automatically activates the dedicated numpad layout when focusing numeric input fields (`TYPE_CLASS_NUMBER`, plain, decimal, or signed), phone dialers (`TYPE_CLASS_PHONE`), date/time fields (`TYPE_CLASS_DATETIME`), and numeric PINs (`TYPE_NUMBER_VARIATION_PASSWORD`). Automatically restores standard QWERTY typing when focusing text, multiline, email, web address, search, or password fields.
- **Clipboard Panel & Chips**: View recent clips in an interactive horizontal strip or open the full clipboard browser to scroll through all saved cards.
- **Card Context Menu**: Long-press any clipboard card in the keyboard to reveal quick actions: **Insert**, **Edit** (opens in app editor), **Pin/Unpin**, **Lock/Unlock expiry**, **Delete**, **Merge**, and **Split**.
- **Search Clips Keypad**: Built-in search filtering mode to find clips instantly without typing text into the host application.
- **Paste Undo / Redo Capsule**: Contextual floating action capsule providing reliable single-tap undo and redo after clipboard insertions.

### How to Enable the ClipMax Keyboard:
1. Open **ClipMax** on your device.
2. Complete the initial onboarding guide or navigate to **Settings → Android System Keyboard**.
3. Tap **1. Manage System Keyboards** to open Android System Settings and toggle **ClipMax** on.
4. Tap **2. Switch Active Input Method** and select **ClipMax** in the system input method dialog.

---

## What ClipMax Can and Cannot Capture

ClipMax is designed with privacy and system constraints in mind:

### What ClipMax Can Capture:
- Text copied to the clipboard while the ClipMax keyboard is open or shortly before opening it.
- Text actively selected inside a focused editable field while ClipMax is active.
- Plain-text files (`.txt`) explicitly chosen by the user via the Storage Access Framework file picker.
- Text manually typed or pasted into the companion app editor.

### What ClipMax Cannot Capture:
- **Password Fields**: Automatic capture and saved item suggestions are completely disabled whenever a password or PIN field is focused (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`, `WEB_PASSWORD`, etc.).
- **Background / Inactive Monitoring**: ClipMax does not run a continuous background service to spy on the clipboard when the keyboard is closed. Clipboard checks occur only when the keyboard is invoked or the app is active.
- **Uncopied Read-Only Text**: Selections inside read-only views (e.g. articles or web pages) cannot be read by an IME without the user explicitly invoking the source application's **Copy** command.
- **System or IPC Overrides**: If a source app truncates its own clipboard output or refuses to expose clipboard data, ClipMax receives only what Android delivers.

---

## Local Storage & Persistence

ClipMax persists text using an atomic file-storage engine (`ClipStore.kt`):
- **Location**: App-private internal storage (`/data/user/0/com.clipmax.app/files/clipmax_store/`), inaccessible to other applications without root access.
- **Atomic Architecture**: Content is written to temporary files and flushed to disk before small metadata records (`.bin`) are atomically replaced. A sudden power loss or process termination will not corrupt saved clips.
- **Capacity Limits**: Individual saved text items support up to **16 MiB** (UTF-8 bytes). ZIP backup restores are capped at **128 MiB** of uncompressed contents to prevent zip-bomb exhaustion.
- **Device Security**: Relies on Android's device-level File-Based Encryption (FBE). ClipMax does not add an independent custom encryption layer over its internal database files.

---

## Retention, Expiry, and Expiry Locking

### Retention Behavior:
ClipMax allows users to choose how long automatically captured clips remain on device:
- Presets: **Off**, **1 hour**, **6 hours**, **24 hours**, **72 hours**, a **Custom** duration (in minutes or hours), or **Forever**.
- Expiration cleanup runs automatically whenever the companion app or keyboard opens, and periodically via Android's local `AlarmManager` (`CaptureExpiryReceiver.kt`).
- Timers are automatically rescheduled after device reboots via `android.permission.RECEIVE_BOOT_COMPLETED`.

### How Expiry Locking Works:
- Users can lock critical clips to prevent automatic deletion (`isExpiryLocked = true`).
- **Exclusion from Cleanup**: Pinned items and expiry-locked items are strictly skipped by retention cleanup. They remain stored safely until explicitly deleted or unlocked by the user.
- **Independence from Pinning**: An item can be pinned, locked, both, or neither. Locking an item does not force it to float to the top; unpinning an item does not remove its lock.
- **Fresh Expiry Window on Unlock**: When an expiry-locked item is unlocked, ClipMax recalculates a fresh expiry window starting from the moment of unlock based on the active retention setting (or marks it as permanent if retention is set to Forever or Off). Old, expired timestamps are never reused.
- **Contextual Reminders**: When saving new clips or importing text while a finite retention policy is active, ClipMax displays a non-blocking banner allowing users to lock the new items in one tap.

---

## Project Structure

```text
ClipMax-complete/
├── android/                             # Android Gradle project root
│   ├── app/
│   │   ├── src/main/java/com/clipmax/app/
│   │   │   ├── ClipMaxImeService.kt    # Native IME service & keyboard engine
│   │   │   ├── MainActivity.kt         # Android Activity host & WebView
│   │   │   ├── AndroidBridge.kt        # Bidirectional JS-to-Kotlin bridge
│   │   │   ├── ClipStore.kt            # Atomic local filesystem storage
│   │   │   ├── InputFieldPolicy.kt     # EditorInfo classification & auto-numpad
│   │   │   ├── KeyboardLayoutSpec.kt   # Keyboard modes, layouts & themes
│   │   │   ├── CapturePreferences.kt   # Retention, expiry & lock management
│   │   │   └── TextChunker.kt          # Bounded 50,000 UTF-16 chunking
│   │   ├── src/main/assets/web/        # Bundled offline web companion assets
│   │   ├── src/main/AndroidManifest.xml# Android Manifest (zero network perms)
│   │   └── build.gradle.kts            # App module configuration (v0.5.1, code 12)
│   ├── build.gradle.kts                # Android root build configuration
│   └── gradlew                         # Gradle wrapper executable
├── src/                                # Web UI React/TypeScript source
│   ├── components/                     # Screens, modals, and UI primitives
│   └── lib/                            # Storage, backup, and native bridge helpers
├── fastlane/metadata/android/en-US/    # F-Droid Fastlane metadata & screenshots
├── scripts/                            # Release and consistency verification scripts
├── com.clipmax.app.yml                 # F-Droid build recipe draft template
├── LICENSE                             # GNU General Public License v3.0
├── THIRD_PARTY_LICENSES.md             # Third-party attribution & notices
├── FDROID_READINESS_CHECKLIST.md       # F-Droid compliance audit checklist
└── README.md                           # This documentation
```

---

## Building from Source

### Prerequisites:
- **JDK 17** (OpenJDK or Eclipse Temurin)
- **Android SDK Platform 35** and **Build Tools 35.0.0**
- **Node.js 20+** (only required if modifying the companion web UI in `src/`)

### 1. Build the Android Debug APK
Run the Gradle wrapper from the repository root:
```bash
./gradlew assembleDebug
```
Or run directly from inside the `android/` subfolder:
```bash
cd android
./gradlew assembleDebug
```

The resulting installable APK is generated at:
```text
android/app/build/outputs/apk/debug/app-debug.apk
```

### 2. Compile Web Assets (Optional)
If you modify the companion React interface in `src/`:
```bash
npm install
npm run build
cp dist/index.html android/app/src/main/assets/web/
rm -f android/app/src/main/assets/web/assets/index-*.js android/app/src/main/assets/web/assets/index-*.css
cp dist/assets/index-*.js dist/assets/index-*.css android/app/src/main/assets/web/assets/
```

---

## Running Tests

### 1. Execute Android JVM Unit Tests
Run the comprehensive unit test suite (covering IME policies, layout controllers, atomic persistence, binary migration, expiry locking, and history transactions):
```bash
./gradlew testDebugUnitTest
```
Or from the `android/` directory:
```bash
cd android
./gradlew testDebugUnitTest
```

### 2. Verify Release Metadata Consistency
Run the automated consistency check script to verify version declarations, license files, Fastlane metadata, and manifest invariants:
```bash
python3 scripts/check-version-consistency.py
```

---

## Reporting Bugs & Contributing

ClipMax is open-source software. Contributions, bug reports, and suggestions are welcome!

- **Issue Tracker**: File bug reports or feature suggestions at [GitHub Issues](https://github.com/ahmad-maxai/ClipMax-complete/issues).
- **Contributing**: Please open an issue to discuss proposed architectural changes before submitting large pull requests. Ensure all automated tests pass (`./gradlew testDebugUnitTest`) and that code adheres to the project's offline-only, zero-telemetry principles.

---

## Licensing

ClipMax is free software licensed under the **GNU General Public License v3.0 or later** ([`GPL-3.0-or-later`](LICENSE)).

All third-party libraries and runtime components are compatible open-source projects (Apache 2.0, MIT, ISC, EPL-1.0). See [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) for full notices and attributions.
