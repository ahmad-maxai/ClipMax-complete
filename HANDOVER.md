# ClipMax — Comprehensive Handover & Technical Architecture Manual
**Version:** 0.5.1  
**Target Platform:** Android 8.0+ (API 26 to 35 / Android 15 Edge-to-Edge) & React 19 SPA  
**Package Identifier:** `com.clipmax.app`  
**Repository Remote:** `https://github.com/ahmad-maxai/ClipMax-complete`  
**Latest Verified CI Run:** Build ClipMax APK (GitHub Actions)

---

## 1. Executive Summary & Purpose

ClipMax is a private, local-first clipboard manager and system Input Method Editor (IME) for Android. It enables users to securely store, organize, search, and instantly insert long-form text snippets and clipboard items across any application on their device (such as WhatsApp, Chrome, ChatGPT, Notes, and SMS) without cloud dependencies, user accounts, or telemetry.

This document serves as the complete, portable engineering manual and handover package for developers continuing work in any development environment (including Manus, Claude, Android Studio, or local CLI).

---

## 2. System Architecture & Directory Map

```
ClipMax/
├── android/                             # Native Android project (Gradle / Kotlin)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/clipmax/app/
│   │   │   │   ├── MainActivity.kt      # Host Activity with WebView & SAF file chooser
│   │   │   │   ├── AndroidBridge.kt     # JavaScript <-> Kotlin interface (ClipMaxNative)
│   │   │   │   ├── ClipMaxImeService.kt # Real native InputMethodService (System Keyboard)
│   │   │   │   ├── ClipStore.kt         # Atomic local storage (metadata + raw text)
│   │   │   │   ├── TextChunker.kt       # Surrogate-safe 50,000 UTF-16 code unit chunker
│   │   │   │   ├── CapturePreferences.kt# Retention and auto-capture settings store
│   │   │   │   └── CaptureExpiryReceiver.kt # BroadcastReceiver for scheduled card expiry
│   │   │   ├── res/                     # Layouts, drawables, XML IME config, mipmaps
│   │   │   ├── assets/web/              # Compiled React SPA assets (loaded by WebView)
│   │   │   └── AndroidManifest.xml      # App permissions, Activity, Service, Receiver
│   │   └── build.gradle.kts             # App build config (SDK 35, Java 17, Kotlin 2.0)
│   ├── build.gradle.kts                 # Root Gradle configuration
│   └── settings.gradle.kts              # Gradle project inclusions
├── src/                                 # Modern React SPA source
│   ├── App.tsx                          # Root router, state manager, and bridge event handler
│   ├── components/
│   │   ├── LibraryScreen.tsx            # Main clip collection, instant search, and file import
│   │   ├── DetailsScreen.tsx            # Full-text viewer, chunk copy, share, pin, delete
│   │   ├── EditorScreen.tsx             # Clip composer and editor with byte calculations
│   │   ├── SettingsScreen.tsx           # Storage overview, IME setup, retention, ZIP backup
│   │   ├── KeyboardPanelScreen.tsx      # In-app interactive IME test panel & preview simulation
│   │   ├── QwertyKeyboard.tsx           # 4-row soft keyboard interactive simulation
│   │   ├── ImeKeyboardScreen.tsx        # Legacy WebView-in-IME fallback screen
│   │   ├── primitives.tsx               # Reusable UI components (TopBar, Btn, Modal, Logo)
│   │   └── ui/Toast.tsx                 # Toast notification system
│   ├── lib/
│   │   ├── types.ts                     # TypeScript interfaces and constants
│   │   ├── storage.ts                   # Storage client (localStorage + AndroidBridge)
│   │   ├── backup.ts                    # ZIP archive creation and restore parser
│   │   ├── chunker.ts                   # Web surrogate-safe text chunking and byte formatting
│   │   ├── nativeBridge.ts              # TypeScript typing for window.ClipMaxNative
│   │   └── utils.ts                     # Tailwind class merge helper
│   ├── index.css                        # Tailwind v4 CSS imports & color tokens
│   └── main.tsx                         # React entry point
├── public/                              # Static public assets (logo, icons)
├── package.json                         # Node.js dependencies and build scripts
└── vite.config.ts                       # Vite build configuration
```

---

## 3. UI Component & Screen Inventory

