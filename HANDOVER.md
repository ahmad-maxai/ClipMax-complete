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

### 5.5 Native Keyboard Polish & Ergonomics Pass (RESOLVED)
- **Dedicated Clipboard Interface & Toolbar**: Added a prominent `📋 Clips` toggle button to the top toolbar. When tapped, it switches into the full saved-clipboard panel inside the keyboard where users can browse, search, chunk-insert, pin/unpin, edit (launches `MainActivity.EXTRA_EDIT_ITEM_ID`), and delete with inline confirmation.
- **Gesture Navigation Clearance**: Solved bottom key overlap on Android 10-15 edge-to-edge layouts using `updateWindowInsets(insets)` calculating `maxOf(navBars, systemGestures, dp(12))`. Keys and space bar remain completely visible without resting under the gesture handle, while avoiding excessive padding on 3-button nav devices.
- **Dynamic In-App Light/Dark Theme Sync**: Added `setTheme` and `getTheme` to `AndroidBridge.kt` and `nativeBridge.ts`. The React app notifies the bridge on theme changes (`clipmax_theme_prefs`), and `ClipMaxImeService` listens via `SharedPreferences.OnSharedPreferenceChangeListener`, dynamically updating colors without app restarts.
- **Tactile Key Feedback & Letter Popups**: Implemented Gboard-style floating key preview popups positioned via `translationX`/`translationY` inside an unclipped wrapper container, paired with native `HapticFeedbackConstants.KEYBOARD_TAP` on all alphanumeric keys, modifiers, and repeating backspace.
- **In-App Preview Parity**: Synchronized `KeyboardPanelScreen.tsx` toolbar with the dedicated `📋 Clips` toggle button for complete behavioral and visual parity between preview and native IME.

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
- [ ] **Key preview popups & haptics**: Press letter keys. Verify fast letter popups float above key and subtle tap vibration occurs.
- [ ] **Dedicated "📋 Clips" toggle**: Tap "📋 Clips" in the keyboard toolbar. Verify full clipboard browser opens inside keyboard.
- [ ] **Clip operations inside IME**: Verify search, pin/unpin, edit (opens app editor), and delete with inline confirmation work in keyboard.
- [ ] **Compact Expandable Cards**: Verify collapsed cards are ~50dp tall showing 3-4 cards at once. Tapping chevron expands to show Insert, Edit, Pin, Delete.
- [ ] **Key Popup Clipping**: Tap Row 1 keys (Q, W, E, R, T, Y, U, I, O, P). Verify popups float above keys without clipping against the top toolbar or screen boundaries.
- [ ] **Typing: Host vs Filter**: Toggle toolbar mode button. Verify keys filter clips without leaking into host text field; verify Host mode types into target app.
- [ ] **Main App Header Height**: Open the main ClipMax application. Verify top navigation bar is compact (44px), without double status-bar spacing.
- [ ] **Theme synchronization**: Change theme between Light and Dark in ClipMax Settings. Open keyboard and verify theme adapts immediately.
- [ ] **Large snippet insertion**: Insert a clip >50,000 characters. Verify chunk progress bar and no crash.
- [ ] **Edge-to-edge insets**: Open ChatGPT or Chrome on Android 15. Verify space bar and bottom controls do not collide with gesture bar.

---

## 8. Refinement Pass 2 Architecture Notes

### 8.1 Main App Top Navigation Bar
- **Source**: `src/components/primitives.tsx` (`TopBar`, `IconBtn`, `Wordmark`).
- **Issue**: Android FrameLayout in `MainActivity.kt` already applied `sysBars.top` to WebView container. `TopBar` previously added redundant `pt-[env(safe-area-inset-top,0px)]` and `h-14` (56px), resulting in excessive vertical header space.
- **Resolution**: Removed `pt-[env(safe-area-inset-top,0px)]`, reduced height to `h-11` (44px), reduced icon button dimensions to `h-8 w-8` (`size="iconSm"`), and reduced wordmark logo to `size={24}` and text to `text-[15px]`. This reduced header height by 54% while maintaining full touch targets.

### 8.2 Native Key Popup Unclipping
- **Source**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt` (`onCreateInputView`, `showKeyPreview`).
- **Issue**: `keyPopupView` was originally constrained inside `keyboardWrapper: FrameLayout`. When row 1 keys were tapped, popup translations clipped against the wrapper boundary.
- **Resolution**: Re-architected `imeRootFrame` as an unclipped `FrameLayout` (`clipChildren=false`, `clipToPadding=false`) with `keyPopupView` attached at `elevation=12f`. Popups compute absolute translation relative to root coordinates and clamp within `[2dp, rootWidth - targetWidth - 2dp]`.

### 8.3 Native Toolbar Simplification & Typing Modes
- **Source**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt` (`buildToolbar`, `updateModeUI`).
- **Removed**: Redundant `switchIme` and `hideBtn` buttons that duplicated Android 15 system navigation bar controls.
- **Added**: Real `Typing: Host` / `Typing: Filter` mode toggle button. In `Typing: Filter` mode, key presses update `keyboardSearchQuery` and filter clips in real-time with zero character leakage into `currentInputConnection`. In `Typing: Host` mode, keys commit directly to host text fields.

