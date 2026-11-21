# CLIPMAX — PHASE 0: FULL TECHNICAL AUDIT & REFACTORING BLUEPRINT

**Document**: Phase 0 Comprehensive Technical Audit & Architectural Blueprint  
**Target Repository**: `ahmad-maxai/ClipMax-complete`  
**Target Branch**: `feat/native-keyboard-clipboard-redesign`  
**Execution Mode**: Inspection Only — Zero Code Modification  
**Audit Scope**: Native Android Kotlin IME, Native Data Storage & Capture Engine, Web-to-Native Bridge, and In-App Components  

---

## 1. Executive Summary

A comprehensive architectural and forensic audit was conducted on the ClipMax Android codebase across the native Kotlin Input Method Editor (`ClipMaxImeService.kt`, `InsertionHistoryManager.kt`, `CapturePreferences.kt`, `ClipStore.kt`, `AndroidBridge.kt`, `MainActivity.kt`) and companion web client modules.

### Key Audit Findings
1. **Targeting Mismatch in Previous Fix Attempts**:
   Previous remediation cycles made adjustments in the React web mockup layer (`src/components/ui/Toast.tsx`, `src/components/SettingsScreen.tsx`, and `src/components/LibraryScreen.tsx`). However, the defects reported by the user occur within the **actual native Android Kotlin IME** (`com.clipmax.app.ClipMaxImeService`), where full view hierarchies are destroyed and recreated upon state transitions.
2. **Premature Dismissal of Merge/Split Undo/Redo Notifications**:
   In `ClipMaxImeService.kt`, invoking `performMerge()` or `splitItem()` executes an asynchronous background disk transaction via `ClipStore`. Upon completion, the main thread schedules `reloadItems()`, which triggers `renderFullImeView()`. Line 483 of `renderFullImeView()` immediately executes `dismissUndoRedoControl()`. Consequently, the Undo/Redo notification banner is destroyed within 10–50 milliseconds by the asynchronous completion of the very data reload initiated by the operation.
3. **Scroll Resets on Merge Activation & Settings Toggles**:
   Both the expandable clipboard panel (`buildClipboardPanel()`) and settings interface (`buildSettingsPanel()`) in `ClipMaxImeService.kt` are rebuilt from scratch via `content.removeAllViews()` and fresh `ScrollView` instantiations whenever a card action or settings toggle is clicked. Zero scroll offset (`scrollY`) is preserved.
4. **Clipboard Paste Undo/Redo Inactivity Regression**:
   `InsertionHistoryManager.canSafelyUndo()` requires `gateway.textBeforeCursor()` to match the full length of the inserted text plus an anchor buffer (`requestLen = entry.insertedText.length + entry.beforeAnchor.length`). In Android host applications (e.g., Chrome, WhatsApp, text editors), `InputConnection.getTextBeforeCursor()` is capped by host-app buffers (often 256–1024 characters). For any clip exceeding that buffer, `before.length < entry.insertedText.length` fails immediately with `"Inserted text or cursor position changed."`, permanently disabling Undo even immediately following an insertion.
5. **Non-Functional Clip Expiry Feature**:
   `ClipItem` in `ClipStore.kt` contains no `expiresAt` property. When items are created, edited, or synced from the application via `AndroidBridge.syncItems()`, no expiry timestamps are recorded in `CapturePreferences`. When `CaptureExpiryReceiver` or `cleanupExpired()` runs, it only inspects `SharedPreferences` keys prefixed with `expiry_`. Because none exist for user-created, imported, or synced clips, clips are never expired by the engine.

---

## 2. Previous Failed Fixes: Exact Root Causes

### 2.1 Split/Merge Undo and Redo Notification Disappearing
- **Primary Source File**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Responsible Methods**: `performMerge()`, `splitItem()`, `reloadItems()`, `renderFullImeView()`, `showUndoRedoControl()`, `dismissUndoRedoControl()`
- **Code Evidence**:
  ```kotlin
  // ClipMaxImeService.kt line 1799 (inside performMerge completion callback)
  main.post {
      setStatus("Merged 2 clips into 1.")
      reloadItems()              // <--- Triggers async disk read on background thread
      showUndoRedoControl()     // <--- Shows floating Undo/Redo bar on screen
  }
  ```
  ```kotlin
  // ClipMaxImeService.kt line 3245 (inside reloadItems async callback)
  main.post {
      ...
      } else if (isClipboardPanelOpen) {
          renderFullImeView()   // <--- Fires 10-50ms later when disk read completes!
      }
  }
  ```
  ```kotlin
  // ClipMaxImeService.kt line 480
  private fun renderFullImeView() {
      val content = contentContainer ?: return
      dismissContextMenu(animate = false)
      dismissUndoRedoControl()  // <--- LINE 483: Destroys the Undo/Redo control immediately!
      ...
      content.removeAllViews()
  ```