| Screen / Component | Source File | Core Capabilities & Interactions |
|---|---|---|
| **LibraryScreen** | `src/components/LibraryScreen.tsx` | - Instant search across titles and full text with match count badge.<br>- Sorting (Last Modified, Newest, Oldest, Title A-Z).<br>- Filter chips: All clips vs. Pinned.<br>- Real document import supporting `.txt`, `.md`, `.json`, and `.zip` backups.<br>- Fast navigation to `editor` and `details`. |
| **DetailsScreen** | `src/components/DetailsScreen.tsx` | - Chunked clipboard copy (50,000 code units).<br>- Native Android share sheet integration (`Intent.ACTION_SEND`).<br>- Pin/unpin toggle with instant persistence.<br>- Delete confirmation dialog.<br>- Character and byte metadata counters. |
| **EditorScreen** | `src/components/EditorScreen.tsx` | - Text title and multi-line body editing.<br>- Live character count and UTF-8 byte length computation.<br>- Automatic draft saving (`localStorage` key `clipmax_draft_v1`).<br>- Title character cap (200 chars) and 16 MiB size limit checks. |
| **SettingsScreen** | `src/components/SettingsScreen.tsx` | - Real device storage statistics (Item count, Pinned count, Total bytes).<br>- Guided Android System Keyboard setup (Step 1: Settings, Step 2: Input Method Picker).<br>- Live keyboard test input area within Settings.<br>- Retention period selector (Off, 1h, 6h, 24h, 72h, Forever).<br>- Automatic capture toggle.<br>- ZIP backup export and file restore.<br>- Danger Zone: Delete all saved clips. |
| **KeyboardPanelScreen** | `src/components/KeyboardPanelScreen.tsx` | - In-app interactive test panel allowing verification of key inputs.<br>- Distinct "Interactive Preview Simulation" badge to prevent confusion with native IME.<br>- Test input field to verify typing and snippet insertion.<br>- Direct launcher for system keyboard settings. |
| **ClipMaxImeService** | `ClipMaxImeService.kt` | - Real Android `InputMethodService` registered in the OS.<br>- Full 4-row QWERTY keyboard layout + numeric row + symbols mode.<br>- Top toolbar with ClipMax wordmark, instant clip insertion, search key, and IME close.<br>- Insets-aware layout preventing navigation-bar crowding in edge-to-edge apps (Android 15 / ChatGPT). |

---

## 4. Feature Integration & Data Flow Map

### 4.1 Storage & Persistence
- **Dual-Layer Architecture**: When running inside Android WebView, `storage.ts` delegates to `window.ClipMaxNative.syncItems()` and `getItems()`, backed by Kotlin's atomic `ClipStore`. When running in standard web browsers, it operates via `localStorage`.
- **Atomic Two-Phase Writes**: `ClipStore.kt` writes the content file first (`.tmp`), flushes file descriptors, moves the file atomically to `content/`, and then writes a compact metadata file (`metadata/{id}.bin` or `{id}.json`). This guarantees that power interruptions never corrupt existing records.
- **Safe Identifiers**: IDs adhere to `[A-Za-z0-9._-]{1,64}`, supporting standard UUIDs, React generated IDs (`clip-...`), and restored IDs (`restored-...`).

### 4.2 Large Text & Chunked Insertion (Surrogate-Safe)
- Android IPC limits binder transactions to 1 MiB (`TransactionTooLargeException`).
- `TextChunker.kt` and `chunker.ts` slice text into chunks of **at most 50,000 UTF-16 code units**.
- If the 50,000 boundary lands on a high surrogate (`\uD800-\uDBFF`), the chunk is reduced by one character so emoji pairs and CJK characters are never split or corrupted.

### 4.3 Universal File Import & Backup Restore
- **Workflow**:
  1. User taps "Import file" on `LibraryScreen` or "Restore ZIP" on `SettingsScreen`.
  2. In Android WebView, `MainActivity`'s `WebChromeClient.onShowFileChooser` triggers Android's Storage Access Framework (`ACTION_GET_CONTENT` with `CATEGORY_OPENABLE`).
  3. The selected `Uri` is passed back to JavaScript via `ValueCallback<Array<Uri>>`.
  4. JavaScript reads the file using `FileReader` with UTF-8 encoding.
  5. If the file is a `.zip` archive, `restoreZipBackup` verifies the 128 MiB uncompressed limit, path traversal checks, parses `metadata/` and `content/`, and adds items.
  6. If the file is `.json`, it parses arrays of clip items and restores them.
  7. If the file is `.txt`, `.md`, or code, it creates a new `ClipItem` named after the file.
  8. `onUpdateItems` commits the items to React state, `localStorage`, and native `ClipStore`.

