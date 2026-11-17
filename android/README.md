# ClipMax v0.5.1

ClipMax is an offline-first native Android clipboard library for saving, finding, editing, importing, and reusing large plain-text items. It stores the text in private app files rather than depending on Android's system clipboard for persistence.

## Features

- Create, edit, rename, duplicate, pin, and delete named text items.
- Save multiline Unicode and emoji text locally; show character and stored-byte counts.
- Search item titles and content; sort by recently modified, newest, oldest, or title.
- Paste text into ClipMax on demand; copy or share a saved item explicitly.
- Select **ClipMax keyboard** inside another app and insert a saved item from device storage in bounded 50,000-UTF-16-unit chunks, with progress and cancellation.
- While ClipMax is active, automatically capture selection updates from the focused editable field and Android text-copy events; it also checks the current clipboard when the keyboard opens, so a copy made just before switching can be captured.
- Save each captured copy as separate, serially numbered cards (for example, `Chunk 001/004`), each containing up to 50,000 UTF-16 code units. Search titles and card text from within the keyboard using its built-in search keypad. Scroll all cards in the fixed-height keyboard; there is no 15-card pagination cap. Tap a card's **More** button or long-press it to reveal **Edit**, **Pin/Unpin**, and **Delete** controls in the keyboard.
- Choose automatic capture retention in Settings: keep cards forever, delete them after 1, 6, 24 or 72 hours, or turn automatic capture off.
- Import and export `.txt` through Android's Storage Access Framework.
- Create and merge-restore a ZIP backup of the library.
- Debounced local draft recovery for in-progress edits.
- Light/dark appearance follows the Android system theme.
- Uses the supplied ClipMax artwork as a square, squircle-masked launcher icon (adaptive and legacy density resources).
- No account, analytics, advertising, network access, or keystroke logging. Clipboard monitoring is limited to an active, non-password ClipMax input session. Automatic capture is enabled by default with 24-hour retention and can be disabled.

## Data and architecture

The package/application ID is `com.clipmax.app`. `MainActivity` is a native Android activity with library, editor, and settings screens. `ClipMaxImeService` is an Android input method protected by `android.permission.BIND_INPUT_METHOD`. While active in an editable field, it can read selections within that focused field through `InputConnection.getSelectedText`; this does **not** expose a selection made in a separate read-only message/article view. For read-only content, the user must invoke that app's **Copy** action. ClipMax listens for clipboard-change events while active and checks the current clipboard on keyboard open to catch a copy made immediately before switching. Android/source apps may still hide, delay, truncate, or withhold clipboard data. Captured text is split into independently insertable, serially labelled cards and written as UTF-8 to ClipMax's private storage. The source selection or clipboard can still be delivered as an in-memory object first, so this is not zero-RAM and cannot defeat source-app, system clipboard, or IPC limits. Saved cards are inserted with up to 50,000 UTF-16 code units per `InputConnection.commitText` call. The keyboard includes a self-contained search keypad that filters saved titles and content; its card list scrolls within the fixed-height input view. Tap **More** or long-press a card to reveal Edit, Pin/Unpin, and Delete controls. The editor offers Android's input-method picker so users can switch to a full typing keyboard when editing. ClipMax does not collect keystrokes or read text surrounding a selection. Timed cards carry a per-card expiry; an inexact Android alarm removes due cards and is re-armed after boot. Android may delay inexact alarms during Doze; expired items are also cleaned when the app or keyboard next opens.

App data is private to ClipMax and is removed by Android when the app is uninstalled. Android Auto Backup is disabled in the manifest; use ClipMax's own ZIP backup/export to keep a user-controlled copy. ClipMax does not encrypt its private files independently of Android's device/user storage protections.

## Supported limits and caveats

- The storage/import limit is **16 MiB per saved item** (UTF-8 bytes); the implementation does not claim unlimited capacity.
- ZIP backup restore is capped at **128 MiB of uncompressed archive contents**.
- The editor uses a conventional Android text-editing widget and holds an open document in memory. Very large documents can use substantial memory and may be slow on Android Go; a successful storage-layer test is not a guarantee of smooth editing on every phone.
- The insert keyboard sends at most 50,000 UTF-16 units per `commitText` operation (usually 50,000 Unicode characters, or fewer when emoji use surrogate pairs). The destination app may still cap, reject, or slow large text; verify the result. Cancel stops future chunks, but cannot undo chunks already inserted.
- Direct selection capture is available for selected text inside the focused editable field only. Selecting text in a separate read-only response does not expose it to the keyboard; use that app's **Copy** action instead. The clipboard listener/startup check receives only text Android/source apps actually place on the clipboard and cannot recover source-side truncation. Some apps do not provide clipboard data to the IME. ClipMax cannot intercept every app's Copy implementation or guarantee unlimited capture.
- **Copy** and **Share** still use Android's normal clipboard/intent paths and may hit size limits. Use the ClipMax keyboard for large inserts.
- ClipMax disables capture and hides saved items in recognized password fields. Android requires you to enable and select ClipMax as an input method. With automatic capture enabled, selected/copied text observed during an active ClipMax input session is saved locally, so use the retention/off setting on shared devices.
- Search is performed on a background thread and scans saved content. It is submitted explicitly rather than processing the full library on every keystroke.
- The in-keyboard search keypad provides Latin letters, digits, and common punctuation; matches are case-insensitive substring searches.
- If Android reports insufficient storage, a file operation can fail; leave free device space and retry. Keep a separate backup for important data.
- The MVP is plain text only. It does not preserve rich-text styling, monitor the clipboard outside an active ClipMax input session, sync to a cloud, or provide a database index for very large libraries.

## Installation on Android

1. Download `ClipMax-v0.5.1-debug.apk` from the delivery files.
2. Transfer it to the Android phone and open it with the package installer. Android may ask you to allow installation from that file app; allow it for this installation, then complete the install.
3. Launch **ClipMax**. No account or broad storage permission is required.
4. Use **New item** to type/paste and save text. Use **Import .txt** or **Settings → Create library backup** for file operations.
5. Choose **Settings → Automatic capture** to set retention or turn it off. For a read-only response/article, invoke that app's **Copy** action; ClipMax captures Copy events while active and checks the current clipboard when the keyboard opens. Captured text appears as ordered cards, each up to 50,000 UTF-16 units; labels show the chunk number and total. Use **Search** on the keyboard and its built-in keypad to filter by text. Tap **More** or long-press a card to reveal **Edit**, **Pin/Unpin**, and **Delete**. In the editor, use **Choose a typing keyboard** if you need a full QWERTY keyboard. Some apps/Android versions may not expose clipboard data to keyboards.

The APK is a debug build, signed with the SDK's development debug key; it is not signed with a production/Play Store key. Android 8.0 (API 26) or later is required; the project targets API 35 (Android 15).

## Build from source

Requirements: JDK 17 or later, Android SDK platform 35 and Build Tools 35.0.0, and internet access for the first Gradle dependency download.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The project uses Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21, compile/target SDK 35, and min SDK 26. No third-party runtime libraries are included; JUnit 4 is used for JVM tests.

## Verification status

See `TEST_REPORT.md` for the executed test/build results, SHA-256, and any emulator or physical-device testing limitations. Only completed checks are reported as passed.