- **Forensic Diagnosis**:
  When `performMerge` or `splitItem` completes, `showUndoRedoControl()` mounts the floating horizontal control bar (`undoRedoHost`). Concurrently, `reloadItems()` delegates `store.listItems()` to an `ExecutorService` worker thread. When the disk read finishes milliseconds later, the callback on the main Looper executes `renderFullImeView()`. Line 483 explicitly calls `dismissUndoRedoControl()`. The control is destroyed before the user can see or tap it.
- **Secondary Touch Hazard**:
  Line 417 of `dispatchTouchEvent()` checks if a touch falls outside `undoRedoHost` bounding box:
  ```kotlin
  if (ev.action == MotionEvent.ACTION_DOWN && undoRedoHost != null) {
      val host = undoRedoHost
      if (host != null && host.visibility == View.VISIBLE) {
          val rect = android.graphics.Rect()
          host.getGlobalVisibleRect(rect)
          if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
              dismissUndoRedoControl()
          }
      }
  }
  ```
  When the user taps "Undo", `performUndo()` also calls `reloadItems()`, which triggers another `renderFullImeView()` teardown.

---

### 2.2 Merge Selection and Unexpected Scrolling to Top
- **Primary Source File**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Responsible Methods**: `buildClipboardPanel()`, `renderFullImeView()`, context menu action for "Merge" (lines 2138–2147)
- **Code Evidence**:
  ```kotlin
  // ClipMaxImeService.kt lines 2138-2147
  addActionRow(label = "Merge", iconType = IconType.MERGE, tint = colorKeyText) {
      dismissContextMenu(animate = true)
      isMergeMode = true
      mergeSourceId = targetId
      setStatus("Merge Mode: Tap another card to merge with \"${item.title}\"")
      reloadItems() // <--- Triggers reloadItems -> renderFullImeView()
  }
  ```
  ```kotlin
  // ClipMaxImeService.kt lines 487 & 1538
  content.removeAllViews() // <--- Destroys previous View hierarchy
  ...
  val scrollView = android.widget.ScrollView(this@ClipMaxImeService).apply { ... } // <--- Creates fresh ScrollView
  clipboardScrollView = scrollView
  ```
- **Forensic Diagnosis**:
  Tapping "Merge" in the card context menu sets `isMergeMode = true` and `mergeSourceId = targetId`. It then calls `reloadItems()`, which completes and calls `renderFullImeView()`. `renderFullImeView()` purges all views via `content.removeAllViews()` and instantiates a brand new `ScrollView`. The previous `scrollY` coordinate is discarded, resetting the viewport to the top of the list (`scrollY = 0`).

---

### 2.3 Assistant Settings Toggle and Scroll Preservation
- **Primary Source File**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Responsible Methods**: `buildSettingsPanel()`, `addBooleanSettingRow()` (lines 1245–1290)
- **Code Evidence**:
  ```kotlin
  // ClipMaxImeService.kt lines 1281-1286
  setOnClickListener {
      themePreferences.edit().putBoolean(prefKey, value).apply()
      onTypingSettingsChanged()
      renderFullImeView() // <--- Tears down entire IME view hierarchy
  }
  ```
- **Forensic Diagnosis**:
  Every toggle inside `buildSettingsPanel()` (Theme, Keyboard Height, One-Handed Mode, Autocomplete, Autocorrect, Suggestions) calls `renderFullImeView()`. As in the clipboard panel, `renderFullImeView()` calls `content.removeAllViews()` and creates a brand-new `ScrollView` inside `buildSettingsPanel()`. There is no scroll position tracking or restoration, resetting the viewport to `0` on every toggle click.

---

## 3. Clipboard Paste Undo/Redo Regression: Detailed Diagnosis