### 8.4 Compact Expandable Cards
- **Source**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt` (`buildExpandedCard`).
- **Collapsed**: ~50dp height with Title, star icon, expand chevron (`⌄`), single-line monospace preview, and character count / size metadata. 3 to 4 cards visible at a glance in portrait mode.
- **Expanded**: Smoothly reveals divider and action buttons (`↵ Insert`, `✎ Edit`, `★ Pin`, `🗑 Delete` with inline confirmation).

---

## 9. Saved Clips UI Professionalization Pass (Pass 3)

### 9.1 Genuinely Compact Cards Layout & Measurements
- **Target Portrait Screen Viewport**: 208dp available scroll height within the ~220–250dp IME panel.
- **Subheader Bar**: Reduced to `24dp` height with `10sp` title and compact `20dp` "Keyboard" switch button.
- **Collapsed Card Height**: `51dp` total (`4dp` vertical padding top/bottom, `18dp` header, `13dp` single-line monospace preview, `12dp` metadata row, `3dp` bottom margin).
  - **Simultaneous Visibility**: 4 collapsed cards fit completely within the standard portrait scroll viewport.
- **Expanded Card Height**: `94dp` total (`4dp` vertical padding top/bottom, `18dp` header, `24dp` 2-line monospace preview, `12dp` metadata row, `7dp` divider, `22dp` action buttons row, `3dp` bottom margin).
  - **Simultaneous Visibility with Expansion**: 1 expanded card (`94dp`) + 2 collapsed cards (`51dp` each) = `196dp` <= `208dp`. At least **3 cards are visible simultaneously** (1 expanded + 2 collapsed) in normal portrait mode. Even on extreme low-height landscape windows, at least 2 cards are always guaranteed visible.
- **Single Expansion Rule**: Only one card can expand at a time (`expandedCardId == item.id`). Expanding another card automatically collapses the previous one.
- **Action Buttons Row**: 4 equal-width buttons (`weightSum = 4f`, `weight = 1f` each, height `22dp`): `Insert`, `Edit`, `Pin`/`Unpin`, `Delete`. Buttons never clip, wrap, or overflow on narrow screens.
- **Inline Delete Confirmation**: Replaces the action row with a matching `22dp` row containing a prompt ("Delete clip?"), "Cancel", and solid red "Delete" button, avoiding layout jumps.

### 9.2 Complete Unicode Emoji Elimination & Vector Iconography
- **Programmatic Vector Engine**: Implemented `IconDrawable` extending `Drawable` using hardware-accelerated `Canvas` path drawing (`strokeWidth = 1.6f`, `strokeCap = Paint.Cap.ROUND`, `strokeJoin = Paint.Join.ROUND`).
- **Icon Types**: `CLIPBOARD`, `PIN`, `PIN_FILLED`, `KEYBOARD`, `SEARCH`, `INSERT`, `EDIT`, `DELETE`, `CHEVRON_DOWN`, `CHEVRON_UP`, `CLOSE`, `LOCK`.
- **Theme-Aware Tinting**: Icons dynamically adopt theme colors (`colorAccentText`, `colorModText`, `colorKeyText`, `colorHintText`, red `0xFFF87171`).
- **Total Removal of Unicode Emojis**:
  - Removed `📋` from Clips button and headers.
  - Removed `★` and `☆` from Pinned buttons and card headers (replaced with clean pin vector).
  - Removed `⌨` from ABC Keyboard button (replaced with keyboard vector).
  - Removed `↵` from Insert button (replaced with return-arrow vector).
  - Removed `✎` from Edit button (replaced with pencil vector).
  - Removed `🗑` from Delete button (replaced with trash can vector).
  - Removed `🔒` from privacy/password banners (replaced with lock vector).
  - Removed `⌃` and `⌄` text chevrons (replaced with chevron vector).
  - Removed `✕` character crosses (replaced with clean close vector and Lucide `<X />`).

### 9.3 Newest-First Ordering & Chunk Sequence Preservation
- **Sorting Algorithm**:
  `compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.createdAt }.thenBy { it.title }`
- **Application Across Stack**:
  1. `ClipStore.kt`: `listItems()` now returns stored clips sorted by `pinned` (descending) -> `createdAt` (descending) -> `title` (ascending). `search()` queries this sorted dataset.
  2. `ClipMaxImeService.kt`: `reloadItems()` and `runKeyboardSearch()` strictly maintain `createdAt` descending, keeping newly captured clips at the top immediately.
  3. `AndroidBridge.kt`: Native `getItems()` bridge provides already-sorted data directly to the web app.
  4. `src/lib/storage.ts`: `sortClips()` defaults to `"newest"` (`b.createdAt - a.createdAt`), with `a.title.localeCompare(b.title)` tiebreaker. `getStoredItems()` sorts on read.
  5. `LibraryScreen.tsx` & `KeyboardPanelScreen.tsx`: Default sort key set to `"newest"`.
- **Chunk Order Invariance**: Chunks belonging to the same large clipboard capture share the same `createdAt` timestamp. The secondary tiebreaker `thenBy { it.title }` guarantees `Chunk 001/002` precedes `Chunk 002/002`, preserving sequential integrity.
- **Unit Test**: Added `testNewestFirstOrderingAndChunkGrouping` in `ClipStoreTest.kt` verifying `[C, B, A]` ordering for sequential clips and `Chunk 001` before `Chunk 002` for multi-chunk captures.