---

## 5. Known Issues & Audit Log

### 5.1 Import Function Failure (DIAGNOSED & RESOLVED)
- **Root Causes**:
  1. *Missing WebChromeClient*: `MainActivity.kt` only configured `WebViewClient`, omitting `WebChromeClient.onShowFileChooser`. Android WebView drops all `<input type="file">` clicks without this client.
  2. *Strict Regex in ClipStore*: `ClipStore.kt` restricted `safeId` to `[a-fA-F0-9-]{20,40}`. React clip IDs (`clip-m...`) failed validation.
  3. *Throw on Non-Existent ID*: `ClipStore.saveFromReader` threw `IllegalArgumentException("The item no longer exists.")` if `id != null` and the item was not already in storage, which caused `syncItems` to abort and fail on newly imported items.
  4. *Web FileReader*: `LibraryScreen.tsx` checked `!file.name.endsWith(".txt")`, reset `e.target.value = ""` synchronously before read completion, and lacked error listeners.
- **Fixes Applied**:
  - Implemented `WebChromeClient.onShowFileChooser` and `onActivityResult` with `REQUEST_SELECT_FILE` in `MainActivity.kt`.
  - Expanded `safeId` in `ClipStore.kt` to `[A-Za-z0-9._-]{1,64}` and updated `saveFromReader` to allow pre-assigned IDs for new items.
  - Upgraded `handleImportFile` in `LibraryScreen.tsx` to accept `.txt`, `.md`, `.json`, and `.zip` archives with resilient `FileReader` lifecycle handling.

### 5.2 Navigation Bar Insets & Edge-to-Edge Keyboard Layout (RESOLVED)
- **Issue**: On Android 15 edge-to-edge apps (e.g. ChatGPT), native keyboard bottom buttons overlapped with gesture bars.
- **Resolution**: Added `setOnApplyWindowInsetsListener` observing `WindowInsets.Type.navigationBars()` and set bottom padding dynamically in `ClipMaxImeService.kt` and `MainActivity.kt`.

### 5.3 Horizontal Key Clipping on Small Displays (RESOLVED)
- **Issue**: QWERTY keys clipped horizontally on narrow screens (320dp width).
- **Resolution**: Adjusted key margins to `dp(1)` and flexible weights in `ClipMaxImeService.kt`.

### 5.4 Main App Top Navigation Bar Styling (OUT OF SCOPE)
- Explicitly marked out of scope for keyboard and import fixes per user instruction.

---

## 6. Build & Execution Instructions

### 6.1 Web Development (Vite + React)
```bash
# Install dependencies
npm install

# Start development server
npm run dev

# Build production assets
npm run build
```

### 6.2 Synchronize Web Assets to Android
```bash
# Copy compiled web build to Android assets folder
rm -rf android/app/src/main/assets/web
cp -r dist android/app/src/main/assets/web
```

### 6.3 Android APK Compilation
```bash
# Compile debug APK using Gradle Wrapper
./gradlew assembleDebug

# Output APK path:
# android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. Regression Test Checklist

- [ ] **File Import (.txt)**: Tap "Import file" in Library, pick a text file. Verify item is created and content matches.
- [ ] **File Import (.zip)**: Tap "Import file" in Library, pick a ClipMax backup `.zip`. Verify clips are unpacked and listed.
- [ ] **JSON Import (.json)**: Pick a JSON array file. Verify clips are parsed and imported.
- [ ] **Persistence across restarts**: Force close the app and reopen. Verify imported items remain in the list.
- [ ] **Native IME activation**: Open Android Settings, enable ClipMax Keyboard, switch active input method.
- [ ] **System-wide typing**: Open WhatsApp or Notes, type with ClipMax. Verify keys, shift, symbols, and backspace.
- [ ] **Large snippet insertion**: Insert a clip >50,000 characters. Verify chunk progress bar and no crash.
- [ ] **Edge-to-edge insets**: Open ChatGPT or Chrome on Android 15. Verify space bar and bottom controls do not collide with gesture bar.