### 3.1 Over-Strict Surrounding Text Validation (`getTextBeforeCursor`)
- **Primary Source File**: `android/app/src/main/java/com/clipmax/app/InsertionHistoryManager.kt`
- **Responsible Function**: `canSafelyUndo()`
- **Code Evidence**:
  ```kotlin
  // InsertionHistoryManager.kt lines 152-158
  val requestLen = entry.insertedText.length + entry.beforeAnchor.length
  val before = gateway.textBeforeCursor(requestLen)
      ?: return Availability(false, "App did not expose surrounding text.")
  if (before.length < entry.insertedText.length || !before.endsWith(entry.insertedText)) {
      return Availability(false, "Inserted text or cursor position changed.")
  }
  ```
- **Forensic Diagnosis**:
  `canSafelyUndo()` requires `textBeforeCursor(requestLen)` to return a string at least as long as `entry.insertedText`. However, standard Android `InputConnection` implementations in external apps (such as WhatsApp, Chrome, Gmail, and WebViews) clamp `getTextBeforeCursor()` to an internal buffer (typically 256–1024 code units). If a pasted clip is 800 characters, `requestLen` is 816 characters. If the host application only supplies the last 256 characters, `before.length < entry.insertedText.length` evaluates to true, and Undo is immediately rejected with `"Inserted text or cursor position changed."`.

### 3.2 Target Invalidation on Focus/Field Re-Entry
- **Primary Source File**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Responsible Function**: `onStartInput()`
- **Code Evidence**:
  ```kotlin
  // ClipMaxImeService.kt lines 3486-3491
  currentEditorTarget = InsertionHistoryManager.EditorTarget(
      packageName = attribute.packageName,
      fieldId = attribute.fieldId,
      inputType = attribute.inputType
  )
  insertionHistory.clearForTargetChangeIfNeeded(currentEditorTarget)
  ```
- **Forensic Diagnosis**:
  In many dynamic WebViews and Jetpack Compose text fields, `attribute.fieldId` is unassigned (`-1` or `View.NO_ID`). When toggling between toolbar states or when the host application restarts input, `clearForTargetChangeIfNeeded` detects a mismatched target signature and purges the active undo stack.

### 3.3 State Separation: Merge/Split History vs. Paste Insertion History
- **Investigation Finding**:
  In `ClipMaxImeService.kt`:
  - Card Merge/Split operations are recorded in `undoStack` / `redoStack` as `OperationSnapshot` instances (lines 180–181).
  - Clipboard text insertions and autocorrect events are recorded in `insertionHistory` (`InsertionHistoryManager.kt`) as `HistoryEntry` instances.
  - In Toolbar State B (secondary toolbar), `stateBUndoBtn` and `stateBRedoBtn` are hardwired exclusively to `performInsertionUndo()` and `performInsertionRedo()`. They do not observe or manipulate `undoStack` (Merge/Split operations).
  - Merge/Split history was delegated to a floating bottom overlay (`undoRedoHost`) that is killed by `reloadItems()`.

---

## 4. Existing Implementation Status for All 11 Requirements (+ Expiry)

### Requirement 1: Fixed Keyboard Height Viewport
- **Status**: **IMPLEMENTED BUT BROKEN**
- **Files**: `ClipMaxImeService.kt`, `KeyboardLayoutSpec.kt`
- **Existing Behavior**: Standard typing height is derived from `KeyboardLayoutSpec.computeGeometryDp()` (~242dp). `buildClipboardPanel()` (line 1480) calculates height as `minOf(dp(276), screenH * 0.38f)`, while `buildSettingsPanel()` (line 966) uses `minOf(dp(258), screenH * 0.36f)`. Switching panels causes the overall IME window height to jump and resize rather than scrolling internally within a fixed viewport.
- **Required Change**: Bind `buildClipboardPanel()` and `buildSettingsPanel()` heights to `currentLayoutGeometry.totalHeightDp` so the IME window dimensions remain completely fixed across typing, clipboard, and settings modes.
- **Verification Test**: Measure IME window height across QWERTY typing, Clipboard panel, and Settings panel in portrait and landscape. Window height must be identical.

---

### Requirement 2: Dedicated Numeric Keypad
- **Status**: **NOT IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: `renderNumericRows()` (lines 2662–2700) displays a standard 4-row text symbol layout with numbers `1 2 3 4 5 6 7 8 9 0` in a single horizontal row across the top.
- **Required Change**: Introduce a dedicated Gboard-style 3x4 / 4x3 telephone/calculator numeric keypad layout (keys 1–9, 0, decimal, backspace, and action key) accessible via the number/symbol layer.
- **Verification Test**: Tap `?123` / numeric switcher. Verify a dedicated 3x4 numeric keypad appears with large touch targets.

---

### Requirement 3: Clip Card Redesign
- **Status**: **PARTIALLY IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: `buildCompactCard()` (lines 2221–2320) renders nested boxes (an inner `snippetBox` with `colorSnippetBg` inside an outer card `LinearLayout`). Redundant metadata badges are displayed, and sequential card indices (`#1`, `#2`, `#11`) are absent.
- **Required Change**: Redesign `buildCompactCard()` to render a single, flat, unnested card surface. Prominently display copied text and add clear sequential index badges (`#1`, `#2`).
- **Verification Test**: Open clipboard panel. Confirm cards display as clean single surfaces with prominent text, clear index numbers, and no nested boxes.

---

### Requirement 4: Dynamic Action Key
- **Status**: **PARTIALLY IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: `handleEnterPress()` (line 3159) checks `EditorInfo.IME_MASK_ACTION` and calls `performEditorAction(action)`. However, `createEnterKey()` (line 3024) is visually hardcoded to `IconType.ENTER` (standard carriage return arrow). It does not adapt its icon or label for `IME_ACTION_SEARCH`, `IME_ACTION_SEND`, `IME_ACTION_GO`, `IME_ACTION_NEXT`, or `IME_ACTION_DONE`.
- **Required Change**: Inspect `currentInputEditorInfo?.imeOptions` during key rendering and dynamically assign the appropriate icon (`SEARCH`, `SEND`, `NEXT`, `DONE`, `ENTER`) and accent background.
- **Verification Test**: Focus a Search field (confirm magnifying glass), focus a WhatsApp message field (confirm Send icon), and focus a multi-line note (confirm Return arrow).

---

### Requirement 5: Caps Lock
- **Status**: **PARTIALLY IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: `handleShiftPress()` (line 3170) checks `now - lastShiftTapTime < 300` for double-tap Caps Lock (`shiftState = 2`). However, 300ms is too narrow for reliable operation on touchscreens, and auto-capitalization logic resets manual shift overrides unexpectedly on field transitions.
- **Required Change**: Expand double-tap threshold to 400ms, decouple Caps Lock from transient auto-capitalization resets, and provide a persistent visual state indicator.
- **Verification Test**: Double-tap Shift; verify Caps Lock engages and remains active through multi-word typing until explicitly tapped again.

---

### Requirement 6: Secondary Toolbar & Snippets
- **Status**: **PARTIALLY IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: State B exists (lines 800–950), but contains 7 cramped controls in a horizontal scroll. Undo and Redo buttons frequently show as disabled due to the buffer check failure in `canSafelyUndo()`.
- **Required Change**: Streamline State B controls, ensure minimum 48dp touch targets, and restore reliable live Undo/Redo state calculation.
- **Verification Test**: Toggle to State B; confirm Back, Pinned, Search, Settings, and Undo/Redo are properly spaced and responsive.

---

### Requirement 7: Long-Press Comma (Emoji Panel)
- **Status**: **NOT IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: Line 2653 adds `createStandardKey(",")`. There is no long-press detection, and normal tap only commits `,`.
- **Required Change**: Attach `attachDualFunctionTouchFeedback()` or a long-press controller to `,` to open an emoji picker panel, while ensuring short tap inputs `,` without race conditions.
- **Verification Test**: Tap `,` (inserts comma). Long press `,` (opens emoji selector without inserting comma).

---

### Requirement 8: Settings Interface
- **Status**: **IMPLEMENTED BUT BROKEN**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: `buildSettingsPanel()` (lines 958–1300) contains Theme, Keyboard Height, One-Handed Mode, and Typing Assistance. However, tapping any toggle rebuilds the view and resets scroll position to the top.
- **Required Change**: Implement in-place state updating for toggle buttons without tearing down the parent view, and preserve `scrollY` across interactions.
- **Verification Test**: Scroll to bottom of Settings, toggle Autocomplete on/off multiple times; confirm scroll position remains stationary.

---

### Requirement 9: Clipboard Auto-Save Toggle
- **Status**: **NOT IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: The auto-save capture toggle exists only in the React `SettingsScreen.tsx`. `buildClipboardPanel()` in the native IME has no master toggle.
- **Required Change**: Add an explicit Auto-Save toggle switch directly inside the header of `buildClipboardPanel()`, connected to `CapturePreferences`.
- **Verification Test**: Toggle Auto-Save off in clipboard panel; copy text in another app; verify clip is not added. Toggle on; verify copying text captures clip.

---

### Requirement 10: System Theme Synchronization
- **Status**: **PARTIALLY IMPLEMENTED**
- **Files**: `ClipMaxImeService.kt`
- **Existing Behavior**: `isNightMode` (line 220) checks `app_theme`. When set to `"graphite"`, it forces dark mode regardless of the device's system theme. There is no explicit `"system"` setting that follows Android system day/night changes.
- **Required Change**: Add `"system"` as a first-class theme option that monitors `resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK`.
- **Verification Test**: Set theme to System; toggle Android system Dark Theme in quick settings; confirm keyboard dynamically adapts colors.

---

### Requirement 11: In-App Simulator Removal
- **Status**: **NOT IMPLEMENTED**
- **Files**: `src/App.tsx`, `src/components/LibraryScreen.tsx`, `src/components/SettingsScreen.tsx`, `src/components/KeyboardPanelScreen.tsx`
- **Existing Behavior**: The web bundle packaged into the APK contains the full web simulator (`KeyboardPanelScreen.tsx`, `ImeKeyboardScreen.tsx`, `QwertyKeyboard.tsx`) and navigation shortcuts to them.
- **Required Change**: Remove the simulator routes and test panel buttons from production builds while maintaining the native Android keyboard service.
- **Verification Test**: Verify the APK build contains no user-facing simulator views or broken navigation links.

---

### Additional: Clip Expiry Time Non-Functionality
- **Status**: **NOT IMPLEMENTED**
- **Files**: `ClipStore.kt`, `CapturePreferences.kt`, `AndroidBridge.kt`, `storage.ts`
- **Existing Behavior**: `ClipStore.ClipItem` has no `expiresAt` field. `AndroidBridge.syncItems()` does not write to the expiry ledger. Expiry is never applied to clips saved or synced from the application.
- **Required Change**: Add `expiresAt` to `ClipStore.ClipItem` metadata serialization. When clips are saved, edited, or captured, calculate expiry timestamp according to current retention setting.
- **Verification Test**: Set retention to 1 hour; verify unpinned clips older than 1 hour are pruned on schedule by `CaptureExpiryReceiver`.

---

## 5. Architectural Conflicts and Shared-State Risks

```
[UI State Transition]
        │
        ├──> Calls renderFullImeView()
        │           │
        │           ├──> Wipes contentContainer.removeAllViews()
        │           ├──> Destroys clipboardScrollView & settingsScrollView (Resets Scroll to 0)
        │           └──> Destroys undoRedoHost (Kills Split/Merge Undo banner)
        │
        └──> Async store.listItems() finishes later
                    │
                    └──> Calls renderFullImeView() AGAIN (Double destruction race condition)
```

1. **Destructive Full-View Rerendering**:
   `renderFullImeView()` is called across 14 different event handlers. Because it unconditionally purges `contentContainer.removeAllViews()`, transient UI states (scroll offset, floating action bars, focus) are wiped out.
2. **Two Isolated Undo Systems**:
   - `InsertionHistoryManager` handles text pasted into external host apps.
   - `undoStack` in `ClipMaxImeService` handles card splits and merges in local storage.
   - These two systems have distinct lifecycles and must remain separate, but their presentation must be unified without premature dismissal.
3. **Surrounding Text Query Limits**:
   Querying `getTextBeforeCursor(length)` for large insertions fails due to IPC buffer limitations in host apps. Verification must use a tail-signature strategy rather than full string matching.

---

## 6. Recommended Implementation Order and Dependencies

```
[Phase 1: Lifecycle & Notification Fixes]
   ├── Fix performMerge / splitItem race conditions
   └── Decouple reloadItems() from full UI recreation
          │
          ▼
[Phase 2: Scroll Position & Selection State]
   ├── Implement non-destructive updates for clipboard and settings panels
   ├── Retain scroll position during Merge activation and card selection
   └── Preserve scroll position on settings toggles
          │
          ▼
[Phase 3: Insertion Undo/Redo Engine Overhaul]
   ├── Redesign canSafelyUndo to use tail-signature matching
   └── Synchronize State B toolbar button states with host editor gateway
          │
          ▼
[Phase 4: Viewport Geometry, Keypad & Key States]
   ├── Lock keyboard viewport height to a fixed metric across all modes
   ├── Implement dedicated 3x4 numeric keypad
   ├── Implement dynamic action key icons (Send, Search, Next, Done)
   └── Expand Caps Lock double-tap window to 400ms
          │
          ▼
[Phase 5: Visual Redesign, Expiry Engine & Cleanup]
   ├── Redesign clip cards (single surface, #1/#2 index badges)
   ├── Long-press comma emoji quick-access
   ├── Clipboard auto-save master toggle in clipboard panel
   ├── Connect ClipStore metadata to CapturePreferences expiry ledger
   └── Cleanly decouple/remove in-app simulator
```

---

## 7. Acceptance Tests for Every Requirement

1. **Split/Merge Undo Notification**:
   - Perform a Merge or Split on cards.
   - Verify Undo/Redo notification remains visible indefinitely until an explicit tap outside the notification occurs.
   - Tap Undo; verify the operation is reversed and the notification updates without disappearing.
2. **Merge Selection & Scroll Position**:
   - Scroll to the 20th card at the bottom of the clipboard panel.
   - Tap Merge on the card.
   - Verify the card is highlighted as Source and the scroll position does not reset to the top.
3. **Settings Toggle Scroll Position**:
   - Open Settings inside the keyboard. Scroll down to Typing Assistance.
   - Toggle Autocomplete, Autocorrect, and Suggestions repeatedly.
   - Verify the scroll position remains stationary.
4. **Paste Insertion Undo/Redo**:
   - Paste a 1,000-character clip into an external messaging app or browser.
   - Open secondary toolbar (State B).
   - Verify Undo button is enabled. Tap Undo; verify the pasted text is removed and Redo becomes enabled.
5. **Fixed Keyboard Height**:
   - Switch between typing, clipboard panel, and settings.
   - Verify the keyboard bounding box height remains completely constant.
6. **Dedicated Numeric Keypad**:
   - Switch to numeric layout.
   - Verify a 3x4 calculator/telephone-style numeric keypad is rendered with large keys.
7. **Clip Card Redesign**:
   - Open clipboard panel.
   - Verify cards have a single flat background, no nested double-colored boxes, and display `#1`, `#2` index numbers.
8. **Dynamic Action Key**:
   - Focus a Search field -> displays Search icon.
   - Focus an SMS/chat field -> displays Send icon.
   - Focus a multiline text area -> displays Return arrow.
9. **Caps Lock**:
   - Double-tap Shift key within 400ms.
   - Verify Caps Lock indicator engages and remains locked across multiple words.
10. **Long-Press Comma**:
    - Tap `,` -> commits comma.
    - Long-press `,` -> opens emoji quick panel without committing comma.
11. **Clipboard Auto-Save Toggle**:
    - Toggle Auto-Save off inside clipboard panel.
    - Copy text in another application.
    - Confirm text is not added to ClipMax.
12. **Clip Expiry**:
    - Set retention to 1 hour.
    - Verify unpinned clips older than 1 hour are pruned automatically.

---

## 8. Files Expected to Change by Phase

- **Phase 1**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Phase 2**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Phase 3**: `android/app/src/main/java/com/clipmax/app/InsertionHistoryManager.kt`, `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Phase 4**: `android/app/src/main/java/com/clipmax/app/KeyboardLayoutSpec.kt`, `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`
- **Phase 5**: `android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt`, `android/app/src/main/java/com/clipmax/app/ClipStore.kt`, `android/app/src/main/java/com/clipmax/app/CapturePreferences.kt`, `android/app/src/main/java/com/clipmax/app/AndroidBridge.kt`, `src/lib/storage.ts`, `src/App.tsx`, `src/components/LibraryScreen.tsx`

---

## 9. Risks, Blockers and Unresolved Questions

1. **Risk — Host Editor Surrounding Text Limitations**:
   Because `InputConnection.getTextBeforeCursor` cannot be forced to return more characters than the host application chooses to expose, Phase 3 must use a tail-and-anchor verification strategy rather than demanding the entire string length.
2. **Risk — Window Inset Dynamics in Fixed Viewports**:
   Enforcing a fixed viewport height across typing, clipboard, and settings must take into account gesture navigation insets on Android 10+ devices to prevent navigation bar overlap.
3. **Question for User**:
   Should Merge/Split Undo/Redo controls be consolidated directly into the secondary toolbar (Toolbar State B), or should they continue to be presented as a persistent floating bottom toast? *(Recommendation: Unified Toolbar State B presentation for cleaner ergonomics).*

---
*End of Phase 0 Technical Audit Report.*
