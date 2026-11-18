package com.clipmax.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.io.Reader
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ClipMax Production Native Android InputMethodService (IME).
 * Implements the approved Graphite/Classic QWERTY and numeric layout with
 * 50,000-character chunked insertion, persistent local storage access,
 * real clipboard preview pills, host insertion Undo/Redo, offline autocomplete
 * and autocorrection, live clip filtering, and pin support.
 */
class ClipMaxImeService : InputMethodService() {

    private lateinit var store: ClipStore
    private lateinit var capturePreferences: SharedPreferences
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardRegistered = false
    private var inputSessionActive = false
    private var inputViewActive = false
    @Volatile private var captureMode = CapturePreferences.DEFAULT_MODE
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val inserter: ExecutorService = Executors.newSingleThreadExecutor()
    private val suggestExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    private var listRequest = 0
    private var isPasswordTarget = false
    private var fieldCapabilities = InputFieldPolicy.evaluate(InputType.TYPE_CLASS_TEXT, 0)
    private var currentEditorTarget = InsertionHistoryManager.EditorTarget(null, 0, 0, 0L)
    private var editorSessionId = 0L
    private var lastEditorPackage: String? = null
    private var lastEditorFieldId: Int = 0
    private var pasteHistoryVerifyToken = 0
    private var isInserting = false
    private var pinnedOnly = false
    private var keyboardSearchQuery = ""
    private var keyboardSearchResults: List<ClipItem> = emptyList()
    private var keyboardSearchRequest = 0
    private var insertedCodePoints = 0
    private var lastReportedCodePoints = 0
    private var activeItemCodePoints = 0
    private var insertTask: Future<*>? = null
    private var items: List<ClipItem> = emptyList()
    @Volatile private var snippetCache: Map<String, String> = emptyMap()

    // Keyboard Layout Mode: ALPHA (QWERTY), SYMBOLS (?123), SYMBOLS_EXT (=\<), NUMPAD (4x5 calculator)
    private val modeController = KeyboardLayoutSpec.KeyboardModeController()
    private val keyboardMode: KeyboardLayoutSpec.KeyboardMode
        get() = modeController.currentMode
    // Shift State: 0 = off (lowercase), 1 = shifted (1-shot uppercase), 2 = caps lock
    private val shiftStateMachine = AutoCapitalizationPolicy.ShiftKeyStateMachine()
    private val shiftState: Int
        get() = shiftStateMachine.shiftState
    private val manualShiftOverride: Boolean
        get() = shiftStateMachine.manualShiftOverride
    private var autoCapMode = AutoCapitalizationPolicy.CapMode.SENTENCES
    private var currentLayoutGeometry: KeyboardLayoutSpec.GeometryDp =
        KeyboardLayoutSpec.computeGeometryDp("normal", 800f, false, false)

    // UI View References
    private var imeRootFrame: FrameLayout? = null
    private var imeSurfaceFrame: FrameLayout? = null
    private var contentContainer: LinearLayout? = null
    private var mainViewportFrame: LinearLayout? = null
    private var clipboardPanelRoot: LinearLayout? = null
    private var clipboardScrollView: android.widget.ScrollView? = null
    private var clipboardCardsContainer: LinearLayout? = null
    private var clipboardHeaderCountTv: TextView? = null
    private var clipboardCaptureToggleRef: FrameLayout? = null
    private var clipboardCaptureThumbRef: View? = null
    private var clipboardCaptureLabelRef: TextView? = null
    private var clipboardMergeBanner: TextView? = null
    private var clipboardSavedScrollY: Int = 0
    private var pasteUndoCapsuleHost: FrameLayout? = null
    private var pasteUndoCapsuleBtn: LinearLayout? = null
    private var pasteUndoCapsuleIcon: ImageView? = null
    private var pasteUndoCapsuleText: TextView? = null
    private val pasteCapsuleController = PasteUndoCapsuleSpec.PasteUndoCapsuleController()

    private var settingsScrollView: android.widget.ScrollView? = null
    private var settingsSavedScrollY: Int = 0
    private var chipsContainer: LinearLayout? = null
    private var toolbarChipsScroll: HorizontalScrollView? = null
    private var suggestionScrollView: HorizontalScrollView? = null
    private var suggestionsContainer: LinearLayout? = null
    private var searchResultsContainer: LinearLayout? = null
    private var keyboardRowsContainer: LinearLayout? = null
    private var statusBannerView: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var statusCancelBtn: Button? = null
    private var searchInputText: TextView? = null
    private var searchPillView: View? = null
    private var searchClearBtn: View? = null
    private var modeButton: Button? = null
    private var shiftButton: View? = null
    private var shiftImageView: ImageView? = null
    private var enterKeyView: View? = null
    private var enterKeyImageView: ImageView? = null
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()

    // Host Insertion History & Offline Typing Intelligence (strictly separate from Merge/Split undoStack)
    private val insertionHistory = InsertionHistoryManager()
    @Volatile private var typingEngine: TypingIntelligenceEngine = TypingIntelligenceEngine.createFallback()
    private val suggestionGeneration = AtomicInteger(0)
    private var toolbarStateACenterMode = 0 // 0 = Clipboard previews, 1 = Typing suggestions
    private var currentWordAnalysis: TypingIntelligenceEngine.WordAnalysis? = null
    private var activeComposingWord: String = ""
    private var currentNextWordSuggestions: List<String> = emptyList()
    private var lastRevertOriginalWord: String? = null
    private val suppressedAutocorrectWords = HashSet<String>()



    private val parenChunkRegex = Regex("^(.*?)\\s*\\((Chunk\\s+(\\d+)/(\\d+))\\)$", RegexOption.IGNORE_CASE)
    private val dotChunkTimeRegex = Regex("^(.*?)\\s*[·•]\\s*(Chunk\\s+(\\d+)/(\\d+))(?:\\s*[·•]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AP]M)?)?$", RegexOption.IGNORE_CASE)

    private fun parseTitleAndChunk(rawTitle: String): Pair<String, String?> {
        val trimmed = rawTitle.trim()
        dotChunkTimeRegex.find(trimmed)?.let { match ->
            val base = match.groupValues[1].trim()
            val idx = match.groupValues[3].toIntOrNull() ?: 1
            val total = match.groupValues[4].toIntOrNull() ?: 1
            val badge = if (total > 1) "Chunk $idx/$total" else null
            return base to badge
        }
        parenChunkRegex.find(trimmed)?.let { match ->
            val base = match.groupValues[1].trim()
            val idx = match.groupValues[3].toIntOrNull() ?: 1
            val total = match.groupValues[4].toIntOrNull() ?: 1
            val badge = if (total > 1) "Chunk $idx/$total" else null
            return base to badge
        }
        return trimmed to null
    }

    // Clipboard & Touch Feedback States
    private var isClipboardPanelOpen = false
    private var isSettingsOpen = false
    private var toolbarState = 0 // 0 = State A (Snippets + Right Arrow), 1 = State B (Back + Pinned + Search + Settings)
    private var toolbarContainer: FrameLayout? = null
    private var toolbarStateAView: View? = null
    private var toolbarStateBView: View? = null
    private var isFilterMode = false // false = Typing: Host, true = Typing: Filter
    private var isEmojiPickerOpen = false
    private var selectedEmojiCategoryIdx = -1
    private var emojiPickerRoot: FrameLayout? = null
    private var emojiRemoveOverlay: View? = null
    private var emojiScrollView: android.widget.ScrollView? = null
    private var emojiGridContainer: LinearLayout? = null
    private val emojiTabViews = mutableListOf<Pair<Int, FrameLayout>>()
    private val emojiPreferences: SharedPreferences by lazy {
        getSharedPreferences(EmojiInterfaceSpec.PREF_FILE, Context.MODE_PRIVATE)
    }
    private val recentEmojiHistory: EmojiInterfaceSpec.RecentEmojiHistory by lazy {
        val raw = runCatching { emojiPreferences.getString(EmojiInterfaceSpec.PREF_RECENT_EMOJIS, null) }.getOrNull()
        EmojiInterfaceSpec.RecentEmojiHistory(EmojiInterfaceSpec.RecentEmojiHistory.decode(raw))
    }
    private var isMergeMode = false
    private var mergeSourceId: String? = null
    private data class OperationSnapshot(
        val description: String,
        val removedItems: List<ClipItem>,
        val removedContents: Map<String, String>,
        val addedItems: List<ClipItem>,
        val addedContents: Map<String, String>
    )
    private val undoStack = java.util.Stack<OperationSnapshot>()
    private val redoStack = java.util.Stack<OperationSnapshot>()
    private var isSplitMergeHistoryBusy = false
    private var undoRedoHost: View? = null
    private var contextMenuHost: FrameLayout? = null
    private var contextMenuItemId: String? = null
    private var pendingRenderAfterContextMenu = false
    private var keyboardWrapper: FrameLayout? = null
    private var keyPopupView: FrameLayout? = null
    private var keyPopupText: TextView? = null

    private val themePreferences: SharedPreferences by lazy {
        getSharedPreferences("clipmax_theme_prefs", Context.MODE_PRIVATE)
    }
    private var themePreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var pendingSelectionCapture: Runnable? = null
    private var expiryCleanupRunnable: Runnable? = null
    private var serviceDestroyed = false
    private val storeChangeListener: () -> Unit = {
        main.post {
            if (!serviceDestroyed) reloadItems()
        }
    }

    private val appTheme: String
        get() = themePreferences.getString("app_theme", "graphite") ?: "graphite"

    private val currentThemePalette: KeyboardLayoutSpec.ImeThemePalette
        get() {
            val saved = runCatching { themePreferences.getString("app_theme", null) }.getOrNull()
            val systemNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return KeyboardLayoutSpec.resolveThemePalette(saved, systemNight)
        }

    private val isClassicTheme: Boolean
        get() = currentThemePalette.isClassicTheme

    private val isAutocompleteEnabled: Boolean
        get() = themePreferences.getBoolean("typing_autocomplete", true)

    private val isAutoCorrectEnabled: Boolean
        get() = themePreferences.getBoolean("typing_autocorrect", true)

    private val isSuggestionsEnabled: Boolean
        get() = themePreferences.getBoolean("typing_suggestions", true)

    // Theme Color Tokens (Evaluated dynamically, reads app preference before system night mode)
    private val isNightMode: Boolean get() = currentThemePalette.isNightMode
    private val colorBg: Int get() = currentThemePalette.colorBg
    private val colorAltDeepBg: Int get() = currentThemePalette.colorAltDeepBg
    private val colorAltPrimaryBg: Int get() = currentThemePalette.colorAltPrimaryBg
    private val colorAltPrimaryText: Int get() = currentThemePalette.colorAltPrimaryText
    private val colorToolbarBg: Int get() = currentThemePalette.colorBg
    private val colorKeyBg: Int get() = currentThemePalette.colorKeyBg
    private val colorKeyBorder: Int get() = currentThemePalette.colorKeyBorder
    private val colorKeyText: Int get() = currentThemePalette.colorKeyText
    private val colorModBg: Int get() = currentThemePalette.colorModBg
    private val colorModText: Int get() = currentThemePalette.colorModText
    private val colorAccent: Int get() = currentThemePalette.colorAccent
    private val colorAccentText: Int get() = currentThemePalette.colorAccentText
    private val colorHintText: Int get() = currentThemePalette.colorHintText
    private val colorChipBg: Int get() = currentThemePalette.colorChipBg
    private val colorChipBorder: Int get() = currentThemePalette.colorChipBorder
    private val colorSnippetBg: Int get() = currentThemePalette.colorSnippetBg
    private val colorSnippetBorder: Int get() = currentThemePalette.colorSnippetBorder
    private val colorActiveTabBg: Int get() = currentThemePalette.colorActiveTabBg
    private val colorActiveTabText: Int get() = currentThemePalette.colorActiveTabText
    private val colorDangerText: Int get() = currentThemePalette.colorDangerText

    // QWERTY Letter Rows (All 26 letters: 10 + 9 + 7)
    private val alphaRow1 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
    private val alphaRow2 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
    private val alphaRow3 = listOf("Z", "X", "C", "V", "B", "N", "M")

    // Numeric / Common Symbols (?123)
    private val numRow1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val numRow2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    private val numRow3 = listOf("*", "\"", "'", ":", ";", "!", "?")

    // Secondary Symbols (=\<)
    private val symRow1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "Δ")
    private val symRow2 = listOf("£", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\")
    private val symRow3 = listOf("<", ">", "%", "©", "®", "™", "[", "]")

    private val capturePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CapturePreferences.MODE_KEY) {
            captureMode = CapturePreferences.mode(capturePreferences)
            updateCaptureMonitoring()
            main.post {
                updateCaptureStatus()
                updateClipboardCaptureToggleUI()
                renderClipChips()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed = false
        store = ClipStore(filesDir)
        ClipStore.addChangeListener(storeChangeListener)
        capturePreferences = CapturePreferences.preferences(this)
        captureMode = CapturePreferences.mode(capturePreferences)
        capturePreferences.registerOnSharedPreferenceChangeListener(capturePreferenceListener)

        // Observe in-app theme & typing preference changes in real-time
        themePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "app_theme" -> main.post { renderFullImeView() }
                "typing_autocomplete", "typing_autocorrect", "typing_suggestions" -> main.post {
                    onTypingSettingsChanged()
                }
            }
        }
        themePreferences.registerOnSharedPreferenceChangeListener(themePreferenceListener)

        // Load bundled local-only English dictionary off the main UI thread
        io.execute {
            val loaded = runCatching {
                resources.openRawResource(R.raw.en_words).use { stream ->
                    TypingIntelligenceEngine.fromStream(stream)
                }
            }.getOrNull()
            if (loaded != null && !serviceDestroyed) {
                typingEngine = loaded
            }
        }

        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            capturePrimaryClip(CaptureSource.CLIPBOARD_LISTENER)
        }
        updateCaptureMonitoring()
        if (CapturePreferences.isEnabled(captureMode)) {
            capturePrimaryClip(CaptureSource.CLIPBOARD_POLL)
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        win.navigationBarColor = colorBg
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
        }
    }

    private fun updateWindowInsets(insets: WindowInsets) {
        val root = imeRootFrame ?: return
        val (navBottom, gestureBottom) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val gesture = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
            nav to gesture
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom to insets.systemGestureInsets.bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom to 0
        }

        // Keep the complete bottom key row visible and usable.
        // If 3-button navigation is active (navBottom > 28dp), use navBottom directly.
        // If gesture navigation or edge-to-edge, apply max(navBottom, gestureBottom, dp(12))
        // ensuring keys never sit flush on top of the gesture navigation bar.
        val bottomPadding = if (navBottom > dp(28)) {
            navBottom
        } else {
            maxOf(navBottom, maxOf(gestureBottom, dp(12)))
        }
        imeRootFrame?.setPadding(0, 0, 0, bottomPadding)
    }

    override fun onCreateInputView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(colorBg)
            setPadding(0, 0, 0, dp(12))
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        imeRootFrame = root

        val surface = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    val host = undoRedoHost
                    if (host != null && host.visibility == View.VISIBLE && host.parent === this) {
                        val rect = android.graphics.Rect()
                        host.getHitRect(rect)
                        if (!rect.contains(ev.x.toInt(), ev.y.toInt())) {
                            dismissUndoRedoControl()
                            return true // Consume outside tap so underlying card action is not triggered
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        imeSurfaceFrame = surface
        root.addView(surface)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentContainer = content
        surface.addView(content)

        // Floating letter preview popup directly on top-level root FrameLayout
        val popup = FrameLayout(this).apply {
            visibility = View.GONE
            background = roundedDrawable(colorKeyBg, dp(8f), colorAccent)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(12f)
            }
            val tv = TextView(this@ClipMaxImeService).apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
                gravity = Gravity.CENTER
            }
            keyPopupText = tv
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        keyPopupView = popup
        root.addView(popup, FrameLayout.LayoutParams(dp(44), dp(52)))

        root.setOnApplyWindowInsetsListener { _, insets ->
            updateWindowInsets(insets)
            insets
        }

        renderFullImeView()
        return root
    }

    private fun applyOneHandedPadding() {
        val content = contentContainer ?: return
        val oneHanded = themePreferences.getString("one_handed_mode", "off") ?: "off"
        val (padLeft, padRight) = when (oneHanded) {
            "left" -> 0 to dp(54)
            "right" -> dp(54) to 0
            else -> 0 to 0
        }
        content.setPadding(padLeft, 0, padRight, 0)
    }

    private fun renderFullImeView() {
        val content = contentContainer ?: return
        dismissContextMenu(animate = false)
        pendingRenderAfterContextMenu = false
        clipboardPanelRoot = null
        clipboardScrollView = null
        clipboardCardsContainer = null
        clipboardHeaderCountTv = null
        clipboardCaptureToggleRef = null
        clipboardCaptureThumbRef = null
        clipboardCaptureLabelRef = null
        clipboardMergeBanner = null
        pasteUndoCapsuleHost = null
        pasteUndoCapsuleBtn = null
        pasteUndoCapsuleIcon = null
        pasteUndoCapsuleText = null
        settingsScrollView = null
        emojiRemoveOverlay = null
        emojiPickerRoot = null
        emojiScrollView = null
        emojiGridContainer = null
        emojiTabViews.clear()
        keyboardWrapper = null
        keyboardRowsContainer = null
        mainViewportFrame = null
        imeRootFrame?.setBackgroundColor(colorBg)
        content.removeAllViews()
        content.setBackgroundColor(colorBg)
        letterKeyViews.clear()
        shiftButton = null
        shiftImageView = null
        enterKeyView = null
        enterKeyImageView = null
        searchResultsContainer = null
        searchInputText = null
        searchPillView = null
        searchClearBtn = null
        chipsContainer = null
        toolbarChipsScroll = null
        suggestionScrollView = null
        suggestionsContainer = null


        // Apply One-Handed Mode padding
        applyOneHandedPadding()

        val viewport = computeCurrentViewportSpec()

        // 1. ClipMax Compact Toolbar (fixed 38dp)
        content.addView(buildToolbar())

        // 2. Canonical Fixed-Height Main Viewport across all IME panels & overlays
        val viewportContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(colorBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(viewport.mainPanelHeightDp).toInt()
            )
        }
        mainViewportFrame = viewportContainer
        content.addView(viewportContainer)

        // Status / In-Flight Banner inside fixed viewport (shrinks flexible panel/rows cleanly without height shift)
        viewportContainer.addView(buildStatusBanner())

        if (isSettingsOpen) {
            // 3. Settings Interface Panel
            viewportContainer.addView(buildSettingsPanel())
        } else if (isClipboardPanelOpen) {
            // 3B. Compact Expandable Saved-Clipboard / Pinned Panel
            viewportContainer.addView(buildClipboardPanel())
        } else {
            // 3C. Live Search Clips Bar & Results Strip (above keyboard or emoji picker when active)
            if (isFilterMode) {
                viewportContainer.addView(buildSearchInterface(viewport))
            }

            if (isEmojiPickerOpen) {
                // 3D. Native Offline Emoji Picker inside fixed viewport
                viewportContainer.addView(buildEmojiPickerPanel())
                if (isFilterMode) {
                    renderSearchResults()
                }
                renderClipChips()
            } else {
                // 4. Approved 4-row Ergonomic QWERTY / Numeric Keyboard Container
            val geom = refreshKeyboardGeometry()
            syncAutoCapitalization(preserveManualShift = true)

            val wrapper = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            keyboardWrapper = wrapper

            val kbContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(colorBg)
                setPadding(
                    dp(geom.sidePaddingDp).toInt(),
                    dp(geom.topPaddingDp).toInt(),
                    dp(geom.sidePaddingDp).toInt(),
                    dp(geom.bottomPaddingDp).toInt()
                )
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            keyboardRowsContainer = kbContainer
            renderKeyboardRows()
            wrapper.addView(kbContainer)

            viewportContainer.addView(wrapper)
                if (isFilterMode) {
                    renderSearchResults()
                }
                renderClipChips()
            }
        }

        // Preserve any active Split/Merge floating Undo/Redo overlay above rebuilt content
        undoRedoHost?.bringToFront()
    }

    // =========================================================================
    // 1. TWO-STATE TOOLBAR (Phase 5B: State A & State B with smooth sliding structure)
    // =========================================================================
    private fun buildToolbar(): View {
        val rootFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(KeyboardLayoutSpec.TOOLBAR_HEIGHT_DP).toInt())
            setBackgroundColor(colorToolbarBg)
            clipChildren = true
        }
        toolbarContainer = rootFrame

        // Use INVISIBLE instead of GONE so both states are measured to exact full width before animating
        val viewA = buildToolbarStateA().apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = if (toolbarState == 0) View.VISIBLE else View.INVISIBLE
            translationX = 0f
        }
        toolbarStateAView = viewA

        val viewB = buildToolbarStateB().apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = if (toolbarState == 1) View.VISIBLE else View.INVISIBLE
            translationX = 0f
        }
        toolbarStateBView = viewB

        rootFrame.addView(viewA)
        rootFrame.addView(viewB)

        // Contextual Floating Paste Undo/Redo Capsule (Phase 2B)
        val capsule = buildPasteUndoCapsule()
        rootFrame.addView(capsule)
        updatePasteUndoCapsuleUI()

        return rootFrame
    }

    private fun switchToolbarState(targetState: Int, dismissCapsule: Boolean = true) {
        if (dismissCapsule) {
            dismissPasteUndoCapsule()
        }
        toolbarState = targetState
        updatePasteUndoCapsuleUI()
        if (targetState == 1) {
            updateInsertionHistoryButtons()
        }
        val viewA = toolbarStateAView
        val viewB = toolbarStateBView
        val root = toolbarContainer

        if (viewA == null || viewB == null || root == null || !root.isAttachedToWindow) {
            renderFullImeView()
            return
        }

        val w = root.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()

        viewA.animate().withEndAction(null).cancel()
        viewB.animate().withEndAction(null).cancel()

        if (targetState == 1) {
            viewB.translationX = w
            viewB.alpha = 1f
            viewB.visibility = View.VISIBLE

            viewA.animate()
                .translationX(-w)
                .setDuration(150)
                .withEndAction {
                    if (toolbarState == 1) {
                        viewA.visibility = View.INVISIBLE
                    }
                    viewA.translationX = 0f
                }
                .start()
            viewB.animate()
                .translationX(0f)
                .setDuration(150)
                .withEndAction {
                    viewB.visibility = View.VISIBLE
                    viewB.translationX = 0f
                }
                .start()
        } else {
            viewA.translationX = -w
            viewA.alpha = 1f
            viewA.visibility = View.VISIBLE

            viewB.animate()
                .translationX(w)
                .setDuration(150)
                .withEndAction {
                    if (toolbarState == 0) {
                        viewB.visibility = View.INVISIBLE
                    }
                    viewB.translationX = 0f
                }
                .start()
            viewA.animate()
                .translationX(0f)
                .setDuration(150)
                .withEndAction {
                    viewA.visibility = View.VISIBLE
                    viewA.translationX = 0f
                }
                .start()
        }
    }

    private fun buildToolbarStateA(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        val vPad = dp(OverlayUiSpec.TOOLBAR_VERTICAL_PADDING_DP).toInt()
        setPadding(dp(6), vPad, dp(6), vPad)
        setBackgroundColor(colorToolbarBg)

        // 1. Far-left Clipboard icon button (switches back from Suggestions to Clipboard Pills, or opens Clipboard Panel)
        val clipIconBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(colorChipBg, dp(17f), colorChipBorder)
            contentDescription = "Clipboard history"
            val iv = createIconView(IconDrawable(IconType.CLIPBOARD, colorKeyText, 14f))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                dismissPasteUndoCapsule()
                if (toolbarStateACenterMode == 1 &&
                    keyboardMode != KeyboardLayoutSpec.KeyboardMode.NUMPAD &&
                    !isClipboardPanelOpen &&
                    !isSettingsOpen
                ) {
                    showClipboardPillsInToolbar()
                } else {
                    pinnedOnly = false
                    isFilterMode = false
                    isEmojiPickerOpen = false
                    isSettingsOpen = false
                    settingsSavedScrollY = 0
                    isClipboardPanelOpen = !isClipboardPanelOpen
                    clipboardSavedScrollY = 0
                    renderFullImeView()
                }
            }
        }
        addView(clipIconBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            rightMargin = dp(6)
        })

        // 2. Center slot sharing exact 38dp toolbar space between Clipboard Previews (0) & Typing Suggestions (1)
        val centerFrame = FrameLayout(this@ClipMaxImeService).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val scrollView = HorizontalScrollView(this@ClipMaxImeService).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(20))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = if (toolbarStateACenterMode == 0) View.VISIBLE else View.GONE
            val container = LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), 0, dp(16), 0)
            }
            chipsContainer = container
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        toolbarChipsScroll = scrollView
        centerFrame.addView(scrollView)
        renderClipChips()

        val suggestScroll = HorizontalScrollView(this@ClipMaxImeService).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = if (toolbarStateACenterMode == 1) View.VISIBLE else View.GONE
            val container = LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), 0, dp(8), 0)
            }
            suggestionsContainer = container
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        suggestionScrollView = suggestScroll
        centerFrame.addView(suggestScroll)
        renderSuggestionStrip()
        updateToolbarCenterVisibility()

        addView(centerFrame)

        // 3. Circular navigation arrow button at far right (White circle with dark right chevron)
        val rightArrowBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(0xFFFFFFFF.toInt(), dp(17f), 0)
            contentDescription = "More actions"
            val iv = createIconView(IconDrawable(IconType.CHEVRON_RIGHT, 0xFF111111.toInt(), 14.5f))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                switchToolbarState(1)
            }
        }
        addView(rightArrowBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            leftMargin = dp(6)
            rightMargin = dp(2)
        })
    }

    private fun buildToolbarStateB(): View = FrameLayout(this).apply {
        val tbPalette = SecondaryToolbarSpec.resolvePalette(isNightMode, isClassicTheme)
        setBackgroundColor(tbPalette.toolbarBg)

        val row = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val endSize = dp(SecondaryToolbarSpec.END_BUTTON_SIZE_DP.toInt())
        val endRadius = dp(SecondaryToolbarSpec.END_BUTTON_CORNER_DP)

        // 1. Circular Back Button (Left side, prominent white circle in dark mode)
        val backBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(tbPalette.backCircleBg, endRadius, 0, tbPalette.rippleColor)
            contentDescription = "Back to clipboard snippets"
            val iv = createIconView(
                IconDrawable(
                    IconType.CHEVRON_LEFT,
                    tbPalette.backIconFg,
                    SecondaryToolbarSpec.BACK_ICON_SIZE_DP
                )
            )
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                dismissPasteUndoCapsule()
                val wasPanelOpen = isSettingsOpen || isClipboardPanelOpen || isFilterMode || pinnedOnly || isEmojiPickerOpen
                val wasFilterMode = isFilterMode
                toolbarState = 0
                isSettingsOpen = false
                isClipboardPanelOpen = false
                isEmojiPickerOpen = false
                clipboardSavedScrollY = 0
                settingsSavedScrollY = 0
                isFilterMode = false
                pinnedOnly = false
                keyboardSearchQuery = ""
                keyboardSearchResults = emptyList()
                if (wasFilterMode) {
                    modeController.exitSearchClips()
                }
                if (wasPanelOpen) {
                    renderFullImeView()
                } else {
                    switchToolbarState(0)
                }
            }
        }
        row.addView(backBtn, LinearLayout.LayoutParams(endSize, endSize).apply {
            rightMargin = dp(4)
        })

        // Central icon-only actions container: Pinned, Search Clips, Settings evenly distributed
        val actionsContainer = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val rippleDiameter = dp(SecondaryToolbarSpec.TOUCH_RIPPLE_SIZE_DP.toInt())
        val rippleRadius = dp(SecondaryToolbarSpec.TOUCH_RIPPLE_SIZE_DP / 2f)

        fun makeStateBIconAction(
            iconType: IconType,
            accessibilityLabel: String,
            onClick: () -> Unit
        ): FrameLayout {
            return FrameLayout(this@ClipMaxImeService).apply {
                contentDescription = accessibilityLabel
                isClickable = true
                isFocusable = true

                val rippleCircle = FrameLayout(this@ClipMaxImeService).apply {
                    background = roundedRipple(0, rippleRadius, 0, tbPalette.rippleColor)
                }
                addView(rippleCircle, FrameLayout.LayoutParams(rippleDiameter, rippleDiameter, Gravity.CENTER))

                val iv = createIconView(
                    IconDrawable(iconType, tbPalette.centerIconFg, SecondaryToolbarSpec.CENTER_ICON_SIZE_DP)
                )
                addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            rippleCircle.isPressed = true
                            iv.animate().cancel()
                            iv.animate()
                                .scaleX(SecondaryToolbarSpec.PRESS_SCALE)
                                .scaleY(SecondaryToolbarSpec.PRESS_SCALE)
                                .alpha(SecondaryToolbarSpec.PRESS_ALPHA)
                                .setDuration(SecondaryToolbarSpec.PRESS_DOWN_MS)
                                .start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            rippleCircle.isPressed = false
                            iv.animate().cancel()
                            iv.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(SecondaryToolbarSpec.PRESS_UP_MS)
                                .start()
                        }
                    }
                    false
                }
                setOnClickListener { onClick() }
            }
        }

        // 2. Pinned (icon-only, no persistent rectangular card)
        val pinBtn = makeStateBIconAction(
            IconType.PIN,
            "Pinned"
        ) {
            dismissPasteUndoCapsule()
            if (isClipboardPanelOpen && pinnedOnly) {
                pinnedOnly = false
                isClipboardPanelOpen = false
                clipboardSavedScrollY = 0
            } else {
                pinnedOnly = true
                isClipboardPanelOpen = true
                isEmojiPickerOpen = false
                clipboardSavedScrollY = 0
                isSettingsOpen = false
                settingsSavedScrollY = 0
                isFilterMode = false
                keyboardSearchQuery = ""
                keyboardSearchResults = emptyList()
            }
            renderFullImeView()
        }
        actionsContainer.addView(pinBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // 3. Search Clips (icon-only, no persistent rectangular card)
        val searchBtn = makeStateBIconAction(
            IconType.SEARCH,
            "Search Clips"
        ) {
            dismissPasteUndoCapsule()
            if (isPasswordTarget) {
                setStatus("Password field: Search disabled for privacy.")
                return@makeStateBIconAction
            }
            if (isFilterMode) {
                isFilterMode = false
                keyboardSearchQuery = ""
                keyboardSearchResults = emptyList()
                modeController.exitSearchClips()
            } else {
                isFilterMode = true
                isClipboardPanelOpen = false
                isEmojiPickerOpen = false
                clipboardSavedScrollY = 0
                isSettingsOpen = false
                settingsSavedScrollY = 0
                pinnedOnly = false
                keyboardSearchQuery = ""
                keyboardSearchResults = items
                modeController.enterSearchClips()
            }
            renderFullImeView()
        }
        actionsContainer.addView(searchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // 4. Settings (icon-only, no persistent rectangular card)
        val settingsBtn = makeStateBIconAction(
            IconType.SETTINGS,
            "Settings"
        ) {
            dismissPasteUndoCapsule()
            isSettingsOpen = !isSettingsOpen
            settingsSavedScrollY = 0
            isClipboardPanelOpen = false
            isEmojiPickerOpen = false
            clipboardSavedScrollY = 0
            isFilterMode = false
            pinnedOnly = false
            renderFullImeView()
        }
        actionsContainer.addView(settingsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        row.addView(actionsContainer)

        // 5. Circular Globe/Language button (far right)
        val imeSwitchBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(tbPalette.globeCircleBg, endRadius, tbPalette.globeCircleBorder, tbPalette.rippleColor)
            contentDescription = "Switch keyboard"
            val iv = createIconView(
                IconDrawable(
                    IconType.IME_SWITCH,
                    tbPalette.globeIconFg,
                    SecondaryToolbarSpec.GLOBE_ICON_SIZE_DP
                )
            )
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                dismissPasteUndoCapsule()
                showSystemImePicker()
            }
        }
        row.addView(imeSwitchBtn, LinearLayout.LayoutParams(endSize, endSize).apply {
            leftMargin = dp(4)
        })

        addView(row)

        // Subtle 1px bottom hairline separating Secondary Toolbar from keyboard base
        val hairline = View(this@ClipMaxImeService).apply {
            setBackgroundColor(tbPalette.toolbarBottomHairline)
        }
        addView(hairline, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))
    }

    // =========================================================================
    // 1B. CONTEXTUAL FLOATING PASTE UNDO/REDO CAPSULE (Phase 2B)
    // =========================================================================
    private fun buildPasteUndoCapsule(): FrameLayout {
        val host = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(
                dp(PasteUndoCapsuleSpec.WIDTH_DP.toInt()),
                dp(PasteUndoCapsuleSpec.HEIGHT_DP.toInt())
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(PasteUndoCapsuleSpec.RIGHT_MARGIN_DP.toInt())
            }
            layoutParams = lp
            visibility = View.GONE
            isClickable = false
            clipChildren = false
        }
        pasteUndoCapsuleHost = host

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val capsuleBg = if (isClassicTheme) (if (isNightMode) 0xFF1F2024.toInt() else 0xFFFFFFFF.toInt()) else colorChipBg
            val capsuleBorder = if (isNightMode) 0xD9FFFFFF.toInt() else 0xFF333338.toInt()
            val capsuleFg = if (isClassicTheme) colorKeyText else colorAltPrimaryText
            background = roundedRipple(capsuleBg, dp(PasteUndoCapsuleSpec.CORNER_RADIUS_DP), capsuleBorder)

            val iv = ImageView(this@ClipMaxImeService).apply {
                setImageDrawable(IconDrawable(IconType.UNDO, capsuleFg, PasteUndoCapsuleSpec.ICON_SIZE_DP))
            }
            pasteUndoCapsuleIcon = iv
            addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val tv = TextView(this@ClipMaxImeService).apply {
                text = "Undo"
                textSize = PasteUndoCapsuleSpec.TEXT_SIZE_SP
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(capsuleFg)
                setSingleLine(true)
            }
            pasteUndoCapsuleText = tv
            val tvLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(5)
            }
            addView(tv, tvLp)

            setOnClickListener {
                if (!pasteCapsuleController.isActive) return@setOnClickListener
                if (pasteCapsuleController.isRedoMode) {
                    performInsertionRedo()
                } else {
                    performInsertionUndo()
                }
            }
        }
        pasteUndoCapsuleBtn = btn
        host.addView(btn)

        return host
    }

    private fun updatePasteUndoCapsuleUI() {
        val host = pasteUndoCapsuleHost ?: return
        val btn = pasteUndoCapsuleBtn
        val config = pasteCapsuleController.currentConfig
        if (config.state == PasteUndoCapsuleSpec.CapsuleState.HIDDEN || toolbarState != 0) {
            host.visibility = View.GONE
            host.isClickable = false
            btn?.isClickable = false
            return
        }

        host.visibility = View.VISIBLE
        host.bringToFront()
        btn?.isClickable = true
        btn?.isEnabled = true

        val iconType = if (config.action == PasteUndoCapsuleSpec.CapsuleAction.REDO) IconType.REDO else IconType.UNDO
        val capsuleFg = if (isClassicTheme) colorKeyText else colorAltPrimaryText
        pasteUndoCapsuleIcon?.setImageDrawable(IconDrawable(iconType, capsuleFg, PasteUndoCapsuleSpec.ICON_SIZE_DP))
        pasteUndoCapsuleText?.text = config.label
        btn?.contentDescription = if (config.action == PasteUndoCapsuleSpec.CapsuleAction.REDO) {
            "Redo pasted clip"
        } else {
            "Undo pasted clip"
        }
    }

    private fun dismissPasteUndoCapsule() {
        if (!pasteCapsuleController.isActive && !pasteCapsuleController.isRedoMode) return
        pasteCapsuleController.dismiss()
        updatePasteUndoCapsuleUI()
    }

    private fun buildSettingsPanel(): View = LinearLayout(this).apply {
        val pillPalette = SettingsPanelSpec.resolvePillCardPalette(currentThemePalette)
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(pillPalette.panelBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        // Header (36dp preserved)
        val header = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setBackgroundColor(pillPalette.headerBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(SettingsPanelSpec.HEADER_HEIGHT_DP.toInt())
            )

            val icon = createIconView(IconDrawable(IconType.SETTINGS, colorModText, 14f)).apply {
                setPadding(0, 0, dp(6), 0)
            }
            addView(icon)

            val title = TextView(this@ClipMaxImeService).apply {
                text = "ClipMax Settings"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(pillPalette.sectionTitleFg)
            }
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val closeBtn = TextView(this@ClipMaxImeService).apply {
                text = "Close"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(colorModText)
                background = roundedRipple(
                    pillPalette.inactivePillBg,
                    dp(13f),
                    pillPalette.inactivePillBorder,
                    pillPalette.pillRipple
                )
                setIcon(IconDrawable(IconType.CLOSE, colorModText, 10f), 4f)
                setOnClickListener {
                    isSettingsOpen = false
                    settingsSavedScrollY = 0
                    renderFullImeView()
                }
            }
            addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)))
        }
        addView(header)

        val divider = View(this@ClipMaxImeService).apply {
            setBackgroundColor(pillPalette.dividerColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        addView(divider)

        // Scrollable pill-card settings body
        val scroll = android.widget.ScrollView(this@ClipMaxImeService).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                settingsSavedScrollY = scrollY
            }
        }
        settingsScrollView = scroll

        val list = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(SettingsPanelSpec.OUTER_PADDING_HORIZONTAL_DP.toInt()),
                dp(8),
                dp(SettingsPanelSpec.OUTER_PADDING_HORIZONTAL_DP.toInt()),
                dp(10)
            )
        }

        val cardRadiusPx = dp(SettingsPanelSpec.CARD_CORNER_RADIUS_DP)
        val pillRadiusPx = dp(SettingsPanelSpec.PILL_CORNER_RADIUS_DP)
        val pillGapPx = dp(SettingsPanelSpec.PILL_GAP_DP.toInt())
        val cardGapPx = dp(SettingsPanelSpec.CARD_GAP_DP.toInt())
        val pillHeightPx = dp(SettingsPanelSpec.PILL_HEIGHT_DP.toInt())

        fun makeSettingCard(bottomMarginPx: Int = cardGapPx): LinearLayout {
            return LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(9))
                background = roundedDrawable(pillPalette.cardBg, cardRadiusPx, pillPalette.cardBorder)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = bottomMarginPx
                }
            }
        }

        fun applyPillVisual(pill: TextView, selected: Boolean) {
            val st = SettingsPanelSpec.resolvePillVisualState(selected, pillPalette)
            pill.setTextColor(st.textColor)
            pill.typeface = if (st.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            pill.background = roundedRipple(st.bgColor, pillRadiusPx, st.borderColor, pillPalette.pillRipple)
        }

        fun makeOptionPill(label: String, settingTitle: String, heightPx: Int = pillHeightPx): TextView {
            return TextView(this@ClipMaxImeService).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isClickable = true
                isFocusable = true
                contentDescription = "$settingTitle: $label"
                setPadding(dp(6), 0, dp(6), 0)
            }
        }

        fun <T> addSegmentedSettingCard(
            title: String,
            options: List<SettingsPanelSpec.ChoiceOption<T>>,
            initialSelected: T,
            onSelect: (T) -> Unit
        ) {
            val card = makeSettingCard()
            val lbl = TextView(this@ClipMaxImeService).apply {
                text = title
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(pillPalette.itemLabelFg)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            card.addView(lbl, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            })

            val row = LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
            }

            val pills = mutableListOf<Pair<T, TextView>>()
            fun refreshSelection(selectedVal: T) {
                pills.forEach { (value, view) ->
                    applyPillVisual(view, value == selectedVal)
                }
            }

            options.forEachIndexed { idx, opt ->
                val pill = makeOptionPill(opt.label, title)
                pill.setOnClickListener {
                    onSelect(opt.value)
                    refreshSelection(opt.value)
                }
                pills.add(opt.value to pill)
                row.addView(pill, LinearLayout.LayoutParams(0, pillHeightPx, 1f).apply {
                    if (idx < options.size - 1) rightMargin = pillGapPx
                })
            }
            refreshSelection(initialSelected)

            card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            list.addView(card)
        }

        // 1. Theme (Graphite vs Classic)
        addSegmentedSettingCard(
            title = "Theme",
            options = SettingsPanelSpec.THEME_OPTIONS,
            initialSelected = appTheme
        ) { nextTheme ->
            if (appTheme != nextTheme) {
                themePreferences.edit().putString(SettingsPanelSpec.PREF_APP_THEME, nextTheme).apply()
                renderFullImeView()
            }
        }

        // 2. Keyboard Height (Compact | Normal | Tall)
        val currentHeight = themePreferences.getString(SettingsPanelSpec.PREF_KEYBOARD_HEIGHT, "normal") ?: "normal"
        addSegmentedSettingCard(
            title = "Keyboard Height",
            options = SettingsPanelSpec.HEIGHT_OPTIONS,
            initialSelected = currentHeight
        ) { nextHeight ->
            themePreferences.edit().putString(SettingsPanelSpec.PREF_KEYBOARD_HEIGHT, nextHeight).apply()
            applyCanonicalViewportHeightInPlace()
        }

        // 3. One-Handed Mode (Left | Off | Right)
        val currentOneHanded = themePreferences.getString(SettingsPanelSpec.PREF_ONE_HANDED, "off") ?: "off"
        addSegmentedSettingCard(
            title = "One-Handed Mode",
            options = SettingsPanelSpec.ONE_HANDED_OPTIONS,
            initialSelected = currentOneHanded
        ) { nextMode ->
            themePreferences.edit().putString(SettingsPanelSpec.PREF_ONE_HANDED, nextMode).apply()
            applyOneHandedPadding()
        }

        // 4. Typing Assistance Card (Autocomplete, Automatic Correction, Suggestions — two equal [On] [Off] pills each)
        val typingCard = makeSettingCard()
        val typingHeader = TextView(this@ClipMaxImeService).apply {
            text = "Typing Assistance"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pillPalette.sectionTitleFg)
        }
        typingCard.addView(typingHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(6)
        })

        SettingsPanelSpec.TYPING_SETTINGS.forEachIndexed { settingIdx, def ->
            val subLbl = TextView(this@ClipMaxImeService).apply {
                text = def.title
                textSize = 11f
                setTextColor(pillPalette.subLabelFg)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            typingCard.addView(subLbl, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            })

            val pairRow = LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
            }

            val pairPills = mutableListOf<Pair<Boolean, TextView>>()
            fun refreshBinary(selectedVal: Boolean) {
                pairPills.forEach { (value, view) ->
                    applyPillVisual(view, value == selectedVal)
                }
            }

            SettingsPanelSpec.BINARY_ON_OFF_OPTIONS.forEachIndexed { idx, opt ->
                val pill = makeOptionPill(opt.label, def.title, dp(30))
                pill.setOnClickListener {
                    themePreferences.edit().putBoolean(def.prefKey, opt.value).apply()
                    onTypingSettingsChanged()
                    refreshBinary(opt.value)
                }
                pairPills.add(opt.value to pill)
                pairRow.addView(pill, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                    if (idx == 0) rightMargin = pillGapPx
                })
            }
            refreshBinary(themePreferences.getBoolean(def.prefKey, def.defaultValue))

            typingCard.addView(pairRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (settingIdx < SettingsPanelSpec.TYPING_SETTINGS.lastIndex) {
                    bottomMargin = dp(8)
                }
            })
        }
        list.addView(typingCard)

        // 5. Full-width Action Pill: Share ClipMax
        val sharePill = TextView(this@ClipMaxImeService).apply {
            text = "Share ClipMax"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            isClickable = true
            isFocusable = true
            contentDescription = "Share ClipMax"
            setTextColor(pillPalette.actionPillFg)
            background = roundedRipple(
                pillPalette.actionPillBg,
                cardRadiusPx,
                pillPalette.actionPillBorder,
                pillPalette.pillRipple
            )
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Check out ClipMax - Android clipboard manager with large-text keyboard insertion!")
                }
                val chooser = Intent.createChooser(shareIntent, "Share ClipMax").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(chooser) }
                setStatus("Share dialog opened.")
            }
        }
        list.addView(sharePill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(SettingsPanelSpec.ACTION_PILL_HEIGHT_DP.toInt())
        ))

        scroll.addView(list)
        addView(scroll)

        val restoreY = settingsSavedScrollY
        if (restoreY > 0) {
            scroll.post { scroll.scrollTo(0, restoreY) }
        }
    }

    private fun buildSearchInterface(viewport: KeyboardLayoutSpec.ViewportSpecDp): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colorBg)
        setPadding(dp(6), dp(viewport.searchTopPaddingDp).toInt(), dp(6), dp(viewport.searchBottomPaddingDp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(viewport.searchInterfaceHeightDp).toInt()
        )

        val barH = dp(viewport.searchBarHeightDp).toInt()
        val pillH = (barH - dp(2)).coerceAtLeast(dp(20))

        // Row 1: Search Input Bar (exact measured height from ViewportSpecDp)
        val barRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH)
        }

        val pill = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
            background = roundedDrawable(colorKeyBg, dp(8f), colorAccent)
            layoutParams = LinearLayout.LayoutParams(0, pillH, 1f).apply {
                rightMargin = dp(6)
            }
        }
        searchPillView = pill

        val searchTv = TextView(this@ClipMaxImeService).apply {
            text = if (keyboardSearchQuery.isEmpty()) "Type below to search clips…" else keyboardSearchQuery
            textSize = 11.5f
            setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            setIcon(IconDrawable(IconType.SEARCH, colorAccent, 12f), 5f)
        }
        searchInputText = searchTv
        pill.addView(searchTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val clearBtn = FrameLayout(this@ClipMaxImeService).apply {
            visibility = if (keyboardSearchQuery.isEmpty()) View.GONE else View.VISIBLE
            contentDescription = "Clear search query"
            background = roundedRipple(colorModBg, dp(10f), 0)
            val iv = createIconView(IconDrawable(IconType.CLOSE, colorModText, 10f))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                keyboardSearchQuery = ""
                keyboardSearchResults = items
                updateSearchUI()
                renderSearchResults()
            }
        }
        searchClearBtn = clearBtn
        pill.addView(clearBtn, LinearLayout.LayoutParams(dp(20), dp(20)))
        barRow.addView(pill)

        val closeSearchBtn = Button(this@ClipMaxImeService).apply {
            text = "Done"
            isAllCaps = false
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(colorModText)
            background = roundedRipple(colorModBg, dp(8f), colorKeyBorder)
            setOnClickListener {
                isFilterMode = false
                keyboardSearchQuery = ""
                keyboardSearchResults = emptyList()
                modeController.exitSearchClips()
                renderFullImeView()
            }
        }
        barRow.addView(closeSearchBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH))
        addView(barRow)

        // Row 2: Live Filtered Clip Results Strip (exact measured height from ViewportSpecDp)
        val resultsScroll = HorizontalScrollView(this@ClipMaxImeService).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(viewport.searchResultsHeightDp).toInt()).apply {
                topMargin = dp(viewport.searchResultsTopMarginDp).toInt()
            }
        }
        val resultsRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(8), 0)
        }
        searchResultsContainer = resultsRow
        resultsScroll.addView(resultsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(resultsScroll)
    }

    private fun renderSearchResults() {
        val container = searchResultsContainer ?: return
        container.removeAllViews()

        if (isPasswordTarget) {
            container.addView(TextView(this).apply {
                text = "Password field: Search disabled for privacy"
                textSize = 10.5f
                setTextColor(colorHintText)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            return
        }

        val matches = if (keyboardSearchQuery.isNotEmpty()) keyboardSearchResults else items
        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (keyboardSearchQuery.isNotEmpty()) "No clips match \"$keyboardSearchQuery\"" else "No saved clips to search"
                textSize = 11f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            })
            return
        }

        matches.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundedRipple(colorChipBg, dp(10f), colorChipBorder)
                val chipH = (dp(computeCurrentViewportSpec().searchResultsHeightDp).toInt() - dp(4)).coerceAtLeast(dp(26))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, chipH).apply {
                    rightMargin = dp(6)
                }

                if (item.pinned) {
                    val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 10.5f)).apply {
                        setPadding(0, 0, dp(4), 0)
                    }
                    addView(pinIcon)
                }

                val snippet = getSnippet(item, 42)
                val (baseTitle, _) = parseTitleAndChunk(item.title)
                val label = displayTitleForCard(baseTitle, snippet)

                val tv = TextView(this@ClipMaxImeService).apply {
                    text = label
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorKeyText)
                    maxWidth = dp(170)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                setOnClickListener {
                    insertItem(item)
                }
            }
            container.addView(card)
        }
    }

    private fun updateModeUI() {
        val btn = modeButton ?: return
        val modeFg = if (isFilterMode) colorActiveTabText else colorModText
        val modeBg = if (isFilterMode) colorActiveTabBg else colorKeyBg
        val modeStroke = if (isFilterMode) colorAccent else colorKeyBorder
        btn.text = if (isFilterMode) "Filter" else "Host"
        btn.setTextColor(modeFg)
        btn.background = roundedRipple(modeBg, dp(6f), modeStroke)
        btn.setIcon(IconDrawable(if (isFilterMode) IconType.SEARCH else IconType.KEYBOARD, modeFg, 11f), 3.5f)

        searchPillView?.background = roundedRipple(
            colorKeyBg,
            dp(8f),
            if (isFilterMode) colorAccent else colorKeyBorder
        )
        searchInputText?.setIcon(IconDrawable(IconType.SEARCH, if (isFilterMode) colorAccent else colorHintText, 12f), 5f)
    }

    // =========================================================================
    // 1B. EXPANDABLE SAVED-CLIPBOARD PANEL
    // =========================================================================
    private fun getVisibleClipboardItems(): List<ClipItem> {
        val base = if (keyboardSearchQuery.isNotEmpty()) keyboardSearchResults else items
        return if (pinnedOnly) base.filter { it.pinned } else base
    }

    private fun clipboardHeaderLabel(@Suppress("UNUSED_PARAMETER") visibleCount: Int): String = when {
        keyboardSearchQuery.isNotEmpty() -> "Matches"
        pinnedOnly -> "Pinned Clips"
        else -> "Clipboard"
    }

    private fun updateClipboardCaptureToggleUI() {
        val toggle = clipboardCaptureToggleRef ?: return
        val thumb = clipboardCaptureThumbRef ?: return
        val label = clipboardCaptureLabelRef ?: return
        val enabled = CapturePreferences.isEnabled(captureMode)

        toggle.background = if (enabled) {
            roundedRipple(colorAccent, dp(12f), colorAccent)
        } else {
            roundedRipple(colorModBg, dp(12f), colorKeyBorder)
        }
        toggle.contentDescription = if (enabled) {
            "Clipboard capture on. Tap to turn off."
        } else {
            "Clipboard capture off. Tap to turn on."
        }

        val thumbLp = (thumb.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(dp(18), dp(18))
        thumbLp.width = dp(18)
        thumbLp.height = dp(18)
        if (enabled) {
            thumbLp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            thumbLp.rightMargin = dp(3)
            thumbLp.leftMargin = 0
            thumb.background = roundedDrawable(colorAccentText, dp(9f))
        } else {
            thumbLp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            thumbLp.leftMargin = dp(3)
            thumbLp.rightMargin = 0
            thumb.background = roundedDrawable(colorHintText, dp(9f))
        }
        thumb.layoutParams = thumbLp

        val labelLp = (label.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (enabled) {
            label.text = "ON"
            label.setTextColor(colorAccentText)
            labelLp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            labelLp.leftMargin = dp(8)
            labelLp.rightMargin = 0
        } else {
            label.text = "OFF"
            label.setTextColor(colorHintText)
            labelLp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            labelLp.rightMargin = dp(7)
            labelLp.leftMargin = 0
        }
        label.layoutParams = labelLp
    }

    private fun populateClipboardCards(cardsList: LinearLayout, visible: List<ClipItem>) {
        cardsList.removeAllViews()
        if (isPasswordTarget) {
            cardsList.addView(TextView(this@ClipMaxImeService).apply {
                text = "Password field active: Saved clips withheld for privacy"
                textSize = 11f
                setTextColor(colorHintText)
                setPadding(dp(12), dp(20), dp(12), dp(20))
                gravity = Gravity.CENTER
                setIcon(IconDrawable(IconType.LOCK, colorHintText, 13f), 4f)
            })
        } else if (visible.isEmpty()) {
            cardsList.addView(TextView(this@ClipMaxImeService).apply {
                text = when {
                    keyboardSearchQuery.isNotEmpty() -> "No clips match \"$keyboardSearchQuery\""
                    pinnedOnly -> "No pinned clips yet. Pin a clip to keep it handy."
                    else -> "No saved clips yet. Copy text to capture cards."
                }
                textSize = 11f
                setTextColor(colorHintText)
                setPadding(dp(12), dp(20), dp(12), dp(20))
                gravity = Gravity.CENTER
            })
        } else {
            val cardGapPx = dp(ClipboardPanelSpec.CARD_SPACING_DP).toInt()
            val halfGapLeft = cardGapPx / 2
            val halfGapRight = cardGapPx - halfGapLeft
            val totalVisible = visible.size

            // Two-column responsive grid layout (chunk items into pairs)
            var i = 0
            while (i < totalVisible) {
                val hasNextRow = (i + 2) < totalVisible
                val rowLayout = LinearLayout(this@ClipMaxImeService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 2f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = if (hasNextRow) cardGapPx else 0
                    }
                }

                val item1 = visible[i]
                val serial1 = ClipboardPanelSpec.computeSerialNumber(i, totalVisible)
                val card1 = buildCompactCard(item1, serialNumber = serial1)
                rowLayout.addView(card1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    rightMargin = halfGapLeft
                })

                if (i + 1 < totalVisible) {
                    val item2 = visible[i + 1]
                    val serial2 = ClipboardPanelSpec.computeSerialNumber(i + 1, totalVisible)
                    val card2 = buildCompactCard(item2, serialNumber = serial2)
                    rowLayout.addView(card2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        leftMargin = halfGapRight
                    })
                } else {
                    // Empty spacer view for odd count so 1st card maintains exact 50% width
                    val spacer = View(this@ClipMaxImeService).apply {
                        visibility = View.INVISIBLE
                    }
                    rowLayout.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f).apply {
                        leftMargin = halfGapRight
                    })
                }

                cardsList.addView(rowLayout)
                i += 2
            }
        }
    }

    private fun refreshClipboardPanelInPlace() {
        val cardsList = clipboardCardsContainer
        val scroll = clipboardScrollView
        if (!isClipboardPanelOpen || cardsList == null || scroll == null) {
            return
        }
        val visible = getVisibleClipboardItems()
        val preservedScrollY = scroll.scrollY
        clipboardSavedScrollY = preservedScrollY
        clipboardHeaderCountTv?.text = clipboardHeaderLabel(visible.size)
        updateClipboardCaptureToggleUI()
        clipboardMergeBanner?.visibility = if (isMergeMode) View.VISIBLE else View.GONE
        populateClipboardCards(cardsList, visible)
        scroll.post {
            if (clipboardScrollView === scroll) {
                scroll.scrollTo(0, preservedScrollY)
            }
        }
    }

    private fun buildClipboardPanel(): View = LinearLayout(this).apply {
        clipboardPanelRoot = this
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colorBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        val visible = getVisibleClipboardItems()

        // Gboard-inspired Clipboard Navigation Header:
        // Left: prominent circular back arrow | Center: Clipboard title | Right: ON/OFF capture toggle
        val subHeader = FrameLayout(this@ClipMaxImeService).apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setBackgroundColor(if (isClassicTheme) colorAltDeepBg else colorChipBg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ClipboardPanelSpec.HEADER_HEIGHT_DP).toInt())

            val backBtn = FrameLayout(this@ClipMaxImeService).apply {
                background = roundedRipple(colorKeyBg, dp(16f), colorKeyBorder)
                contentDescription = "Back to keyboard"
                val iv = createIconView(IconDrawable(IconType.ARROW_LEFT, colorKeyText, 14.5f))
                addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                setOnClickListener {
                    isClipboardPanelOpen = false
                    clipboardSavedScrollY = 0
                    pinnedOnly = false
                    renderFullImeView()
                }
            }
            addView(backBtn, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.START or Gravity.CENTER_VERTICAL))

            val titleTv = TextView(this@ClipMaxImeService).apply {
                text = clipboardHeaderLabel(visible.size)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
                gravity = Gravity.CENTER
                setSingleLine(true)
            }
            clipboardHeaderCountTv = titleTv
            addView(titleTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

            val captureToggle = FrameLayout(this@ClipMaxImeService).apply {
                isClickable = true
                isFocusable = true
                val label = TextView(this@ClipMaxImeService).apply {
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                }
                clipboardCaptureLabelRef = label
                addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                val thumb = View(this@ClipMaxImeService)
                clipboardCaptureThumbRef = thumb
                addView(thumb, FrameLayout.LayoutParams(dp(18), dp(18)))

                setOnClickListener {
                    val nextEnabled = !CapturePreferences.isEnabled(captureMode)
                    CapturePreferences.setCaptureEnabled(capturePreferences, nextEnabled)
                    captureMode = CapturePreferences.mode(capturePreferences)
                    updateCaptureMonitoring()
                    updateClipboardCaptureToggleUI()
                    updateCaptureStatus()
                }
            }
            clipboardCaptureToggleRef = captureToggle
            addView(captureToggle, FrameLayout.LayoutParams(dp(54), dp(24), Gravity.END or Gravity.CENTER_VERTICAL))
            updateClipboardCaptureToggleUI()
        }
        addView(subHeader)

        val headerDivider = View(this@ClipMaxImeService).apply {
            setBackgroundColor(colorKeyBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        addView(headerDivider)

        val mergeBanner = TextView(this@ClipMaxImeService).apply {
            text = "Merge Mode: Drag source card onto target card or tap another card to merge."
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentText)
            setBackgroundColor(colorAccent)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = if (isMergeMode) View.VISIBLE else View.GONE
            setOnClickListener {
                exitMergeMode(refresh = true)
                setStatus("Merge cancelled.")
            }
        }
        clipboardMergeBanner = mergeBanner
        addView(mergeBanner)

        // Scrollable cards list with 1.5x card spacing (9dp)
        val scrollView = android.widget.ScrollView(this@ClipMaxImeService).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                clipboardSavedScrollY = scrollY
            }
        }
        clipboardScrollView = scrollView

        val listPadPx = dp(ClipboardPanelSpec.CARD_SPACING_DP).toInt()
        val cardsList = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(listPadPx, listPadPx, listPadPx, listPadPx)
        }
        clipboardCardsContainer = cardsList

        populateClipboardCards(cardsList, visible)

        scrollView.addView(cardsList)
        addView(scrollView)

        val restoreY = clipboardSavedScrollY
        if (restoreY > 0) {
            scrollView.post { scrollView.scrollTo(0, restoreY) }
        }
    }

    private var undoBtnRef: Button? = null
    private var redoBtnRef: Button? = null

    private fun dismissUndoRedoControl() {
        val host = undoRedoHost ?: return
        undoRedoHost = null
        undoBtnRef = null
        redoBtnRef = null
        host.animate().cancel()
        host.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(120)
            .withEndAction {
                imeSurfaceFrame?.removeView(host)
            }
            .start()
    }

    private fun updateUndoRedoControl() {
        val undoBtn = undoBtnRef ?: return
        val redoBtn = redoBtnRef ?: return
        val hasUndo = undoStack.isNotEmpty()
        val hasRedo = redoStack.isNotEmpty()

        if (!hasUndo && !hasRedo) {
            dismissUndoRedoControl()
            return
        }

        undoBtn.isEnabled = hasUndo
        undoBtn.isClickable = hasUndo
        undoBtn.alpha = if (hasUndo) 1f else 0.38f
        undoBtn.setTextColor(if (hasUndo) colorKeyText else colorHintText)
        undoBtn.setIcon(IconDrawable(IconType.UNDO, if (hasUndo) colorKeyText else colorHintText, 12f), 4f)

        redoBtn.isEnabled = hasRedo
        redoBtn.isClickable = hasRedo
        redoBtn.alpha = if (hasRedo) 1f else 0.38f
        redoBtn.setTextColor(if (hasRedo) colorKeyText else colorHintText)
        redoBtn.setIcon(IconDrawable(IconType.REDO, if (hasRedo) colorKeyText else colorHintText, 12f), 4f)
    }

    private fun showUndoRedoControl() {
        val surface = imeSurfaceFrame ?: return
        val hasUndo = undoStack.isNotEmpty()
        val hasRedo = redoStack.isNotEmpty()
        if (!hasUndo && !hasRedo) return

        val existing = undoRedoHost
        if (existing != null && existing.parent === surface) {
            existing.animate().cancel()
            existing.alpha = 1f
            existing.scaleX = 1f
            existing.scaleY = 1f
            existing.visibility = View.VISIBLE
            updateUndoRedoControl()
            existing.bringToFront()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = roundedDrawable(colorKeyBg, dp(16f), colorAccent)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(10f)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

        val undoBtn = Button(this).apply {
            text = "Undo"
            isAllCaps = false
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            isEnabled = hasUndo
            isClickable = hasUndo
            alpha = if (hasUndo) 1f else 0.38f
            setTextColor(if (hasUndo) colorKeyText else colorHintText)
            background = roundedRipple(colorKeyBg, dp(12f), 0)
            setIcon(IconDrawable(IconType.UNDO, if (hasUndo) colorKeyText else colorHintText, 12f), 4f)
            setOnClickListener {
                performUndo()
            }
        }
        undoBtnRef = undoBtn
        container.addView(undoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))

        val div = View(this).apply {
            setBackgroundColor(colorKeyBorder)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(16)).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
            }
        }
        container.addView(div)

        val redoBtn = Button(this).apply {
            text = "Redo"
            isAllCaps = false
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            isEnabled = hasRedo
            isClickable = hasRedo
            alpha = if (hasRedo) 1f else 0.38f
            setTextColor(if (hasRedo) colorKeyText else colorHintText)
            background = roundedRipple(colorKeyBg, dp(12f), 0)
            setIcon(IconDrawable(IconType.REDO, if (hasRedo) colorKeyText else colorHintText, 12f), 4f)
            setOnClickListener {
                performRedo()
            }
        }
        redoBtnRef = redoBtn
        container.addView(redoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))

        val closeBtn = ImageView(this).apply {
            setImageDrawable(IconDrawable(IconType.CLOSE, colorHintText, 10f))
            setPadding(dp(6), dp(6), dp(4), dp(6))
            setOnClickListener {
                dismissUndoRedoControl()
            }
        }
        container.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        }
        undoRedoHost = container
        surface.addView(container, lp)

        container.alpha = 0f
        container.scaleX = 0.85f
        container.scaleY = 0.85f
        container.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun exitMergeMode(refresh: Boolean = true) {
        if (isMergeMode || mergeSourceId != null) {
            isMergeMode = false
            mergeSourceId = null
            if (refresh) {
                if (isClipboardPanelOpen && clipboardCardsContainer != null) {
                    refreshClipboardPanelInPlace()
                } else {
                    reloadItems()
                }
            }
        }
    }

    private fun performMerge(sourceId: String, targetId: String) {
        if (sourceId == targetId) {
            setStatus("Cannot merge a card with itself.")
            return
        }
        if (isSplitMergeHistoryBusy) {
            setStatus("Please wait for the previous operation to finish.")
            return
        }
        isMergeMode = false
        mergeSourceId = null
        isSplitMergeHistoryBusy = true
        io.execute {
            try {
                val mergeRes = store.merge(sourceId, targetId)
                val snapshot = OperationSnapshot(
                    description = "Merge",
                    removedItems = listOf(mergeRes.sourceItem, mergeRes.targetItem),
                    removedContents = mapOf(
                        mergeRes.sourceItem.id to mergeRes.sourceText,
                        mergeRes.targetItem.id to mergeRes.targetText
                    ),
                    addedItems = listOf(mergeRes.mergedItem),
                    addedContents = mapOf(
                        mergeRes.mergedItem.id to "${mergeRes.sourceText}\n${mergeRes.targetText}"
                    )
                )
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        undoStack.push(snapshot)
                        redoStack.clear()
                        setStatus("Merged 2 clips into 1.")
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus("Merge failed: ${e.message}")
                        if (isClipboardPanelOpen && clipboardCardsContainer != null) {
                            refreshClipboardPanelInPlace()
                        }
                    }
                }
            }
        }
    }

    private fun splitItem(targetId: String) {
        if (isSplitMergeHistoryBusy) {
            setStatus("Please wait for the previous operation to finish.")
            return
        }
        isSplitMergeHistoryBusy = true
        io.execute {
            try {
                val splitRes = store.split(targetId)
                if (splitRes == null) {
                    main.post {
                        isSplitMergeHistoryBusy = false
                        if (!serviceDestroyed) {
                            setStatus("Clip text is too short to split.")
                        }
                    }
                    return@execute
                }
                val snapshot = OperationSnapshot(
                    description = "Split",
                    removedItems = listOf(splitRes.originalItem),
                    removedContents = mapOf(splitRes.originalItem.id to splitRes.originalText),
                    addedItems = listOf(splitRes.part1Item, splitRes.part2Item),
                    addedContents = mapOf(
                        splitRes.part1Item.id to splitRes.part1Text,
                        splitRes.part2Item.id to splitRes.part2Text
                    )
                )
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        undoStack.push(snapshot)
                        redoStack.clear()
                        setStatus("Split clip into 2 cards.")
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus("Split failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun performUndo() {
        if (isSplitMergeHistoryBusy || undoStack.isEmpty()) return
        val snapshot = undoStack.peek()
        isSplitMergeHistoryBusy = true
        val deleteIds = snapshot.addedItems.map { it.id }
        val restoreRecords = snapshot.removedItems.map { orig ->
            RestoreRecord(
                id = orig.id,
                title = orig.title,
                content = snapshot.removedContents[orig.id] ?: "",
                pinned = orig.pinned,
                createdAt = orig.createdAt
            )
        }
        io.execute {
            try {
                store.applyHistoryTransaction(deleteIds, restoreRecords)
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        if (undoStack.isNotEmpty() && undoStack.peek() === snapshot) {
                            undoStack.pop()
                        } else {
                            undoStack.remove(snapshot)
                        }
                        redoStack.push(snapshot)
                        setStatus("Undid ${snapshot.description}.")
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus("Undo failed: ${e.message}")
                        updateUndoRedoControl()
                    }
                }
            }
        }
    }

    private fun performRedo() {
        if (isSplitMergeHistoryBusy || redoStack.isEmpty()) return
        val snapshot = redoStack.peek()
        isSplitMergeHistoryBusy = true
        val deleteIds = snapshot.removedItems.map { it.id }
        val restoreRecords = snapshot.addedItems.map { added ->
            RestoreRecord(
                id = added.id,
                title = added.title,
                content = snapshot.addedContents[added.id] ?: "",
                pinned = added.pinned,
                createdAt = added.createdAt
            )
        }
        io.execute {
            try {
                store.applyHistoryTransaction(deleteIds, restoreRecords)
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        if (redoStack.isNotEmpty() && redoStack.peek() === snapshot) {
                            redoStack.pop()
                        } else {
                            redoStack.remove(snapshot)
                        }
                        undoStack.push(snapshot)
                        setStatus("Redid ${snapshot.description}.")
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus("Redo failed: ${e.message}")
                        updateUndoRedoControl()
                    }
                }
            }
        }
    }

    private fun dismissContextMenu(animate: Boolean = false) {
        val host = contextMenuHost ?: run {
            contextMenuItemId = null
            return
        }
        contextMenuHost = null
        contextMenuItemId = null
        host.isClickable = false
        host.isFocusable = false
        val surface = imeSurfaceFrame

        if (!animate || surface == null) {
            host.animate().cancel()
            surface?.removeView(host)
            if (pendingRenderAfterContextMenu && imeRootFrame != null) {
                pendingRenderAfterContextMenu = false
                if (isClipboardPanelOpen && clipboardCardsContainer != null) {
                    refreshClipboardPanelInPlace()
                } else {
                    renderFullImeView()
                }
            }
            return
        }

        host.animate()
            .alpha(0f)
            .setDuration(110)
            .withEndAction {
                surface.removeView(host)
                if (pendingRenderAfterContextMenu && contextMenuHost == null && imeRootFrame != null) {
                    pendingRenderAfterContextMenu = false
                    if (isClipboardPanelOpen && clipboardCardsContainer != null) {
                        refreshClipboardPanelInPlace()
                    } else {
                        renderFullImeView()
                    }
                }
            }
            .start()
    }

    private fun offsetRelativeTo(child: View, ancestor: View): Pair<Int, Int> {
        var left = 0
        var top = 0
        var current: View? = child
        while (current != null && current != ancestor) {
            left += current.left - current.scrollX
            top += current.top - current.scrollY
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return left to top
    }

    private fun showContextMenu(targetCard: View, item: ClipItem, serialNumber: Int = 1) {
        dismissContextMenu(animate = false)
        val surface = imeSurfaceFrame ?: return
        val content = contentContainer ?: return
        val surfaceW = content.width.takeIf { it > 0 } ?: surface.width
        val surfaceH = content.height.takeIf { it > 0 } ?: surface.height
        if (surfaceW <= 0 || surfaceH <= 0) return

        val targetId = item.id
        val scroll = clipboardScrollView
        if (scroll != null && scroll.height > 0) {
            val (_, cardTopRelScroll) = offsetRelativeTo(targetCard, scroll)
            val cardTopInViewport = cardTopRelScroll - scroll.scrollY
            val cardBottomInViewport = cardTopInViewport + targetCard.height
            val pad = dp(4)
            if (cardTopInViewport < pad) {
                scroll.scrollBy(0, cardTopInViewport - pad)
            } else if (cardBottomInViewport > scroll.height - pad) {
                scroll.scrollBy(0, cardBottomInViewport - (scroll.height - pad))
            }
        }

        val (cardLeftRaw, cardTopRaw) = offsetRelativeTo(targetCard, content)
        val cardW = targetCard.width.coerceAtLeast(dp(100))
        val cardH = targetCard.height.coerceAtLeast(dp(44))

        // Lock overlay host strictly to measured contentContainer pixel dimensions so
        // FrameLayout NEVER measures a MATCH_PARENT child against full-screen AT_MOST bounds.
        val host = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            layoutParams = FrameLayout.LayoutParams(surfaceW, surfaceH)
        }

        // Layer 1: Background ~50% dimming scrim blocking background interaction
        val scrim = View(this).apply {
            setBackgroundColor(0x80000000.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Dismiss clip options"
            layoutParams = FrameLayout.LayoutParams(surfaceW, surfaceH)
            setOnClickListener { dismissContextMenu(animate = true) }
        }
        host.addView(scrim)

        // Layer 2: Un-dimmed spotlight copy of the selected card at exact viewport bounds
        val scrollBoundsTop = if (scroll != null) offsetRelativeTo(scroll, content).second.coerceIn(0, surfaceH) else 0
        val scrollBoundsH = if (scroll != null && scroll.height > 0) {
            minOf(scroll.height, surfaceH - scrollBoundsTop)
        } else {
            surfaceH - scrollBoundsTop
        }
        val spotlightViewport = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            layoutParams = FrameLayout.LayoutParams(surfaceW, scrollBoundsH.coerceAtLeast(cardH)).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = scrollBoundsTop
            }
        }
        val spotlightCard = buildCompactCard(item, serialNumber = serialNumber, isSpotlight = true).apply {
            isClickable = true
            isLongClickable = false
            setOnClickListener { dismissContextMenu(animate = true) }
            setOnLongClickListener(null)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(8f)
            }
        }
        val cardLeftClamped = cardLeftRaw.coerceIn(0, (surfaceW - cardW).coerceAtLeast(0))
        val cardTopInSpotlight = cardTopRaw - scrollBoundsTop
        spotlightViewport.addView(
            spotlightCard,
            FrameLayout.LayoutParams(cardW, cardH).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = cardLeftClamped
                topMargin = cardTopInSpotlight
            }
        )
        host.addView(spotlightViewport)

        // Layer 3: Comfortable side-mounted contextual action panel (Pin/Unpin, Edit, Delete, Merge, Split)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val surfaceWDp = surfaceW / density
        val surfaceHDp = surfaceH / density
        val panelWidth = dp(OverlayUiSpec.resolveContextMenuPanelWidthDp(surfaceWDp)).toInt()
        val rowHeight = dp(OverlayUiSpec.CONTEXT_MENU_TARGET_ROW_HEIGHT_DP).toInt()
        val maxMenuHeightPx = dp(OverlayUiSpec.resolveContextMenuMaxHeightDp(surfaceHDp)).toInt()
        val iconSize = OverlayUiSpec.CONTEXT_MENU_ICON_SIZE_DP
        val menuSurfaceBg = OverlayUiSpec.resolveContextMenuBg(currentThemePalette)
        val panelCornerPx = dp(OverlayUiSpec.CONTEXT_MENU_CORNER_DP)
        val panelBg = roundedDrawable(menuSurfaceBg, panelCornerPx, colorKeyBorder)

        val panelRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(OverlayUiSpec.CONTEXT_MENU_PADDING_V_DP).toInt(), 0, dp(OverlayUiSpec.CONTEXT_MENU_PADDING_V_DP).toInt())

            var rowCount = 0
            fun addActionRow(
                label: String,
                iconType: IconType,
                tint: Int,
                onClick: () -> Unit
            ) {
                if (rowCount > 0) {
                    val div = View(this@ClipMaxImeService).apply {
                        setBackgroundColor(colorSnippetBorder)
                    }
                    addView(div, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        leftMargin = dp(12)
                        rightMargin = dp(12)
                    })
                }
                rowCount++

                val rowView = LinearLayout(this@ClipMaxImeService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    contentDescription = label
                    setPadding(
                        dp(OverlayUiSpec.CONTEXT_MENU_PADDING_H_DP).toInt(),
                        0,
                        dp(OverlayUiSpec.CONTEXT_MENU_PADDING_H_DP).toInt(),
                        0
                    )
                    background = roundedRipple(0, dp(8f), 0)

                    val iv = createIconView(IconDrawable(iconType, tint, iconSize))
                    addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(OverlayUiSpec.CONTEXT_MENU_ICON_GAP_DP).toInt()
                    })

                    val tv = TextView(this@ClipMaxImeService).apply {
                        text = label
                        textSize = OverlayUiSpec.CONTEXT_MENU_TEXT_SP
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(tint)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                    }
                    addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    setOnClickListener { onClick() }
                }
                addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
            }

            val latestItem = store.getItem(targetId) ?: item
            val pinColor = if (latestItem.pinned) colorActiveTabText else colorKeyText
            addActionRow(
                label = if (latestItem.pinned) "Unpin" else "Pin",
                iconType = if (latestItem.pinned) IconType.PIN_FILLED else IconType.PIN,
                tint = pinColor
            ) {
                dismissContextMenu(animate = true)
                io.execute {
                    val fresh = store.getItem(targetId) ?: return@execute
                    val nextPinned = !fresh.pinned
                    store.setPinned(targetId, nextPinned)
                    if (nextPinned) {
                        CapturePreferences.removeExpiry(capturePreferences, targetId)
                    }
                    main.post { reloadItems() }
                }
            }

            addActionRow(
                label = "Edit",
                iconType = IconType.EDIT,
                tint = colorKeyText
            ) {
                dismissContextMenu(animate = false)
                try {
                    val intent = Intent(this@ClipMaxImeService, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(MainActivity.EXTRA_EDIT_ITEM_ID, targetId)
                    }
                    startActivity(intent)
                } catch (_: Throwable) {}
            }

            addActionRow(
                label = "Delete",
                iconType = IconType.DELETE,
                tint = colorDangerText
            ) {
                dismissContextMenu(animate = true)
                io.execute {
                    if (store.delete(targetId)) {
                        CapturePreferences.removeExpiry(capturePreferences, targetId)
                        CapturePreferences.onItemsDeleted(capturePreferences, listOf(targetId))
                    }
                    main.post { reloadItems() }
                }
            }

            addActionRow(
                label = "Merge",
                iconType = IconType.MERGE,
                tint = colorKeyText
            ) {
                dismissContextMenu(animate = true)
                isMergeMode = true
                mergeSourceId = targetId
                setStatus("Merge Mode: Drag or tap another card to merge with \"${item.title}\"")
                if (isClipboardPanelOpen && clipboardCardsContainer != null) {
                    refreshClipboardPanelInPlace()
                } else {
                    reloadItems()
                }
            }

            addActionRow(
                label = "Split",
                iconType = IconType.SPLIT,
                tint = colorKeyText
            ) {
                dismissContextMenu(animate = true)
                splitItem(targetId)
            }
        }

        val panel = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            background = panelBg
            isClickable = true
            clipToOutline = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(10f)
            }
            addView(panelRows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxMenuHeightPx, View.MeasureSpec.AT_MOST)
        )
        val panelHeight = panel.measuredHeight.coerceAtMost(maxMenuHeightPx)

        // Mount on right side beside left-column cards, or left side beside right-column cards
        val cardOnLeft = (cardLeftClamped + cardW / 2) < (surfaceW / 2)
        val gap = dp(8)
        val edgePad = dp(8)
        val leftPos = if (cardOnLeft) {
            (cardLeftClamped + cardW + gap).coerceIn(edgePad, (surfaceW - panelWidth - edgePad).coerceAtLeast(edgePad))
        } else {
            (cardLeftClamped - panelWidth - gap).coerceIn(edgePad, (surfaceW - panelWidth - edgePad).coerceAtLeast(edgePad))
        }
        val maxTop = (surfaceH - panelHeight - dp(6)).coerceAtLeast(dp(4))
        val topPos = cardTopRaw.coerceIn(dp(4), maxTop)

        host.addView(
            panel,
            FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = leftPos
                topMargin = topPos
            }
        )

        contextMenuHost = host
        contextMenuItemId = targetId
        surface.addView(host)

        // Smooth restrained animations
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(140).start()

        spotlightCard.scaleX = 0.97f
        spotlightCard.scaleY = 0.97f
        spotlightCard.animate().scaleX(1f).scaleY(1f).setDuration(140).start()

        panel.alpha = 0f
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        panel.pivotX = if (cardOnLeft) 0f else panelWidth.toFloat()
        panel.pivotY = 0f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun displayTitleForCard(baseTitle: String, snippet: String): String {
        val cleaned = baseTitle.trim()
        val isGeneric = cleaned.isEmpty() ||
            cleaned.equals("Copy", ignoreCase = true) ||
            cleaned.equals("Select", ignoreCase = true) ||
            cleaned.equals("Selection", ignoreCase = true)
        if (!isGeneric) return cleaned
        if (snippet.isNotEmpty()) {
            return if (snippet.length > 32) "${snippet.take(32).trimEnd()}…" else snippet
        }
        return "Saved Clip"
    }

    private fun buildCompactCard(
        item: ClipItem,
        serialNumber: Int = 1,
        isSpotlight: Boolean = false
    ): View = LinearLayout(this).apply {
        val rawContent = snippetCache[item.id] ?: getRawSnippetPrefix(item, 240)
        val previewContent = ClipboardPanelSpec.resolveCardContentPreview(rawContent, 220)
        val serialLabel = "#$serialNumber"
        val radiusPx = dp(ClipboardPanelSpec.CARD_CORNER_RADIUS_DP)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val minCardHeightPx = dp(
            if (isLandscape) ClipboardPanelSpec.CARD_MIN_HEIGHT_LANDSCAPE_DP
            else ClipboardPanelSpec.CARD_MIN_HEIGHT_PORTRAIT_DP
        ).toInt()

        orientation = LinearLayout.VERTICAL
        isFocusable = true
        minimumHeight = minCardHeightPx
        contentDescription = "Clip $serialLabel. $previewContent. Tap to insert. Long press for options."
        setPadding(
            dp(ClipboardPanelSpec.CARD_PADDING_HORIZONTAL_DP).toInt(),
            dp(ClipboardPanelSpec.CARD_PADDING_VERTICAL_DP).toInt(),
            dp(ClipboardPanelSpec.CARD_PADDING_HORIZONTAL_DP).toInt(),
            dp(ClipboardPanelSpec.CARD_PADDING_VERTICAL_DP).toInt()
        )
        val isSource = isMergeMode && item.id == mergeSourceId
        val isTargetCandidate = isMergeMode && item.id != mergeSourceId
        val cardSurfaceBg = if (isClassicTheme) colorKeyBg else colorChipBg

        background = when {
            isSpotlight -> roundedDrawable(cardSurfaceBg, radiusPx, colorAccent)
            isSource -> roundedDrawable(if (isNightMode) 0x332DD4BF else 0x330D9488, radiusPx, colorAccent)
            isTargetCandidate -> roundedRipple(cardSurfaceBg, radiusPx, colorKeyBorder)
            else -> roundedRipple(cardSurfaceBg, radiusPx, colorKeyBorder)
        }

        // Row 1: Strictly ONLY the dynamic bottom-up positional serial number ("#1", "#2", ...)
        val serialTv = TextView(this@ClipMaxImeService).apply {
            text = serialLabel
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorModText)
            setSingleLine(true)
            includeFontPadding = false
        }
        addView(
            serialTv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(5)
            }
        )

        // Row 2: Direct beginning of the original clipboard content (unified surface, readable medium weight)
        val contentTv = TextView(this@ClipMaxImeService).apply {
            text = previewContent
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colorKeyText)
            setLineSpacing(dp(2f), 1f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(
            contentTv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        if (!isSpotlight) {
            if (isSource) {
                // Long press to drag source card
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val clipData = android.content.ClipData.newPlainText("merge_source_id", item.id)
                    val shadow = View.DragShadowBuilder(this)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        startDragAndDrop(clipData, shadow, item.id, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        startDrag(clipData, shadow, item.id, 0)
                    }
                    true
                }
            } else if (isTargetCandidate) {
                // Drop target listener
                setOnDragListener { v, event ->
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> true
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                            v.background = roundedDrawable(if (isNightMode) 0x552DD4BF else 0x550D9488, radiusPx, colorAccent)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                v.elevation = dp(6f)
                            }
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_EXITED -> {
                            v.background = roundedRipple(colorKeyBg, radiusPx, colorKeyBorder)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                v.elevation = 0f
                            }
                            true
                        }
                        android.view.DragEvent.ACTION_DROP -> {
                            val srcId = event.localState as? String ?: mergeSourceId
                            if (srcId != null && srcId != item.id) {
                                performMerge(srcId, item.id)
                                true
                            } else {
                                false
                            }
                        }
                        android.view.DragEvent.ACTION_DRAG_ENDED -> {
                            v.background = roundedRipple(colorKeyBg, radiusPx, colorKeyBorder)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                v.elevation = 0f
                            }
                            true
                        }
                        else -> true
                    }
                }
            }

            // Click handling: tap-to-merge when in merge mode, or insert
            setOnClickListener {
                if (contextMenuHost != null) {
                    dismissContextMenu(animate = true)
                } else if (isMergeMode) {
                    val src = mergeSourceId
                    if (src == null || src == item.id) {
                        exitMergeMode()
                        setStatus("Merge cancelled.")
                    } else {
                        performMerge(src, item.id)
                    }
                } else {
                    insertItem(item)
                }
            }

            // Normal long press to show contextual actions (disabled during merge mode)
            if (!isMergeMode) {
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showContextMenu(this, item, serialNumber)
                    true
                }
            }
        }
    }

    // =========================================================================
    // 2. SAVED-CLIPS HORIZONTAL STRIP
    // =========================================================================
    private fun buildSavedClipsStrip(): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(colorAltDeepBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))

        val container = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        chipsContainer = container
        addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderClipChips() {
        val container = chipsContainer ?: return
        container.removeAllViews()

        if (isPasswordTarget) {
            container.addView(TextView(this).apply {
                text = "Password field: Saved clips withheld for privacy"
                textSize = 10.5f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setIcon(IconDrawable(IconType.LOCK, colorHintText, 11f), 4f)
            })
            return
        }

        val base = if (keyboardSearchQuery.isNotEmpty()) keyboardSearchResults else items
        val visible = if (pinnedOnly) base.filter { it.pinned } else base

        if (visible.isEmpty()) {
            container.addView(TextView(this).apply {
                text = when {
                    keyboardSearchQuery.isNotEmpty() -> "No clips match \"$keyboardSearchQuery\""
                    pinnedOnly -> "No pinned clips"
                    else -> "No saved clips. Copy text to create cards."
                }
                textSize = 10.5f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            })
            return
        }

        visible.forEach { item ->
            container.addView(buildCompactChip(item))
        }
    }

    private fun buildCompactChip(item: ClipItem): View = LinearLayout(this).apply {
        val (baseTitle, chunkLabel) = parseTitleAndChunk(item.title)

        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val chipPadH = dp(OverlayUiSpec.CLIP_CHIP_PADDING_H_DP).toInt()
        setPadding(chipPadH, 0, chipPadH, 0)
        background = roundedRipple(colorChipBg, dp(OverlayUiSpec.CLIP_CHIP_CORNER_DP), colorChipBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(OverlayUiSpec.CLIP_CHIP_HEIGHT_DP).toInt()).apply {
            rightMargin = dp(6)
        }

        if (item.pinned) {
            val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 11f)).apply {
                setPadding(0, 0, dp(4), 0)
            }
            addView(pinIcon)
        }

        val rawSnippet = snippetCache[item.id] ?: getRawSnippetPrefix(item, 180)
        val previewText = ClipPreviewFormatter.resolveChipPreview(
            baseTitle = baseTitle,
            rawSnippet = rawSnippet,
            isPasswordTarget = isPasswordTarget,
            maxChars = 72
        )

        val screenW = resources.displayMetrics.widthPixels
        val oneHandedPad = if ((themePreferences.getString("one_handed_mode", "off") ?: "off") != "off") dp(54) else 0
        val availableToolbarW = (screenW - oneHandedPad - dp(86)).coerceAtLeast(dp(180))
        val adaptiveMaxW = minOf(dp(220), (availableToolbarW * 0.62f).toInt()).coerceAtLeast(dp(145))

        val title = TextView(this@ClipMaxImeService).apply {
            text = previewText
            textSize = OverlayUiSpec.CLIP_CHIP_TEXT_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(colorKeyText)
            minWidth = dp(56)
            maxWidth = adaptiveMaxW
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (chunkLabel != null) {
            val chunkBadge = TextView(this@ClipMaxImeService).apply {
                text = chunkLabel
                textSize = 8.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorActiveTabText)
                setPadding(dp(4), dp(1), dp(4), dp(1))
                background = roundedDrawable(colorActiveTabBg, dp(4f), 0)
                setSingleLine(true)
            }
            addView(chunkBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(5)
            })
        }

        setOnClickListener {
            insertItem(item)
        }
    }


    // =========================================================================
    // 3. STATUS / IN-FLIGHT BANNER
    // =========================================================================
    private fun buildStatusBanner(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(2), dp(10), dp(2))
        setBackgroundColor(colorAltDeepBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(KeyboardLayoutSpec.STATUS_BANNER_HEIGHT_DP).toInt())
        visibility = if (isInserting || isPasswordTarget) View.VISIBLE else View.GONE
        statusBannerView = this

        val lbl = TextView(this@ClipMaxImeService).apply {
            textSize = 11f
            setTextColor(colorKeyText)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        statusLabel = lbl
        addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val cancel = Button(this@ClipMaxImeService).apply {
            text = "Cancel"
            isAllCaps = false
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(colorDangerText)
            background = roundedDrawable(0x22F87171, dp(5f))
            setOnClickListener { cancelInsertion(userInitiated = true) }
        }
        statusCancelBtn = cancel
        addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)))
    }

    // =========================================================================
    // 4. ERGONOMIC 4-ROW QWERTY / NUMERIC / SYMBOL KEYBOARD
    // =========================================================================
    private fun computeCurrentViewportSpec(): KeyboardLayoutSpec.ViewportSpecDp {
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val screenHeightDp = dm.heightPixels / density
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val heightPref = themePreferences.getString("keyboard_height", "normal") ?: "normal"
        return KeyboardLayoutSpec.computeViewportSpecDp(
            heightPref = heightPref,
            screenHeightDp = screenHeightDp,
            isLandscape = isLandscape
        )
    }

    private fun applyCanonicalViewportHeightInPlace() {
        val viewport = computeCurrentViewportSpec()
        val host = mainViewportFrame ?: return
        val lp = host.layoutParams ?: return
        lp.height = dp(viewport.mainPanelHeightDp).toInt()
        host.layoutParams = lp
    }

    private fun refreshKeyboardGeometry(): KeyboardLayoutSpec.GeometryDp {
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val screenHeightDp = dm.heightPixels / density
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val heightPref = themePreferences.getString("keyboard_height", "normal") ?: "normal"
        val geom = KeyboardLayoutSpec.computeGeometryDp(
            heightPref = heightPref,
            screenHeightDp = screenHeightDp,
            isLandscape = isLandscape,
            hasFilterBar = isFilterMode,
            hasStatusBanner = isInserting || isPasswordTarget
        )
        currentLayoutGeometry = geom
        return geom
    }

    private fun getKeyRowHeight(): Int = dp(currentLayoutGeometry.keyHeightDp).toInt()

    private fun keyCornerRadiusPx(): Float = dp(currentLayoutGeometry.cornerRadiusDp)

    private fun keyLp(weight: Float): LinearLayout.LayoutParams {
        val sideGap = (dp(currentLayoutGeometry.horizontalGapDp) / 2f).toInt().coerceAtLeast(1)
        val h = getKeyRowHeight()
        return LinearLayout.LayoutParams(0, h, weight).apply {
            leftMargin = sideGap
            rightMargin = sideGap
        }
    }

    private fun renderKeyboardRows() {
        val container = keyboardRowsContainer ?: return
        container.removeAllViews()
        letterKeyViews.clear()
        enterKeyView = null
        enterKeyImageView = null
        val geom = refreshKeyboardGeometry()
        container.setPadding(
            dp(geom.sidePaddingDp).toInt(),
            dp(geom.topPaddingDp).toInt(),
            dp(geom.sidePaddingDp).toInt(),
            dp(geom.bottomPaddingDp).toInt()
        )

        when (keyboardMode) {
            KeyboardLayoutSpec.KeyboardMode.ALPHA -> renderAlphaRows(container)
            KeyboardLayoutSpec.KeyboardMode.SYMBOLS -> renderNumericRows(container)
            KeyboardLayoutSpec.KeyboardMode.SYMBOLS_EXT -> renderSymbolRows(container)
            KeyboardLayoutSpec.KeyboardMode.NUMPAD -> renderNumpadRows(container)
        }
        updateToolbarCenterVisibility()
    }

    // 4A. ALPHA (QWERTY with embedded long-press 1-0 on top row, matching Gboard 4-row ergonomics)
    private fun renderAlphaRows(parent: LinearLayout) {
        // Row 1: 10 dual-function keys (Q1 W2 E3 R4 T5 Y6 U7 I8 O9 P0)
        val row1 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.TOP_ROW_EMBEDDED_NUMBERS.forEach { (char, digit) ->
            row1.addView(createAlphaKeyWithHint(char, digit), keyLp(1f))
        }
        parent.addView(row1)

        // Row 2: 9 keys (A S D F G H J K L) centered horizontally with 0.5f weight spacers
        val row2 = createKeyRow(isBottomRow = false)
        val spacerH = getKeyRowHeight()
        row2.addView(View(this), LinearLayout.LayoutParams(0, spacerH, 0.5f))
        alphaRow2.forEach { char ->
            row2.addView(createStandardKey(char), keyLp(1f))
        }
        row2.addView(View(this), LinearLayout.LayoutParams(0, spacerH, 0.5f))
        parent.addView(row2)

        // Row 3: Shift (1.5f), 7 keys (Z X C V B N M), Backspace (1.5f) = 10.0f aligned flush
        val row3 = createKeyRow(isBottomRow = false)
        val shiftBtn = createShiftKey()
        shiftButton = shiftBtn
        row3.addView(shiftBtn, keyLp(1.5f))
        alphaRow3.forEach { char ->
            row3.addView(createStandardKey(char), keyLp(1f))
        }
        row3.addView(createBackspaceKey(), keyLp(1.5f))
        parent.addView(row3)

        // Row 4: ?123 (1.45f), comma (0.9f), Globe (0.9f), Space (4.4f), period (0.9f), Enter (1.45f) = 10.0f
        val row4 = createKeyRow(isBottomRow = true)
        row4.addView(createModifierKey("?123") {
            dismissPasteUndoCapsule()
            modeController.toSymbols()
            renderKeyboardRows()
        }, keyLp(1.45f))

        row4.addView(createCommaEmojiKey(), keyLp(0.9f))
        row4.addView(createGlobeKey(), keyLp(0.9f))
        row4.addView(createSpaceKey(), keyLp(4.4f))
        row4.addView(createStandardKey("."), keyLp(0.9f))
        row4.addView(createEnterKey(), keyLp(1.45f))
        parent.addView(row4)
    }

    // 4B. NUMERIC (?123)
    private fun renderNumericRows(parent: LinearLayout) {
        val row1 = createKeyRow(isBottomRow = false)
        numRow1.forEach { digit ->
            row1.addView(createStandardKey(digit), keyLp(1f))
        }
        parent.addView(row1)

        val row2 = createKeyRow(isBottomRow = false)
        numRow2.forEach { sym ->
            row2.addView(createStandardKey(sym), keyLp(1f))
        }
        parent.addView(row2)

        val row3 = createKeyRow(isBottomRow = false)
        row3.addView(createModifierKey("=\\<") {
            dismissPasteUndoCapsule()
            modeController.toSymbolsExt()
            renderKeyboardRows()
        }, keyLp(1.5f))

        numRow3.forEach { sym ->
            row3.addView(createStandardKey(sym), keyLp(1f))
        }

        row3.addView(createBackspaceKey(), keyLp(1.5f))
        parent.addView(row3)

        val row4 = createKeyRow(isBottomRow = true)
        row4.addView(createModifierKey("ABC") {
            dismissPasteUndoCapsule()
            modeController.toAlpha()
            renderKeyboardRows()
        }, keyLp(1.45f))

        row4.addView(createCommaEmojiKey(), keyLp(0.9f))
        row4.addView(createNumpadToggleKey(), keyLp(0.9f))
        row4.addView(createSpaceKey(), keyLp(4.4f))
        row4.addView(createStandardKey("."), keyLp(0.9f))
        row4.addView(createEnterKey(), keyLp(1.45f))
        parent.addView(row4)
    }

    // 4C. SECONDARY SYMBOLS (=\<)
    private fun renderSymbolRows(parent: LinearLayout) {
        val row1 = createKeyRow(isBottomRow = false)
        symRow1.forEach { sym ->
            row1.addView(createStandardKey(sym), keyLp(1f))
        }
        parent.addView(row1)

        val row2 = createKeyRow(isBottomRow = false)
        symRow2.forEach { sym ->
            row2.addView(createStandardKey(sym), keyLp(1f))
        }
        parent.addView(row2)

        val row3 = createKeyRow(isBottomRow = false)
        row3.addView(createModifierKey("?123") {
            dismissPasteUndoCapsule()
            modeController.toSymbols()
            renderKeyboardRows()
        }, keyLp(1.5f))

        symRow3.forEach { sym ->
            row3.addView(createStandardKey(sym), keyLp(0.875f))
        }

        row3.addView(createBackspaceKey(), keyLp(1.5f))
        parent.addView(row3)

        val row4 = createKeyRow(isBottomRow = true)
        row4.addView(createModifierKey("ABC") {
            dismissPasteUndoCapsule()
            modeController.toAlpha()
            renderKeyboardRows()
        }, keyLp(1.45f))

        row4.addView(createCommaEmojiKey(), keyLp(0.9f))
        row4.addView(createGlobeKey(), keyLp(0.9f))
        row4.addView(createSpaceKey(), keyLp(4.4f))
        row4.addView(createStandardKey("."), keyLp(0.9f))
        row4.addView(createEnterKey(), keyLp(1.45f))
        parent.addView(row4)
    }

    // 4D. EXACT GBOARD-STYLE NUMERIC KEYPAD (Reached via SYMBOLS -> "12 34")
    private fun createNumpadToggleKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = "Switch to numeric keypad"

        val tv = TextView(this@ClipMaxImeService).apply {
            text = KeyboardLayoutSpec.NumpadLayoutSpec.TOGGLE_KEY_LABEL
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorModText)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLineSpacing(0f, 0.88f)
            letterSpacing = 0.08f
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(this, {
            dismissPasteUndoCapsule()
            modeController.toNumpad()
            renderKeyboardRows()
        })
    }

    private fun createNumpadSpaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = "Space"

        val iv = createIconView(IconDrawable(IconType.SPACE, colorModText, 18f))
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        attachKeyTouchFeedback(this, { handleSpacePress() })
    }

    private fun createNumpadOperatorRail(operators: List<KeyboardLayoutSpec.NumpadKeySpec>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(colorModBg, keyCornerRadiusPx(), colorKeyBorder)
        clipToOutline = true

        val railCorner = (keyCornerRadiusPx() * 0.72f).coerceAtLeast(dp(4f))
        operators.forEach { spec ->
            val segment = FrameLayout(this@ClipMaxImeService).apply {
                background = roundedRipple(0x00000000, railCorner, 0)
                contentDescription = spec.contentDescription

                val tv = TextView(this@ClipMaxImeService).apply {
                    text = spec.label
                    textSize = (currentLayoutGeometry.primaryTextSizeSp - 1.5f).coerceIn(14.5f, 19f)
                    typeface = Typeface.DEFAULT
                    setTextColor(colorModText)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                val ch = spec.commitChar ?: spec.label
                attachKeyTouchFeedback(
                    keyView = this,
                    onCommit = { handleGenericCharPress(ch) },
                    previewChar = { spec.label }
                )
            }
            addView(segment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun createNumpadKey(spec: KeyboardLayoutSpec.NumpadKeySpec): View = when (spec.role) {
        KeyboardLayoutSpec.NumpadKeyRole.SPACE -> createNumpadSpaceKey()
        KeyboardLayoutSpec.NumpadKeyRole.BACKSPACE -> createBackspaceKey()
        KeyboardLayoutSpec.NumpadKeyRole.RETURN_ALPHA -> createModifierKey(spec.label) {
            dismissPasteUndoCapsule()
            modeController.toAlpha()
            renderKeyboardRows()
        }.apply {
            contentDescription = spec.contentDescription
        }
        KeyboardLayoutSpec.NumpadKeyRole.TO_SYMBOLS -> FrameLayout(this).apply {
            background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)
            contentDescription = spec.contentDescription

            val tv = TextView(this@ClipMaxImeService).apply {
                text = spec.label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorModText)
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                letterSpacing = -0.02f
            }
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            attachKeyTouchFeedback(this, {
                dismissPasteUndoCapsule()
                modeController.toSymbols()
                renderKeyboardRows()
            })
        }
        KeyboardLayoutSpec.NumpadKeyRole.ACTION_ENTER -> createEnterKey()
        KeyboardLayoutSpec.NumpadKeyRole.DIGIT -> FrameLayout(this).apply {
            background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)
            contentDescription = spec.contentDescription

            val tv = TextView(this@ClipMaxImeService).apply {
                text = spec.label
                textSize = (currentLayoutGeometry.primaryTextSizeSp + 2.5f).coerceIn(17f, 24f)
                typeface = Typeface.DEFAULT
                setTextColor(colorKeyText)
                gravity = Gravity.CENTER
            }
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val ch = spec.commitChar ?: spec.label
            attachKeyTouchFeedback(
                keyView = this,
                onCommit = { handleGenericCharPress(ch) },
                previewChar = { spec.label }
            )
        }
        KeyboardLayoutSpec.NumpadKeyRole.OPERATOR -> FrameLayout(this).apply {
            background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)
            contentDescription = spec.contentDescription

            val tv = TextView(this@ClipMaxImeService).apply {
                text = spec.label
                textSize = (currentLayoutGeometry.primaryTextSizeSp - 0.5f).coerceIn(15.5f, 20f)
                typeface = Typeface.DEFAULT
                setTextColor(colorModText)
                gravity = Gravity.CENTER
            }
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val ch = spec.commitChar ?: spec.label
            attachKeyTouchFeedback(
                keyView = this,
                onCommit = { handleGenericCharPress(ch) },
                previewChar = { spec.label }
            )
        }
    }

    private fun renderNumpadRows(parent: LinearLayout) {
        val rowH = getKeyRowHeight()
        val gapPx = dp(currentLayoutGeometry.interRowGapDp).toInt()
        val sideGap = (dp(currentLayoutGeometry.horizontalGapDp) / 2f).toInt().coerceAtLeast(1)
        val upperBlockH = rowH * 3 + gapPx * 2

        // Upper 3-row block: Left elongated operator rail (1.40f) | Center 3x3 digits (7.20f) | Right modifiers (1.40f)
        val upperBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, upperBlockH).apply {
                bottomMargin = gapPx
            }
        }

        val leftRail = createNumpadOperatorRail(KeyboardLayoutSpec.NumpadLayoutSpec.OPERATOR_RAIL)
        upperBlock.addView(
            leftRail,
            LinearLayout.LayoutParams(0, upperBlockH, KeyboardLayoutSpec.NumpadLayoutSpec.LEFT_RAIL_WEIGHT).apply {
                leftMargin = sideGap
                rightMargin = sideGap
            }
        )

        val centerGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, upperBlockH, KeyboardLayoutSpec.NumpadLayoutSpec.CENTER_BLOCK_WEIGHT)
        }
        val numRows = KeyboardLayoutSpec.NumpadLayoutSpec.NUMBER_ROWS
        numRows.forEachIndexed { idx, rowSpecs ->
            val row = createKeyRow(isBottomRow = (idx == numRows.lastIndex))
            rowSpecs.forEach { spec ->
                row.addView(createNumpadKey(spec), keyLp(spec.weight))
            }
            centerGrid.addView(row)
        }
        upperBlock.addView(centerGrid)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, upperBlockH, KeyboardLayoutSpec.NumpadLayoutSpec.RIGHT_COLUMN_WEIGHT).apply {
                leftMargin = sideGap
                rightMargin = sideGap
            }
        }
        val rightKeys = KeyboardLayoutSpec.NumpadLayoutSpec.RIGHT_COLUMN
        rightKeys.forEachIndexed { idx, spec ->
            val keyView = createNumpadKey(spec)
            rightCol.addView(
                keyView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH).apply {
                    bottomMargin = if (idx == rightKeys.lastIndex) 0 else gapPx
                }
            )
        }
        upperBlock.addView(rightCol)
        parent.addView(upperBlock)

        // Bottom row: matching 1.40f | 7.20f | 1.40f outer alignment with 7 controls in exact order:
        // ABC | , | !?# | 0 | = | . | Dynamic Action (↵)
        val row4 = createKeyRow(isBottomRow = true)
        row4.addView(
            createNumpadKey(KeyboardLayoutSpec.NumpadLayoutSpec.BOTTOM_LEFT),
            keyLp(KeyboardLayoutSpec.NumpadLayoutSpec.BOTTOM_LEFT.weight)
        )

        val bottomCenter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, rowH, KeyboardLayoutSpec.NumpadLayoutSpec.CENTER_BLOCK_WEIGHT)
        }
        KeyboardLayoutSpec.NumpadLayoutSpec.BOTTOM_CENTER_KEYS.forEach { spec ->
            bottomCenter.addView(createNumpadKey(spec), keyLp(spec.weight))
        }
        row4.addView(bottomCenter)

        row4.addView(
            createNumpadKey(KeyboardLayoutSpec.NumpadLayoutSpec.BOTTOM_RIGHT),
            keyLp(KeyboardLayoutSpec.NumpadLayoutSpec.BOTTOM_RIGHT.weight)
        )
        parent.addView(row4)
    }

    // 4E. NATIVE OFFLINE EMOJI PICKER (Fits fixed viewport, Recent history + 7-col grid + spaced nav)
    private fun iconTypeForEmojiTab(tabIdx: Int): IconType {
        if (tabIdx == 0) return IconType.EMOJI_RECENT
        val cat = EmojiCatalog.CATEGORIES[(tabIdx - 1).coerceIn(0, EmojiCatalog.CATEGORIES.lastIndex)]
        return when (cat.id) {
            EmojiCatalog.CategoryId.SMILEYS -> IconType.EMOJI
            EmojiCatalog.CategoryId.PEOPLE -> IconType.EMOJI_PEOPLE
            EmojiCatalog.CategoryId.NATURE -> IconType.EMOJI_NATURE
            EmojiCatalog.CategoryId.FOOD -> IconType.EMOJI_FOOD
            EmojiCatalog.CategoryId.ACTIVITIES -> IconType.EMOJI_ACTIVITIES
            EmojiCatalog.CategoryId.TRAVEL -> IconType.EMOJI_TRAVEL
            EmojiCatalog.CategoryId.OBJECTS -> IconType.EMOJI_OBJECTS
            EmojiCatalog.CategoryId.SYMBOLS -> IconType.EMOJI_SYMBOLS
        }
    }

    private fun labelForEmojiTab(tabIdx: Int): String {
        if (tabIdx == 0) return "Recent"
        return EmojiCatalog.CATEGORIES[(tabIdx - 1).coerceIn(0, EmojiCatalog.CATEGORIES.lastIndex)].label
    }

    private fun resolveActiveEmojiTabIndex(): Int {
        if (selectedEmojiCategoryIdx in 0..EmojiCatalog.CATEGORIES.size) {
            return selectedEmojiCategoryIdx
        }
        selectedEmojiCategoryIdx = if (recentEmojiHistory.items().isNotEmpty()) 0 else 1
        return selectedEmojiCategoryIdx
    }

    private fun persistRecentEmojis() {
        emojiPreferences.edit()
            .putString(EmojiInterfaceSpec.PREF_RECENT_EMOJIS, recentEmojiHistory.encode())
            .apply()
    }

    private fun dismissEmojiRemoveOverlay() {
        val root = emojiPickerRoot
        val overlay = emojiRemoveOverlay
        emojiRemoveOverlay = null
        if (root != null && overlay != null) {
            root.removeView(overlay)
        }
    }

    private fun showRecentEmojiRemoveOverlay(emoji: String) {
        val root = emojiPickerRoot ?: return
        dismissEmojiRemoveOverlay()

        val overlayHost = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(0x66000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dismissEmojiRemoveOverlay() }
        }

        val cardBg = if (isClassicTheme) colorKeyBg else colorChipBg
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedDrawable(cardBg, dp(14f), colorKeyBorder)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(8f)
            }

            val previewTv = TextView(this@ClipMaxImeService).apply {
                text = emoji
                textSize = 22f
                setPadding(0, 0, dp(10), 0)
            }
            addView(previewTv)

            val removeBtn = TextView(this@ClipMaxImeService).apply {
                text = "Remove from recents"
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(colorDangerText)
                isClickable = true
                isFocusable = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedRipple(0, dp(8f), 0)
                setIcon(IconDrawable(IconType.DELETE, colorDangerText, 15f), 8f)
                setOnClickListener {
                    if (recentEmojiHistory.remove(emoji)) {
                        persistRecentEmojis()
                    }
                    dismissEmojiRemoveOverlay()
                    populateEmojiGrid()
                }
            }
            addView(removeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val cancelBtn = TextView(this@ClipMaxImeService).apply {
                text = "Cancel"
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(colorModText)
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(8), dp(8), dp(8))
                background = roundedRipple(0, dp(8f), 0)
                setOnClickListener { dismissEmojiRemoveOverlay() }
            }
            addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            })
        }

        overlayHost.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        emojiRemoveOverlay = overlayHost
        root.addView(overlayHost)
    }

    private fun buildEmojiPickerPanel(): View = FrameLayout(this).apply {
        emojiPickerRoot = this
        setBackgroundColor(colorBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        val column = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val headerH = dp(
            if (isLandscape) EmojiInterfaceSpec.NAV_HEADER_HEIGHT_LANDSCAPE_DP
            else EmojiInterfaceSpec.NAV_HEADER_HEIGHT_PORTRAIT_DP
        ).toInt()
        val navCtrlH = (headerH - dp(8)).coerceAtLeast(dp(28))
        val navHeaderBg = if (isClassicTheme) colorAltDeepBg else colorChipBg

        // Category & Navigation Bar (ABC | Spaced Category Icons | Backspace)
        val header = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(navHeaderBg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerH)
        }

        val abcBtn = TextView(this@ClipMaxImeService).apply {
            text = "ABC"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            minWidth = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(colorKeyText)
            background = roundedRipple(colorModBg, dp(9f), colorKeyBorder)
            contentDescription = "Return to keyboard"
            setOnClickListener {
                dismissPasteUndoCapsule()
                dismissEmojiRemoveOverlay()
                isEmojiPickerOpen = false
                modeController.toAlpha()
                renderFullImeView()
            }
        }
        header.addView(abcBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, navCtrlH).apply {
            rightMargin = dp(8)
        })

        val tabsScroll = HorizontalScrollView(this@ClipMaxImeService).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val tabsRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        emojiTabViews.clear()
        val tabW = dp(EmojiInterfaceSpec.TAB_WIDTH_DP).toInt()
        val tabGap = dp(EmojiInterfaceSpec.TAB_GAP_DP).toInt()
        val totalTabs = 1 + EmojiCatalog.CATEGORIES.size
        for (tabIdx in 0 until totalTabs) {
            val tab = FrameLayout(this@ClipMaxImeService).apply {
                contentDescription = labelForEmojiTab(tabIdx)
                isClickable = true
                isFocusable = true
                val iv = createIconView(
                    IconDrawable(iconTypeForEmojiTab(tabIdx), colorModText, EmojiInterfaceSpec.TAB_ICON_SIZE_DP)
                )
                tag = iv
                addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                setOnClickListener {
                    dismissEmojiRemoveOverlay()
                    selectedEmojiCategoryIdx = tabIdx
                    updateEmojiCategoryTabsUI()
                    populateEmojiGrid()
                    emojiScrollView?.scrollTo(0, 0)
                }
            }
            emojiTabViews.add(tabIdx to tab)
            tabsRow.addView(tab, LinearLayout.LayoutParams(tabW, navCtrlH).apply {
                if (tabIdx < totalTabs - 1) rightMargin = tabGap
            })
        }
        tabsScroll.addView(tabsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(tabsScroll)

        val backspaceBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(colorModBg, dp(9f), colorKeyBorder)
            contentDescription = "Delete"
            isClickable = true
            isFocusable = true
            val iv = createIconView(IconDrawable(IconType.BACKSPACE, colorKeyText, EmojiInterfaceSpec.BACKSPACE_ICON_SIZE_DP))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setupBackspaceRepeat(this)
        }
        header.addView(backspaceBtn, LinearLayout.LayoutParams(dp(EmojiInterfaceSpec.BACKSPACE_WIDTH_DP).toInt(), navCtrlH).apply {
            leftMargin = dp(8)
        })

        column.addView(header)

        val divider = View(this@ClipMaxImeService).apply {
            setBackgroundColor(colorKeyBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        column.addView(divider)

        val scroll = android.widget.ScrollView(this@ClipMaxImeService).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        emojiScrollView = scroll

        val grid = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        emojiGridContainer = grid
        scroll.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(scroll)

        addView(column)

        updateEmojiCategoryTabsUI()
        populateEmojiGrid()
    }

    private fun updateEmojiCategoryTabsUI() {
        val activeIdx = resolveActiveEmojiTabIndex()
        for ((tabIdx, tab) in emojiTabViews) {
            val active = (tabIdx == activeIdx)
            val bg = if (active) colorActiveTabBg else 0
            val fg = if (active) colorActiveTabText else colorModText
            val stroke = if (active) (if (isClassicTheme) colorAccent else colorChipBorder) else 0
            tab.background = roundedRipple(bg, dp(9f), stroke)
            val iv = tab.tag as? ImageView
            iv?.setImageDrawable(IconDrawable(iconTypeForEmojiTab(tabIdx), fg, EmojiInterfaceSpec.TAB_ICON_SIZE_DP))
        }
    }

    private fun populateEmojiGrid() {
        val grid = emojiGridContainer ?: return
        grid.removeAllViews()
        val tabIdx = resolveActiveEmojiTabIndex()
        val isRecentTab = (tabIdx == 0)
        val emojis = if (isRecentTab) {
            recentEmojiHistory.items()
        } else {
            EmojiCatalog.CATEGORIES[(tabIdx - 1).coerceIn(0, EmojiCatalog.CATEGORIES.lastIndex)].emojis
        }

        if (isRecentTab && emojis.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "No recent emojis yet.\nTap any emoji to keep it here."
                textSize = 12.5f
                setTextColor(colorHintText)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(36), dp(16), dp(24))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cols = if (isLandscape) EmojiInterfaceSpec.LANDSCAPE_COLUMNS else EmojiInterfaceSpec.PORTRAIT_COLUMNS
        val cellH = dp(
            if (isLandscape) EmojiInterfaceSpec.CELL_HEIGHT_LANDSCAPE_DP
            else EmojiInterfaceSpec.CELL_HEIGHT_PORTRAIT_DP
        ).toInt()
        val rowGap = dp(EmojiInterfaceSpec.CELL_ROW_GAP_DP).toInt()

        var i = 0
        while (i < emojis.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH).apply {
                    bottomMargin = rowGap
                }
            }
            for (c in 0 until cols) {
                val idx = i + c
                if (idx < emojis.size) {
                    val emoji = emojis[idx]
                    val cell = TextView(this).apply {
                        text = emoji
                        textSize = EmojiInterfaceSpec.CELL_TEXT_SIZE_SP
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        isClickable = true
                        isFocusable = true
                        background = roundedRipple(0x00000000, dp(10f), 0)
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            handleEmojiPress(emoji, fromRecentTab = isRecentTab)
                        }
                        if (isRecentTab) {
                            setOnLongClickListener {
                                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                showRecentEmojiRemoveOverlay(emoji)
                                true
                            }
                        }
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
            }
            grid.addView(row)
            i += cols
        }
    }

    private fun handleEmojiPress(emoji: String, fromRecentTab: Boolean = false) {
        dismissPasteUndoCapsule()
        dismissEmojiRemoveOverlay()
        shiftStateMachine.onNonLetterInput()
        if (isFilterMode) {
            keyboardSearchQuery += emoji
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection ?: return
            insertionHistory.invalidateImmediateBackspaceRevert()
            lastRevertOriginalWord = null
            clearTypingSuggestionsView()
            conn.commitText(emoji, 1)
            updateInsertionHistoryButtons()
        }
        recentEmojiHistory.record(emoji)
        persistRecentEmojis()
        if (fromRecentTab && resolveActiveEmojiTabIndex() == 0) {
            populateEmojiGrid()
        }
    }

    // =========================================================================
    // KEY VIEW BUILDERS, ACTIONS & FEEDBACK
    // =========================================================================
    private fun createKeyRow(isBottomRow: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val gapPx = if (isBottomRow) 0 else dp(currentLayoutGeometry.interRowGapDp).toInt()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, getKeyRowHeight()).apply {
            bottomMargin = gapPx
        }
    }

    private fun showKeyPreview(keyView: View, char: String) {
        val root = imeRootFrame ?: return
        val popup = keyPopupView ?: return
        val tv = keyPopupText ?: return

        tv.text = char
        val keyLoc = IntArray(2)
        val rootLoc = IntArray(2)
        keyView.getLocationOnScreen(keyLoc)
        root.getLocationOnScreen(rootLoc)

        val relLeft = keyLoc[0] - rootLoc[0]
        val relTop = keyLoc[1] - rootLoc[1]

        val targetWidth = maxOf(keyView.width, dp(44))
        val targetHeight = dp(52)

        val lp = popup.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
        lp.width = targetWidth
        lp.height = targetHeight
        lp.leftMargin = 0
        lp.topMargin = 0
        popup.layoutParams = lp

        val minX = dp(2).toFloat()
        val maxX = (root.width - targetWidth - dp(2)).toFloat().coerceAtLeast(minX)
        popup.translationX = (relLeft + (keyView.width - targetWidth) / 2).toFloat().coerceIn(minX, maxX)
        popup.translationY = (relTop - targetHeight + dp(2)).toFloat().coerceAtLeast(0f)
        popup.visibility = View.VISIBLE
        popup.bringToFront()
    }

    private fun hideKeyPreview() {
        keyPopupView?.visibility = View.GONE
    }

    private fun attachKeyTouchFeedback(keyView: View, onCommit: () -> Unit, previewChar: (() -> String)? = null) {
        keyView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (previewChar != null) {
                        showKeyPreview(v, previewChar())
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    val inside = x >= -dp(12) && x <= v.width + dp(12) && y >= -dp(24) && y <= v.height + dp(12)
                    if (!inside) {
                        v.isPressed = false
                        hideKeyPreview()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasPressed = v.isPressed
                    v.isPressed = false
                    hideKeyPreview()
                    if (wasPressed) {
                        onCommit()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    hideKeyPreview()
                    true
                }
                else -> false
            }
        }
    }

    private fun attachDualFunctionTouchFeedback(
        keyView: View,
        primaryChar: String,
        longPressChar: String
    ) {
        val controller = KeyboardLayoutSpec.LongPressKeyController()
        val thresholdMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(320L, 420L)
        val longPressTask = Runnable {
            if (controller.onLongPressTimeout()) {
                keyView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showKeyPreview(keyView, longPressChar)
                handleGenericCharPress(longPressChar)
            }
        }

        keyView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(longPressTask)
                    controller.onDown()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val displayChar = if (shiftState == 0) {
                        primaryChar.lowercase(Locale.ROOT)
                    } else {
                        primaryChar.uppercase(Locale.ROOT)
                    }
                    showKeyPreview(v, displayChar)
                    main.postDelayed(longPressTask, thresholdMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    val inside = x >= -dp(12) && x <= v.width + dp(12) && y >= -dp(24) && y <= v.height + dp(12)
                    if (!inside && controller.onMove(insideBounds = false)) {
                        main.removeCallbacks(longPressTask)
                        v.isPressed = false
                        hideKeyPreview()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPressTask)
                    v.isPressed = false
                    hideKeyPreview()
                    if (controller.onUp()) {
                        handleLetterPress(primaryChar)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPressTask)
                    controller.onCancel()
                    v.isPressed = false
                    hideKeyPreview()
                    true
                }
                else -> false
            }
        }
    }

    private fun createAlphaKeyWithHint(char: String, hint: String): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)

        // Primary letter (visually dominant, optically padded slightly below top-right digit legend)
        val tv = TextView(this@ClipMaxImeService).apply {
            text = if (shiftState == 0) char.lowercase(Locale.ROOT) else char.uppercase(Locale.ROOT)
            textSize = currentLayoutGeometry.primaryTextSizeSp
            typeface = Typeface.DEFAULT
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        letterKeyViews.add(tv to char)
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Discreet superscript number legend pinned top-right (visible in normal and long-press states)
        val hintTv = TextView(this@ClipMaxImeService).apply {
            text = hint
            textSize = currentLayoutGeometry.numberLegendSizeSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorHintText)
            gravity = Gravity.TOP or Gravity.END
            setPadding(0, dp(2), dp(5), 0)
        }
        addView(hintTv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachDualFunctionTouchFeedback(
            keyView = this,
            primaryChar = char,
            longPressChar = hint
        )
    }

    private fun createCommaEmojiKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = "Comma, hold for emoji"

        val tv = TextView(this@ClipMaxImeService).apply {
            text = ","
            textSize = (currentLayoutGeometry.primaryTextSizeSp - 2f).coerceAtLeast(15f)
            typeface = Typeface.DEFAULT
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val emojiHint = createIconView(IconDrawable(IconType.EMOJI, colorHintText, 9.5f))
        addView(
            emojiHint,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(3)
                rightMargin = dp(4)
            }
        )

        val controller = KeyboardLayoutSpec.LongPressKeyController()
        val thresholdMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(320L, 420L)
        val longPressTask = Runnable {
            if (controller.onLongPressTimeout()) {
                this.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                hideKeyPreview()
                dismissPasteUndoCapsule()
                shiftStateMachine.resetDoubleTapTimer()
                isEmojiPickerOpen = true
                isClipboardPanelOpen = false
                isSettingsOpen = false
                renderFullImeView()
            }
        }

        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(longPressTask)
                    controller.onDown()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showKeyPreview(v, ",")
                    main.postDelayed(longPressTask, thresholdMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    val inside = x >= -dp(12) && x <= v.width + dp(12) && y >= -dp(24) && y <= v.height + dp(12)
                    if (!inside && controller.onMove(insideBounds = false)) {
                        main.removeCallbacks(longPressTask)
                        v.isPressed = false
                        hideKeyPreview()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPressTask)
                    v.isPressed = false
                    hideKeyPreview()
                    if (controller.onUp()) {
                        handleGenericCharPress(",")
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPressTask)
                    controller.onCancel()
                    v.isPressed = false
                    hideKeyPreview()
                    true
                }
                else -> false
            }
        }
    }

    private fun createStandardKey(char: String): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)

        val isLetter = char.length == 1 && char[0].isLetter()
        val tv = TextView(this@ClipMaxImeService).apply {
            text = if (isLetter && shiftState == 0) char.lowercase(Locale.ROOT) else char
            textSize = if (isLetter) currentLayoutGeometry.primaryTextSizeSp else (currentLayoutGeometry.primaryTextSizeSp - 2f).coerceAtLeast(15f)
            typeface = Typeface.DEFAULT
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            if (isLetter) letterKeyViews.add(this to char)
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(
            keyView = this,
            onCommit = {
                if (isLetter) {
                    handleLetterPress(char)
                } else {
                    handleGenericCharPress(char)
                }
            },
            previewChar = {
                if (isLetter) {
                    if (shiftState == 0) char.lowercase(Locale.ROOT) else char.uppercase(Locale.ROOT)
                } else {
                    char
                }
            }
        )
    }

    private fun createModifierKey(title: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorModText)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(this, onClick)
    }

    private fun createShiftKey(): View = FrameLayout(this).apply {
        val (bgCol, fgCol, iconType) = when (shiftState) {
            2 -> Triple(colorAccent, colorAccentText, IconType.CAPS_LOCK)
            1 -> Triple(colorActiveTabBg, colorActiveTabText, IconType.SHIFT_ACTIVE)
            else -> Triple(colorModBg, colorModText, IconType.SHIFT)
        }
        val strokeCol = if (shiftState == 1) colorAccent else colorKeyBorder
        background = roundedRipple(bgCol, keyCornerRadiusPx(), strokeCol)

        val iv = createIconView(IconDrawable(iconType, fgCol, 18f))
        shiftImageView = iv
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        attachKeyTouchFeedback(this, { handleShiftPress() })
    }

    private fun createBackspaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)

        val iv = createIconView(IconDrawable(IconType.BACKSPACE, colorModText, 18f))
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setupBackspaceRepeat(this)
    }

    private fun createGlobeKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = "Switch input method"

        val iv = createIconView(IconDrawable(IconType.IME_SWITCH, colorModText, 16f))
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        attachKeyTouchFeedback(this, { showSystemImePicker() })
    }

    private fun createSpaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = "English"
            textSize = 13f
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(this, { handleSpacePress() })
    }

    private fun resolveCurrentActionKey(): InputFieldPolicy.ActionKeyResolution {
        val imeOpts = currentInputEditorInfo?.imeOptions ?: 0
        return InputFieldPolicy.resolveActionKey(imeOpts, isFilterMode = isFilterMode)
    }

    private fun iconTypeForAction(kind: InputFieldPolicy.ActionKeyKind): IconType = when (kind) {
        InputFieldPolicy.ActionKeyKind.SEARCH -> IconType.SEARCH
        InputFieldPolicy.ActionKeyKind.SEND -> IconType.SEND
        InputFieldPolicy.ActionKeyKind.GO -> IconType.GO
        InputFieldPolicy.ActionKeyKind.NEXT -> IconType.NEXT
        InputFieldPolicy.ActionKeyKind.DONE -> IconType.DONE
        InputFieldPolicy.ActionKeyKind.ENTER -> IconType.ENTER
    }

    private fun contentDescriptionForAction(kind: InputFieldPolicy.ActionKeyKind): String = when (kind) {
        InputFieldPolicy.ActionKeyKind.SEARCH -> "Search"
        InputFieldPolicy.ActionKeyKind.SEND -> "Send"
        InputFieldPolicy.ActionKeyKind.GO -> "Go"
        InputFieldPolicy.ActionKeyKind.NEXT -> "Next"
        InputFieldPolicy.ActionKeyKind.DONE -> "Done"
        InputFieldPolicy.ActionKeyKind.ENTER -> "Enter"
    }

    private fun updateEnterKeyUI() {
        val btn = enterKeyView ?: return
        val iv = enterKeyImageView ?: return
        val resolution = resolveCurrentActionKey()
        val fgCol = if (isClassicTheme) colorAccentText else colorKeyText
        iv.setImageDrawable(IconDrawable(iconTypeForAction(resolution.kind), fgCol, 18f))
        btn.contentDescription = contentDescriptionForAction(resolution.kind)
    }

    private fun createEnterKey(): View = FrameLayout(this).apply {
        val bgCol = if (isClassicTheme) colorAccent else colorKeyBg
        val fgCol = if (isClassicTheme) colorAccentText else colorKeyText
        val resolution = resolveCurrentActionKey()
        background = roundedRipple(bgCol, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = contentDescriptionForAction(resolution.kind)

        val iv = createIconView(IconDrawable(iconTypeForAction(resolution.kind), fgCol, 18f))
        enterKeyView = this
        enterKeyImageView = iv
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        attachKeyTouchFeedback(this, { handleEnterPress() })
    }

    // =========================================================================
    // INPUT CONNECTION HANDLING
    // =========================================================================
    private fun handleLetterPress(char: String) {
        dismissPasteUndoCapsule()
        val letter = if (shiftState == 0) char.lowercase(Locale.ROOT) else char.uppercase(Locale.ROOT)
        if (isFilterMode) {
            keyboardSearchQuery += letter
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection
            if (conn != null) {
                insertionHistory.invalidateImmediateBackspaceRevert()
                lastRevertOriginalWord = null
                conn.commitText(letter, 1)
                updateInsertionHistoryButtons()
                refreshTypingSuggestionsAsync()
            }
        }
        val prevShift = shiftState
        shiftStateMachine.onLetterCommitted(autoCapMode)
        if (shiftState != prevShift) {
            updateShiftUI()
        }
    }

    private fun handleGenericCharPress(char: String) {
        dismissPasteUndoCapsule()
        shiftStateMachine.onNonLetterInput()
        if (isFilterMode) {
            keyboardSearchQuery += char
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection ?: return
            if (isWordBoundaryDelimiter(char)) {
                commitWordBoundaryWithIntelligence(conn, char)
            } else {
                val beforeBefore = runCatching { conn.getTextBeforeCursor(48, 0)?.toString() }.getOrNull()
                insertionHistory.invalidateImmediateBackspaceRevert()
                lastRevertOriginalWord = null
                conn.commitText(char, 1)
                updateInsertionHistoryButtons()
                if (char == "'") {
                    refreshTypingSuggestionsAsync()
                } else {
                    clearTypingSuggestionsView()
                }
                syncAutoCapitalization(
                    preserveManualShift = false,
                    predictedBeforeText = beforeBefore?.plus(char)
                )
            }
        }
    }

    private fun handleSpacePress() {
        dismissPasteUndoCapsule()
        shiftStateMachine.onNonLetterInput()
        if (isFilterMode) {
            keyboardSearchQuery += " "
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection ?: return
            commitWordBoundaryWithIntelligence(conn, " ")
        }
    }

    private fun handleBackspace() {
        dismissPasteUndoCapsule()
        shiftStateMachine.onNonLetterInput()
        if (isFilterMode) {
            if (keyboardSearchQuery.isNotEmpty()) {
                keyboardSearchQuery = EmojiInterfaceSpec.dropLastGrapheme(keyboardSearchQuery)
                updateSearchUI()
                runKeyboardSearch()
            }
        } else {
            val conn = currentInputConnection ?: return
            val gateway = buildEditorGateway(conn)
            if (gateway != null && !isPasswordTarget) {
                val reverted = insertionHistory.tryImmediateBackspaceRevertAutocorrect(gateway)
                if (reverted != null) {
                    if (fieldCapabilities.allowSessionLearning) {
                        suppressedAutocorrectWords.add(reverted.lowercase(Locale.ROOT))
                    }
                    lastRevertOriginalWord = null
                    updateInsertionHistoryButtons()
                    refreshTypingSuggestionsAsync()
                    syncAutoCapitalization(preserveManualShift = false)
                    return
                }
            }

            insertionHistory.invalidateImmediateBackspaceRevert()
            lastRevertOriginalWord = null
            val selected = runCatching { conn.getSelectedText(0) }.getOrNull()
            val predictedAfterDelete = if (selected.isNullOrEmpty()) {
                val preDelete = runCatching { conn.getTextBeforeCursor(64, 0)?.toString() }.getOrNull()
                val deleteCount = if (!preDelete.isNullOrEmpty()) {
                    EmojiInterfaceSpec.lastGraphemeUtf16Length(preDelete)
                } else {
                    1
                }
                conn.deleteSurroundingText(deleteCount, 0)
                preDelete?.dropLast(deleteCount)
            } else {
                conn.commitText("", 1)
                null
            }
            updateInsertionHistoryButtons()
            refreshTypingSuggestionsAsync()
            syncAutoCapitalization(
                preserveManualShift = false,
                predictedBeforeText = predictedAfterDelete
            )
        }
    }

    private fun handleEnterPress() {
        dismissPasteUndoCapsule()
        val resolution = resolveCurrentActionKey()
        shiftStateMachine.onNonLetterInput()
        if (resolution.isFilterDismiss || isFilterMode) {
            isFilterMode = false
            keyboardSearchQuery = ""
            keyboardSearchResults = emptyList()
            modeController.exitSearchClips()
            renderFullImeView()
        } else {
            val conn = currentInputConnection ?: return
            val actionId = resolution.editorActionId
            if (actionId != null) {
                insertionHistory.invalidateImmediateBackspaceRevert()
                clearTypingSuggestionsView()
                conn.performEditorAction(actionId)
                syncAutoCapitalization(preserveManualShift = false)
            } else {
                commitWordBoundaryWithIntelligence(conn, "\n")
            }
        }
    }

    private fun handleShiftPress() {
        val now = SystemClock.elapsedRealtime()
        val timeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        shiftStateMachine.onShiftPress(now, timeout)
        updateShiftUI()
    }

    private fun updateShiftUI() {
        for ((tv, baseChar) in letterKeyViews) {
            tv.text = if (shiftState == 0) baseChar.lowercase(Locale.ROOT) else baseChar.uppercase(Locale.ROOT)
        }
        val btn = shiftButton ?: return
        val (bgCol, fgCol, iconType) = when (shiftState) {
            2 -> Triple(colorAccent, colorAccentText, IconType.CAPS_LOCK)
            1 -> Triple(colorActiveTabBg, colorActiveTabText, IconType.SHIFT_ACTIVE)
            else -> Triple(colorModBg, colorModText, IconType.SHIFT)
        }
        val strokeCol = if (shiftState == 1) colorAccent else colorKeyBorder
        btn.background = roundedRipple(bgCol, keyCornerRadiusPx(), strokeCol)
        shiftImageView?.setImageDrawable(IconDrawable(iconType, fgCol, 18f))
    }

    private fun updateSearchUI() {
        searchInputText?.text = if (keyboardSearchQuery.isEmpty()) "Type below to search clips…" else keyboardSearchQuery
        searchInputText?.setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
        searchClearBtn?.visibility = if (keyboardSearchQuery.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setupBackspaceRepeat(view: View) {
        val repeatRunnable = object : Runnable {
            override fun run() {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handleBackspace()
                main.postDelayed(this, 50)
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handleBackspace()
                    main.postDelayed(repeatRunnable, 350)
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(repeatRunnable)
                    view.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    // =========================================================================
    // PERSISTENCE & CHUNKED INSERTION
    // =========================================================================
    fun reloadItems() {
        val request = ++listRequest
        io.execute {
            CapturePreferences.cleanupExpired(store, capturePreferences)
            val found = runCatching { store.listItems() }.getOrElse { emptyList() }
            val prebuiltSnippets = HashMap<String, String>(minOf(found.size, 64))
            for (item in found.take(64)) {
                val snip = getRawSnippetPrefix(item, 180)
                if (snip.isNotEmpty()) {
                    prebuiltSnippets[item.id] = snip
                }
            }
            main.post {
                if (request != listRequest || imeRootFrame == null) return@post
                snippetCache = prebuiltSnippets
                items = found.sortedWith(
                    compareByDescending<ClipItem> { it.pinned }
                        .thenByDescending { it.createdAt }
                        .thenBy { it.title }
                )
                renderClipChips()
                if (isFilterMode) {
                    if (keyboardSearchQuery.isNotEmpty()) {
                        runKeyboardSearch()
                    } else {
                        keyboardSearchResults = items
                        renderSearchResults()
                    }
                } else if (isClipboardPanelOpen) {
                    if (contextMenuHost != null) {
                        pendingRenderAfterContextMenu = true
                    } else if (clipboardCardsContainer != null) {
                        refreshClipboardPanelInPlace()
                    } else {
                        renderFullImeView()
                    }
                }
            }
        }
    }

    private fun runKeyboardSearch() {
        val query = keyboardSearchQuery.trim()
        val request = ++keyboardSearchRequest
        io.execute {
            val found = if (query.isEmpty()) {
                items
            } else {
                runCatching { store.search(query) }.getOrElse { emptyList() }
            }.sortedWith(
                compareByDescending<ClipItem> { it.pinned }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.title }
            )
            main.post {
                if (request != keyboardSearchRequest || imeRootFrame == null) return@post
                keyboardSearchResults = found
                renderClipChips()
                renderSearchResults()
                if (isClipboardPanelOpen) {
                    if (contextMenuHost != null) {
                        pendingRenderAfterContextMenu = true
                    } else if (clipboardCardsContainer != null) {
                        refreshClipboardPanelInPlace()
                    } else {
                        renderFullImeView()
                    }
                }
            }
        }
    }

    private data class InsertionSource(val reader: Reader, val characterCount: Int)

    private fun insertItem(item: ClipItem) {
        beginInsertion(item.title, "Inserting ${item.title}…") {
            InsertionSource(store.openTextReader(item.id), item.characterCount)
        }
    }

    private fun beginInsertion(title: String, initialStatus: String, openSource: () -> InsertionSource) {
        if (isPasswordTarget) {
            setStatus("Saved items are unavailable in password fields.")
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            setStatus("No active text field in destination app.")
            return
        }
        if (isInserting) cancelInsertion(userInitiated = false)
        generation++
        val token = generation
        val startTarget = currentEditorTarget
        val gatewayBefore = buildEditorGateway(connection)
        val hasSelectionBefore = gatewayBefore?.hasActiveSelection() == true
        val beforeAnchor = if (!hasSelectionBefore) {
            gatewayBefore?.textBeforeCursor(InsertionHistoryManager.ANCHOR_LENGTH).orEmpty()
        } else ""
        val afterAnchor = if (!hasSelectionBefore) {
            gatewayBefore?.textAfterCursor(InsertionHistoryManager.ANCHOR_LENGTH).orEmpty()
        } else ""

        dismissPasteUndoCapsule()
        isInserting = true
        insertedCodePoints = 0
        lastReportedCodePoints = 0
        activeItemCodePoints = 0
        setStatus(initialStatus)

        insertTask = inserter.submit {
            try {
                val source = openSource()
                activeItemCodePoints = source.characterCount
                val trackedBuffer = if (!hasSelectionBefore && source.characterCount <= InsertionHistoryManager.MAX_TRACKED_INSERTION_CHARS) {
                    StringBuilder(minOf(source.characterCount + 16, InsertionHistoryManager.MAX_TRACKED_INSERTION_CHARS + 16))
                } else {
                    null
                }
                main.post {
                    if (generation == token) setStatus("Inserting $title (${activeItemCodePoints.grouped()} chars)…")
                }
                val complete = source.reader.use { reader ->
                    TextChunker.forEachChunk(
                        reader = reader,
                        isCancelled = { generation != token || Thread.currentThread().isInterrupted }
                    ) { chunk ->
                        val ok = deliverChunk(connection, token, chunk)
                        if (ok && trackedBuffer != null) {
                            if (trackedBuffer.length + chunk.length <= InsertionHistoryManager.MAX_TRACKED_INSERTION_CHARS) {
                                trackedBuffer.append(chunk)
                            }
                        }
                        ok
                    }
                }
                main.post {
                    if (generation == token) {
                        isInserting = false
                        val insertedText = trackedBuffer?.toString()
                        if (complete && !isPasswordTarget && !insertedText.isNullOrEmpty() &&
                            insertedText.length <= InsertionHistoryManager.MAX_TRACKED_INSERTION_CHARS &&
                            currentEditorTarget.isCompatibleWith(startTarget)
                        ) {
                            pasteHistoryVerifyToken++
                            val recorded = insertionHistory.recordClipInsertion(
                                target = startTarget,
                                insertedText = insertedText,
                                beforeAnchor = beforeAnchor,
                                afterAnchor = afterAnchor
                            )
                            if (recorded) {
                                pasteCapsuleController.onPasteSucceeded(SystemClock.elapsedRealtime())
                                if (toolbarState != 0) {
                                    switchToolbarState(0, dismissCapsule = false)
                                }
                                clearTypingSuggestionsView()
                                updatePasteUndoCapsuleUI()
                                // Compose / WebView hosts often apply commitText asynchronously on the next frame
                                // or trigger restartInput; re-sync capsule state once host context settles.
                                main.postDelayed({
                                    if (!serviceDestroyed && inputViewActive && currentEditorTarget.isCompatibleWith(startTarget)) {
                                        updatePasteUndoCapsuleUI()
                                    }
                                }, 80L)
                                main.postDelayed({
                                    if (!serviceDestroyed && inputViewActive && currentEditorTarget.isCompatibleWith(startTarget)) {
                                        updatePasteUndoCapsuleUI()
                                    }
                                }, 220L)
                            } else {
                                pasteCapsuleController.onPasteFailed()
                                updatePasteUndoCapsuleUI()
                            }
                        } else {
                            pasteCapsuleController.onPasteFailed()
                            updatePasteUndoCapsuleUI()
                        }
                        if (complete) {
                            shiftStateMachine.onNonLetterInput()
                            syncAutoCapitalization(preserveManualShift = false)
                        }
                        setStatus(if (complete) "Inserted ${insertedCodePoints.grouped()} chars." else "Insertion paused.")
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                main.post {
                    if (generation == token) {
                        isInserting = false
                        pasteCapsuleController.onPasteFailed()
                        updatePasteUndoCapsuleUI()
                        setStatus("Insertion ended: ${failure.message ?: "destination closed"}")
                    }
                }
            }
        }
    }

    private fun deliverChunk(connection: android.view.inputmethod.InputConnection, token: Int, chunk: String): Boolean {
        val latch = CountDownLatch(1)
        var accepted = false
        var failure: Throwable? = null
        val points = chunk.codePointCount(0, chunk.length)
        main.post {
            try {
                if (generation == token && isInserting) {
                    accepted = connection.commitText(chunk, 1)
                    if (!accepted) {
                        failure = IOException("Destination app rejected chunk")
                    } else {
                        insertedCodePoints += points
                        if (insertedCodePoints - lastReportedCodePoints >= 16_384 || insertedCodePoints >= activeItemCodePoints) {
                            lastReportedCodePoints = insertedCodePoints
                            setStatus("Inserted ${insertedCodePoints.grouped()} / ${activeItemCodePoints.grouped()} chars…")
                        }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(60, TimeUnit.SECONDS)) throw IOException("Timed out waiting for destination app")
        if (generation != token) return false
        failure?.let { throw IOException(it.message ?: "Destination app rejected text", it) }
        return accepted
    }

    private fun cancelInsertion(userInitiated: Boolean) {
        if (!isInserting) return
        generation++
        insertTask?.cancel(true)
        insertTask = null
        isInserting = false
        setStatus(if (userInitiated) "Insertion cancelled." else "Insertion stopped.")
    }

    private fun setStatus(msg: String) {
        statusLabel?.text = msg
        val targetVis = if (isInserting || isPasswordTarget) View.VISIBLE else View.GONE
        val banner = statusBannerView
        val visChanged = banner != null && banner.visibility != targetVis
        banner?.visibility = targetVis
        statusCancelBtn?.visibility = if (isInserting) View.VISIBLE else View.GONE
        if (visChanged && !isSettingsOpen && !isClipboardPanelOpen && keyboardRowsContainer != null) {
            renderKeyboardRows()
        }
    }

    private fun getRawSnippetPrefix(item: ClipItem, maxChars: Int = 180): String {
        return try {
            store.openTextReader(item.id).use { reader ->
                val buf = CharArray(maxChars)
                val read = reader.read(buf, 0, maxChars)
                if (read > 0) String(buf, 0, read) else ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun getSnippet(item: ClipItem, maxChars: Int = 40): String {
        return ClipPreviewFormatter.normalizePreviewText(getRawSnippetPrefix(item, maxChars), maxChars)
    }

    fun showSystemImePicker() {
        main.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    // =========================================================================
    // CLIPBOARD AUTO-CAPTURE & LIFECYCLE
    // =========================================================================
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        dismissContextMenu(animate = false)
        generation++
        suggestionGeneration.incrementAndGet()
        insertTask?.cancel(true)
        insertTask = null
        isInserting = false
        fieldCapabilities = InputFieldPolicy.evaluate(attribute.inputType, attribute.imeOptions)
        isPasswordTarget = fieldCapabilities.isPasswordOrPin || isSensitiveField(attribute.inputType)
        if (!restarting) {
            isEmojiPickerOpen = false
        }
        if (isPasswordTarget) {
            isFilterMode = false
            isClipboardPanelOpen = false
            isEmojiPickerOpen = false
            pinnedOnly = false
        }
        modeController.onFieldFocused(fieldCapabilities, restarting)
        autoCapMode = AutoCapitalizationPolicy.resolveCapMode(attribute.inputType, isFilterMode)
        shiftStateMachine.onInputSessionReset()
        val pkgChanged = attribute.packageName != lastEditorPackage
        val fieldChanged = attribute.fieldId != 0 && lastEditorFieldId != 0 && attribute.fieldId != lastEditorFieldId
        if (!restarting || pkgChanged || fieldChanged) {
            editorSessionId++
            dismissPasteUndoCapsule()
        }
        lastEditorPackage = attribute.packageName
        lastEditorFieldId = attribute.fieldId
        pasteHistoryVerifyToken++
        currentEditorTarget = InsertionHistoryManager.EditorTarget(
            packageName = attribute.packageName,
            fieldId = attribute.fieldId,
            inputType = attribute.inputType,
            sessionId = editorSessionId
        )
        insertionHistory.clearForTargetChangeIfNeeded(currentEditorTarget)
        suppressedAutocorrectWords.clear()
        currentWordAnalysis = null
        activeComposingWord = ""
        currentNextWordSuggestions = emptyList()
        lastRevertOriginalWord = null
        toolbarStateACenterMode = 0

        inputSessionActive = true
        inputViewActive = false
        updateCaptureMonitoring()
        if (isPasswordTarget) {
            setStatus("Password field: Saved clips withheld for privacy.")
        } else {
            updateCaptureStatus()
        }
        pendingSelectionCapture?.let(main::removeCallbacks)
        pendingSelectionCapture = null
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) {
            val task = Runnable { captureCurrentSelection() }
            pendingSelectionCapture = task
            main.postDelayed(task, 250)
        }
        renderClipChips()
        updateToolbarCenterVisibility()
        updateInsertionHistoryButtons()
        syncAutoCapitalization(preserveManualShift = false)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
        autoCapMode = AutoCapitalizationPolicy.resolveCapMode(info.inputType, isFilterMode)
        if (!restarting) {
            shiftStateMachine.clearManualOverride()
        } else {
            shiftStateMachine.resetDoubleTapTimer()
        }
        toolbarStateACenterMode = 0
        fieldCapabilities = InputFieldPolicy.evaluate(info.inputType, info.imeOptions)
        isPasswordTarget = fieldCapabilities.isPasswordOrPin || isSensitiveField(info.inputType)
        if (isPasswordTarget && isFilterMode) {
            isFilterMode = false
        }
        if (!restarting) {
            modeController.onFieldFocused(fieldCapabilities, restarting = false)
        }
        updateToolbarCenterVisibility()
        updateInsertionHistoryButtons()
        renderKeyboardRows()
        syncAutoCapitalization(preserveManualShift = manualShiftOverride)
        updateCaptureMonitoring()
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) {
            capturePrimaryClip(CaptureSource.CLIPBOARD_POLL)
        }
        reloadItems()
        scheduleExpiryCleanup()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!inputViewActive) return
        if (pasteCapsuleController.isActive) {
            val gateway = buildEditorGateway()
            val hasSelection = (newSelStart != newSelEnd) || (gateway?.hasActiveSelection() == true)
            val cursorMoved = (oldSelStart != newSelStart) || (oldSelEnd != newSelEnd)
            val canUndoNow = gateway != null && insertionHistory.canSafelyUndo(gateway).allowed
            val canRedoNow = gateway != null && insertionHistory.canSafelyRedo(gateway).allowed
            val dismissed = pasteCapsuleController.onEditorSelectionChanged(
                nowMs = SystemClock.elapsedRealtime(),
                hasSelection = hasSelection,
                cursorMoved = cursorMoved,
                canUndoNow = canUndoNow,
                canRedoNow = canRedoNow
            )
            if (dismissed) {
                updatePasteUndoCapsuleUI()
            }
        }
        updateInsertionHistoryButtons()
        if (newSelStart != newSelEnd) {
            clearTypingSuggestionsView()
            syncAutoCapitalization(preserveManualShift = false)
            return
        }
        // If cursor jumped outside 1-char typing flow, clear manual shift override & re-sync context
        val delta = kotlin.math.abs(newSelStart - oldSelStart)
        if (delta > 1) {
            shiftStateMachine.onNonLetterInput()
            insertionHistory.invalidateImmediateBackspaceRevert()
            lastRevertOriginalWord = null
            if (!isFilterMode && !isPasswordTarget && fieldCapabilities.allowSuggestions && isSuggestionsEnabled) {
                refreshTypingSuggestionsAsync()
            } else {
                clearTypingSuggestionsView()
            }
            syncAutoCapitalization(preserveManualShift = false)
        } else {
            syncAutoCapitalization(preserveManualShift = true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (contextMenuHost != null || isMergeMode || undoRedoHost != null ||
                isEmojiPickerOpen || isSettingsOpen || isClipboardPanelOpen || isFilterMode || toolbarState == 1) {
                if (event.repeatCount == 0) {
                    event.startTracking()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (contextMenuHost != null) {
                if (!event.isCanceled) {
                    dismissContextMenu(animate = true)
                }
                return true
            }
            if (isMergeMode) {
                if (!event.isCanceled) {
                    exitMergeMode()
                    setStatus("Merge cancelled.")
                }
                return true
            }
            if (undoRedoHost != null) {
                if (!event.isCanceled) {
                    dismissUndoRedoControl()
                }
                return true
            }
            if (isEmojiPickerOpen) {
                if (!event.isCanceled) {
                    isEmojiPickerOpen = false
                    renderFullImeView()
                }
                return true
            }
            if (isSettingsOpen || isClipboardPanelOpen || isFilterMode || pinnedOnly || toolbarState == 1) {
                if (!event.isCanceled) {
                    val wasFilterMode = isFilterMode
                    isSettingsOpen = false
                    settingsSavedScrollY = 0
                    isClipboardPanelOpen = false
                    clipboardSavedScrollY = 0
                    isFilterMode = false
                    pinnedOnly = false
                    keyboardSearchQuery = ""
                    keyboardSearchResults = emptyList()
                    toolbarState = 0
                    if (wasFilterMode) {
                        modeController.exitSearchClips()
                    }
                    renderFullImeView()
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowHidden() {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode(refresh = false)
        shiftStateMachine.resetDoubleTapTimer()
        val needsReset = isEmojiPickerOpen || isSettingsOpen || isClipboardPanelOpen || isFilterMode || pinnedOnly || toolbarState != 0
        isEmojiPickerOpen = false
        isSettingsOpen = false
        settingsSavedScrollY = 0
        isClipboardPanelOpen = false
        clipboardSavedScrollY = 0
        isFilterMode = false
        pinnedOnly = false
        keyboardSearchQuery = ""
        keyboardSearchResults = emptyList()
        toolbarState = 0
        modeController.onWindowHiddenReset()
        if (needsReset && imeRootFrame != null) {
            renderFullImeView()
        }
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode(refresh = false)
        super.onConfigurationChanged(newConfig)
        if (imeRootFrame != null) {
            renderFullImeView()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        dismissPasteUndoCapsule()
        exitMergeMode(refresh = false)
        inputViewActive = false
        updateCaptureMonitoring()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        dismissPasteUndoCapsule()
        exitMergeMode(refresh = false)
        generation++
        inputSessionActive = false
        inputViewActive = false
        isPasswordTarget = false
        updateCaptureMonitoring()
        pendingSelectionCapture?.let(main::removeCallbacks)
        pendingSelectionCapture = null
        expiryCleanupRunnable?.let(main::removeCallbacks)
        expiryCleanupRunnable = null
        insertTask?.cancel(true)
        insertTask = null
        isInserting = false
        super.onFinishInput()
    }

    override fun onDestroy() {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode(refresh = false)
        serviceDestroyed = true
        generation++
        inputSessionActive = false
        inputViewActive = false
        isPasswordTarget = false
        updateCaptureMonitoring()
        ClipStore.removeChangeListener(storeChangeListener)
        capturePreferences.unregisterOnSharedPreferenceChangeListener(capturePreferenceListener)
        themePreferenceListener?.let { themePreferences.unregisterOnSharedPreferenceChangeListener(it) }
        pendingSelectionCapture?.let(main::removeCallbacks)
        expiryCleanupRunnable?.let(main::removeCallbacks)
        insertTask?.cancel(true)
        io.shutdownNow()
        inserter.shutdownNow()
        suggestExecutor.shutdownNow()
        super.onDestroy()
    }

    // =========================================================================
    // HOST INSERTION UNDO/REDO & TYPING INTELLIGENCE HELPERS
    // =========================================================================
    private fun buildEditorGateway(conn: InputConnection? = currentInputConnection): InsertionHistoryManager.HostEditorGateway? {
        val ic = conn ?: return null
        return object : InsertionHistoryManager.HostEditorGateway {
            override fun currentTarget(): InsertionHistoryManager.EditorTarget = currentEditorTarget
            override fun isPasswordField(): Boolean = isPasswordTarget
            override fun hasActiveSelection(): Boolean {
                val sel = runCatching { ic.getSelectedText(0) }.getOrNull()
                if (!sel.isNullOrEmpty()) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val surrounding = runCatching { ic.getSurroundingText(0, 0, 0) }.getOrNull()
                    if (surrounding != null) {
                        return surrounding.selectionStart != surrounding.selectionEnd
                    }
                }
                return false
            }

            override fun textBeforeCursor(maxChars: Int): String? {
                if (maxChars <= 0) return ""
                val direct = runCatching { ic.getTextBeforeCursor(maxChars, 0)?.toString() }.getOrNull()
                if (direct != null && (direct.length >= maxChars || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                    return direct
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android S+ getSurroundingText throws IllegalArgumentException if fetchLen > 2048
                    val fetchLen = minOf(maxChars, 2048)
                    val surrounding = runCatching { ic.getSurroundingText(fetchLen, 0, 0) }.getOrNull()
                    if (surrounding != null) {
                        val selStart = surrounding.selectionStart.coerceIn(0, surrounding.text.length)
                        val fromSurrounding = surrounding.text.subSequence(0, selStart).toString()
                        if (direct == null || fromSurrounding.length > direct.length) {
                            return fromSurrounding
                        }
                    }
                }
                if (direct != null) return direct
                if (maxChars > 2048) {
                    val fallbackDirect = runCatching { ic.getTextBeforeCursor(2048, 0)?.toString() }.getOrNull()
                    if (fallbackDirect != null) return fallbackDirect
                }
                val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
                if (extracted != null && extracted.text != null && extracted.startOffset >= 0) {
                    val localSelStart = extracted.selectionStart - extracted.startOffset
                    if (localSelStart in 0..extracted.text.length && (extracted.startOffset == 0 || localSelStart >= maxChars)) {
                        val start = (localSelStart - maxChars).coerceAtLeast(0)
                        return extracted.text.subSequence(start, localSelStart).toString()
                    }
                }
                return null
            }

            override fun textAfterCursor(maxChars: Int): String? {
                if (maxChars <= 0) return ""
                val direct = runCatching { ic.getTextAfterCursor(maxChars, 0)?.toString() }.getOrNull()
                if (direct != null && (direct.length >= maxChars || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                    return direct
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val fetchLen = minOf(maxChars, 2048)
                    val surrounding = runCatching { ic.getSurroundingText(0, fetchLen, 0) }.getOrNull()
                    if (surrounding != null) {
                        val selEnd = surrounding.selectionEnd.coerceIn(0, surrounding.text.length)
                        val fromSurrounding = surrounding.text.subSequence(selEnd, surrounding.text.length).toString()
                        if (direct == null || fromSurrounding.length > direct.length) {
                            return fromSurrounding
                        }
                    }
                }
                if (direct != null) return direct
                if (maxChars > 2048) {
                    val fallbackDirect = runCatching { ic.getTextAfterCursor(2048, 0)?.toString() }.getOrNull()
                    if (fallbackDirect != null) return fallbackDirect
                }
                val extracted = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
                if (extracted != null && extracted.text != null && extracted.startOffset >= 0) {
                    val localSelEnd = extracted.selectionEnd - extracted.startOffset
                    if (localSelEnd in 0..extracted.text.length) {
                        val end = (localSelEnd + maxChars).coerceAtMost(extracted.text.length)
                        return extracted.text.subSequence(localSelEnd, end).toString()
                    }
                }
                return null
            }

            override fun deleteBeforeCursor(length: Int): Boolean {
                if (length < 0) return false
                if (length == 0) return true
                var remaining = length
                while (remaining > 0) {
                    val step = minOf(remaining, 4096)
                    val deleted = runCatching { ic.deleteSurroundingText(step, 0) }.getOrDefault(false)
                    if (!deleted) return false
                    remaining -= step
                }
                return true
            }

            override fun commitText(text: String): Boolean {
                if (text.isEmpty()) return true
                if (text.length <= 16_384) {
                    return runCatching { ic.commitText(text, 1) }.getOrDefault(false)
                }
                var offset = 0
                while (offset < text.length) {
                    var end = minOf(offset + 16_384, text.length)
                    if (end < text.length && Character.isHighSurrogate(text[end - 1])) {
                        end--
                    }
                    val chunk = text.substring(offset, end)
                    val ok = runCatching { ic.commitText(chunk, 1) }.getOrDefault(false)
                    if (!ok) return false
                    offset = end
                }
                return true
            }

            override fun runBatchEdit(block: () -> Boolean): Boolean {
                return try {
                    ic.beginBatchEdit()
                    runCatching { ic.finishComposingText() }
                    block()
                } finally {
                    runCatching { ic.endBatchEdit() }
                }
            }
        }
    }

    private fun updateInsertionHistoryButtons() {
        updatePasteUndoCapsuleUI()
    }

    private fun performInsertionUndo() {
        val gateway = buildEditorGateway()
        if (gateway == null) {
            setStatus("Undo unavailable: no active input field.")
            dismissPasteUndoCapsule()
            return
        }
        val verifyToken = ++pasteHistoryVerifyToken
        val checkLen = 128
        val preBefore = gateway.textBeforeCursor(checkLen)
        val result = insertionHistory.undo(gateway)
        val entry = result.entry
        if (result.success && entry?.kind == InsertionHistoryManager.ActionKind.AUTOCORRECT) {
            if (fieldCapabilities.allowSessionLearning) {
                suppressedAutocorrectWords.add(entry.rawOriginalWord.lowercase(Locale.ROOT))
            }
            lastRevertOriginalWord = null
            refreshTypingSuggestionsAsync()
        } else {
            clearTypingSuggestionsView()
        }
        shiftStateMachine.onNonLetterInput()
        setStatus(result.message)

        if (!result.success || entry == null) {
            dismissPasteUndoCapsule()
            syncAutoCapitalization(preserveManualShift = false)
            return
        }

        pasteCapsuleController.onUndoSucceeded(SystemClock.elapsedRealtime())
        updatePasteUndoCapsuleUI()

        val immediateBefore = gateway.textBeforeCursor(checkLen)
        val immediateMutated = (preBefore != null && immediateBefore != preBefore) ||
            insertionHistory.canSafelyRedo(gateway).allowed
        if (immediateMutated) {
            syncAutoCapitalization(preserveManualShift = false)
            return
        }

        // Optimistic/async InputConnection (e.g. Compose / WebView) may not expose updated buffer at t=0ms.
        main.postDelayed({
            if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@postDelayed
            val g1 = buildEditorGateway()
            val after1 = g1?.textBeforeCursor(checkLen)
            if (g1 != null && ((preBefore != null && after1 != preBefore) || insertionHistory.canSafelyRedo(g1).allowed)) {
                syncAutoCapitalization(preserveManualShift = false)
                return@postDelayed
            }
            main.postDelayed({
                if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@postDelayed
                val g2 = buildEditorGateway()
                val after2 = g2?.textBeforeCursor(checkLen)
                if (g2 != null && ((preBefore != null && after2 != preBefore) || insertionHistory.canSafelyRedo(g2).allowed)) {
                    syncAutoCapitalization(preserveManualShift = false)
                } else if (g2 != null && entry.target.isCompatibleWith(g2.currentTarget())) {
                    if (preBefore != null && after2 == preBefore && !insertionHistory.canSafelyRedo(g2).allowed) {
                        insertionHistory.rollbackLastUndo(entry)
                        dismissPasteUndoCapsule()
                        syncAutoCapitalization(preserveManualShift = false)
                        setStatus("Destination editor rejected undo.")
                    }
                }
            }, 250L)
        }, 150L)
    }

    private fun performInsertionRedo() {
        val gateway = buildEditorGateway()
        if (gateway == null) {
            setStatus("Redo unavailable: no active input field.")
            dismissPasteUndoCapsule()
            return
        }
        val verifyToken = ++pasteHistoryVerifyToken
        val checkLen = 128
        val preBefore = gateway.textBeforeCursor(checkLen)
        val result = insertionHistory.redo(gateway)
        val entry = result.entry
        clearTypingSuggestionsView()
        shiftStateMachine.onNonLetterInput()
        setStatus(result.message)

        if (!result.success || entry == null) {
            dismissPasteUndoCapsule()
            syncAutoCapitalization(preserveManualShift = false)
            return
        }

        val canUndoRemaining = insertionHistory.hasAnyUndoEntries()
        pasteCapsuleController.onRedoSucceeded(canUndoRemaining, SystemClock.elapsedRealtime())
        updatePasteUndoCapsuleUI()

        val immediateBefore = gateway.textBeforeCursor(checkLen)
        val immediateMutated = (preBefore != null && immediateBefore != preBefore) ||
            insertionHistory.canSafelyUndo(gateway).allowed
        if (immediateMutated) {
            syncAutoCapitalization(preserveManualShift = false)
            return
        }

        main.postDelayed({
            if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@postDelayed
            val g1 = buildEditorGateway()
            val after1 = g1?.textBeforeCursor(checkLen)
            if (g1 != null && ((preBefore != null && after1 != preBefore) || insertionHistory.canSafelyUndo(g1).allowed)) {
                syncAutoCapitalization(preserveManualShift = false)
                return@postDelayed
            }
            main.postDelayed({
                if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@postDelayed
                val g2 = buildEditorGateway()
                val after2 = g2?.textBeforeCursor(checkLen)
                if (g2 != null && ((preBefore != null && after2 != preBefore) || insertionHistory.canSafelyUndo(g2).allowed)) {
                    syncAutoCapitalization(preserveManualShift = false)
                } else if (g2 != null && entry.target.isCompatibleWith(g2.currentTarget())) {
                    if (preBefore != null && after2 == preBefore && !insertionHistory.canSafelyUndo(g2).allowed) {
                        insertionHistory.rollbackLastRedo(entry)
                        dismissPasteUndoCapsule()
                        syncAutoCapitalization(preserveManualShift = false)
                        setStatus("Destination editor rejected redo.")
                    }
                }
            }, 250L)
        }, 150L)
    }

    private fun onTypingSettingsChanged() {
        if (!isSuggestionsEnabled || !fieldCapabilities.allowSuggestions) {
            showClipboardPillsInToolbar()
        } else {
            refreshTypingSuggestionsAsync()
        }
    }

    private fun showClipboardPillsInToolbar() {
        suggestionGeneration.incrementAndGet()
        toolbarStateACenterMode = 0
        currentWordAnalysis = null
        activeComposingWord = ""
        currentNextWordSuggestions = emptyList()
        lastRevertOriginalWord = null
        updateToolbarCenterVisibility()
    }

    private fun clearTypingSuggestionsView() {
        currentWordAnalysis = null
        activeComposingWord = ""
        currentNextWordSuggestions = emptyList()
        toolbarStateACenterMode = 0
        updateToolbarCenterVisibility()
    }

    private fun updateToolbarCenterVisibility() {
        val stripState = KeyboardLayoutSpec.resolveToolbarStripState(
            mode = keyboardMode,
            centerMode = toolbarStateACenterMode,
            isPasswordTarget = isPasswordTarget,
            isFilterMode = isFilterMode,
            isSuggestionsEnabled = isSuggestionsEnabled,
            allowSuggestions = fieldCapabilities.allowSuggestions
        )
        toolbarChipsScroll?.visibility = if (stripState.showClipboardStrip) View.VISIBLE else View.GONE
        suggestionScrollView?.visibility = if (stripState.showSuggestionsStrip) View.VISIBLE else View.GONE
    }

    private data class ComposingContext(
        val composingWord: String,
        val fullToken: String,
        val beforeAnchor: String,
        val afterAnchor: String,
        val isSentenceStart: Boolean,
        val previousWord: String?,
        val endedWithSpace: Boolean,
        val midWord: Boolean
    )

    private fun extractComposingContext(conn: InputConnection): ComposingContext? {
        val before = runCatching { conn.getTextBeforeCursor(72, 0)?.toString() }.getOrNull() ?: return null
        val after = runCatching { conn.getTextAfterCursor(48, 0)?.toString() }.getOrNull().orEmpty()
        val selected = runCatching { conn.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return null

        val midWord = after.isNotEmpty() && (after[0].isLetterOrDigit() || after[0] == '\'')
        val endedWithSpace = before.isNotEmpty() && before.last().isWhitespace()

        // Full non-whitespace token right before cursor
        var wsIdx = before.length - 1
        while (wsIdx >= 0 && !before[wsIdx].isWhitespace()) wsIdx--
        val fullToken = if (midWord || endedWithSpace) "" else before.substring(wsIdx + 1)

        // Trailing letter/apostrophe word
        var wordStart = before.length - 1
        while (wordStart >= 0 && (before[wordStart].isLetter() || before[wordStart] == '\'')) {
            wordStart--
        }
        val composingWord = if (midWord || endedWithSpace) "" else before.substring(wordStart + 1)
        val anchorBeforeWord = if (composingWord.isNotEmpty()) {
            before.substring(0, wordStart + 1).takeLast(InsertionHistoryManager.ANCHOR_LENGTH)
        } else {
            before.takeLast(InsertionHistoryManager.ANCHOR_LENGTH)
        }

        // Check sentence start before fullToken
        var scanIdx = if (fullToken.isNotEmpty()) wsIdx else before.length - 1
        while (scanIdx >= 0 && before[scanIdx] == ' ') scanIdx--
        val isSentenceStart = scanIdx < 0 || before[scanIdx] in charArrayOf('\n', '.', '!', '?')

        // Extract previous completed word (when composing or right after space)
        var prevEnd = scanIdx
        while (prevEnd >= 0 && !before[prevEnd].isLetter() && before[prevEnd] != '\'') {
            if (!before[prevEnd].isWhitespace()) {
                prevEnd = -1
                break
            }
            prevEnd--
        }
        val prevWord = if (prevEnd >= 0) {
            var prevStart = prevEnd
            while (prevStart >= 0 && (before[prevStart].isLetter() || before[prevStart] == '\'')) prevStart--
            before.substring(prevStart + 1, prevEnd + 1).takeIf { it.isNotEmpty() }
        } else {
            null
        }

        return ComposingContext(
            composingWord = composingWord,
            fullToken = fullToken,
            beforeAnchor = anchorBeforeWord,
            afterAnchor = after,
            isSentenceStart = isSentenceStart,
            previousWord = prevWord,
            endedWithSpace = endedWithSpace,
            midWord = midWord
        )
    }

    private fun refreshTypingSuggestionsAsync() {
        if (isPasswordTarget || isFilterMode) {
            clearTypingSuggestionsView()
            return
        }
        val allowAutocomp = isAutocompleteEnabled && fieldCapabilities.allowAutocomplete
        val allowAutocorr = isAutoCorrectEnabled && fieldCapabilities.allowAutocorrect
        val allowSuggestUI = isSuggestionsEnabled && fieldCapabilities.allowSuggestions

        if (!allowAutocomp && !allowAutocorr && !allowSuggestUI) {
            clearTypingSuggestionsView()
            return
        }
        val conn = currentInputConnection ?: return
        val ctx = extractComposingContext(conn) ?: run {
            clearTypingSuggestionsView()
            return
        }
        if (ctx.midWord || ctx.composingWord.isEmpty()) {
            if (ctx.endedWithSpace && allowAutocomp && allowSuggestUI && ctx.previousWord != null) {
                val nextWords = typingEngine.nextWordSuggestions(ctx.previousWord, true)
                currentWordAnalysis = null
                activeComposingWord = ""
                currentNextWordSuggestions = nextWords
                if (nextWords.isNotEmpty() || lastRevertOriginalWord != null) {
                    toolbarStateACenterMode = 1
                    renderSuggestionStrip()
                    updateToolbarCenterVisibility()
                } else {
                    clearTypingSuggestionsView()
                }
            } else {
                clearTypingSuggestionsView()
            }
            return
        }

        val reqGen = suggestionGeneration.incrementAndGet()
        val suppressedSnapshot = suppressedAutocorrectWords.toSet()
        suggestExecutor.execute {
            val analysis = typingEngine.analyzeWord(
                composingWord = ctx.composingWord,
                fullTokenBeforeCursor = ctx.fullToken,
                isSentenceStart = ctx.isSentenceStart,
                autocompleteEnabled = allowAutocomp,
                autoCorrectEnabled = allowAutocorr,
                suppressedWordsLower = suppressedSnapshot,
                maxSuggestions = 4
            )
            main.post {
                if (reqGen != suggestionGeneration.get() || serviceDestroyed || isPasswordTarget || isFilterMode) {
                    return@post
                }
                activeComposingWord = ctx.composingWord
                currentWordAnalysis = analysis
                currentNextWordSuggestions = emptyList()
                if (allowSuggestUI && fieldCapabilities.allowSuggestions) {
                    toolbarStateACenterMode = 1
                    renderSuggestionStrip()
                    updateToolbarCenterVisibility()
                }
            }
        }
    }

    private fun isWordBoundaryDelimiter(ch: String): Boolean {
        return ch == " " || ch == "." || ch == "," || ch == "!" || ch == "?" || ch == ";" || ch == ":"
    }

    private fun syncAutoCapitalization(
        preserveManualShift: Boolean = false,
        predictedBeforeText: String? = null
    ) {
        val info = currentInputEditorInfo
        val resolved = if (isFilterMode) {
            AutoCapitalizationPolicy.CapMode.NONE
        } else if (info != null) {
            AutoCapitalizationPolicy.resolveCapMode(info.inputType, isFilterMode = false)
        } else {
            AutoCapitalizationPolicy.CapMode.NONE
        }
        autoCapMode = resolved

        val changed = shiftStateMachine.syncWithAutoCap(
            capMode = resolved,
            preserveManualShift = preserveManualShift
        ) {
            val conn = currentInputConnection
            val before = predictedBeforeText ?: runCatching { conn?.getTextBeforeCursor(48, 0)?.toString() }.getOrNull()
            val after = runCatching { conn?.getTextAfterCursor(16, 0)?.toString() }.getOrNull()
            val hasSel = !runCatching { conn?.getSelectedText(0) }.getOrNull().isNullOrEmpty()
            val androidCaps = if (info != null && conn != null) {
                runCatching { conn.getCursorCapsMode(info.inputType) }.getOrDefault(0)
            } else 0

            AutoCapitalizationPolicy.shouldAutoShift(
                capMode = resolved,
                textBeforeCursor = before,
                textAfterCursor = after,
                hasSelection = hasSel,
                androidCursorCapsMode = androidCaps
            )
        }
        if (changed) {
            updateShiftUI()
        }
    }

    private fun commitWordBoundaryWithIntelligence(conn: InputConnection, delimiter: String) {
        suggestionGeneration.incrementAndGet()
        val beforeFallback = runCatching { conn.getTextBeforeCursor(48, 0)?.toString() }.getOrNull()
        if (isPasswordTarget || !isAutoCorrectEnabled || !fieldCapabilities.allowAutocorrect) {
            lastRevertOriginalWord = null
            insertionHistory.invalidateImmediateBackspaceRevert()
            conn.commitText(delimiter, 1)
            updateInsertionHistoryButtons()
            refreshTypingSuggestionsAsync()
            syncAutoCapitalization(
                preserveManualShift = false,
                predictedBeforeText = beforeFallback?.plus(delimiter)
            )
            return
        }

        val ctx = extractComposingContext(conn)
        val word = ctx?.composingWord.orEmpty()
        if (ctx == null || ctx.midWord || word.length < 2) {
            lastRevertOriginalWord = null
            insertionHistory.invalidateImmediateBackspaceRevert()
            conn.commitText(delimiter, 1)
            updateInsertionHistoryButtons()
            refreshTypingSuggestionsAsync()
            syncAutoCapitalization(
                preserveManualShift = false,
                predictedBeforeText = beforeFallback?.plus(delimiter)
            )
            return
        }

        val suppressedSnapshot = suppressedAutocorrectWords.toSet()
        val cached = currentWordAnalysis?.takeIf { it.originalWord == word }
        val analysis = cached ?: typingEngine.analyzeWord(
            composingWord = word,
            fullTokenBeforeCursor = ctx.fullToken,
            isSentenceStart = ctx.isSentenceStart,
            autocompleteEnabled = isAutocompleteEnabled && fieldCapabilities.allowAutocomplete,
            autoCorrectEnabled = true,
            suppressedWordsLower = suppressedSnapshot,
            maxSuggestions = 4
        )

        val correction = analysis.autoCorrection
        if (correction != null && correction != word && word.lowercase(Locale.ROOT) !in suppressedSnapshot) {
            val committed = try {
                conn.beginBatchEdit()
                conn.deleteSurroundingText(word.length, 0) && conn.commitText(correction + delimiter, 1)
            } finally {
                runCatching { conn.endBatchEdit() }
            }
            if (committed) {
                insertionHistory.recordAutocorrect(
                    target = currentEditorTarget,
                    rawOriginalWord = word,
                    correctedWord = correction,
                    delimiter = delimiter,
                    beforeAnchor = ctx.beforeAnchor,
                    afterAnchor = ctx.afterAnchor
                )
                lastRevertOriginalWord = word
                currentWordAnalysis = null
                activeComposingWord = ""
                currentNextWordSuggestions = if (isAutocompleteEnabled && fieldCapabilities.allowAutocomplete) {
                    typingEngine.nextWordSuggestions(correction, true)
                } else emptyList()
                if (isSuggestionsEnabled && fieldCapabilities.allowSuggestions) {
                    toolbarStateACenterMode = 1
                    renderSuggestionStrip()
                    updateToolbarCenterVisibility()
                }
                updateInsertionHistoryButtons()
                syncAutoCapitalization(
                    preserveManualShift = false,
                    predictedBeforeText = ctx.beforeAnchor + correction + delimiter
                )
                return
            }
        }

        lastRevertOriginalWord = null
        insertionHistory.invalidateImmediateBackspaceRevert()
        conn.commitText(delimiter, 1)
        updateInsertionHistoryButtons()
        refreshTypingSuggestionsAsync()
        syncAutoCapitalization(
            preserveManualShift = false,
            predictedBeforeText = ctx.beforeAnchor + ctx.composingWord + delimiter
        )
    }

    private fun renderSuggestionStrip() {
        val container = suggestionsContainer ?: return
        container.removeAllViews()
        if (isPasswordTarget || !isSuggestionsEnabled || !fieldCapabilities.allowSuggestions) return

        val revertOrig = lastRevertOriginalWord
        if (revertOrig != null) {
            container.addView(buildSuggestionChip("Undo \"$revertOrig\"", isHighlight = false, isRevert = true) {
                val gateway = buildEditorGateway()
                if (gateway != null) {
                    val res = insertionHistory.undo(gateway)
                    if (res.success && fieldCapabilities.allowSessionLearning) {
                        suppressedAutocorrectWords.add(revertOrig.lowercase(Locale.ROOT))
                    }
                    lastRevertOriginalWord = null
                    updateInsertionHistoryButtons()
                    refreshTypingSuggestionsAsync()
                    setStatus(res.message)
                }
            })
        }

        val analysis = currentWordAnalysis
        val word = activeComposingWord
        if (analysis != null && word.isNotEmpty()) {
            // Always show the typed word option if autocorrect would change it or it's unknown, so user can keep it
            if (analysis.autoCorrection != null || !analysis.isKnownWord) {
                container.addView(buildSuggestionChip("\"$word\"", isHighlight = false, isRevert = false) {
                    acceptTypedWordAsIs(word)
                })
            }
            for ((idx, sug) in analysis.suggestions.withIndex()) {
                val highlight = (sug == analysis.autoCorrection) || (idx == 0 && analysis.autoCorrection == null)
                container.addView(buildSuggestionChip(sug, isHighlight = highlight, isRevert = false) {
                    applyManualSuggestion(sug)
                })
            }
        } else if (currentNextWordSuggestions.isNotEmpty()) {
            for ((idx, nextWord) in currentNextWordSuggestions.withIndex()) {
                container.addView(buildSuggestionChip(nextWord, isHighlight = (idx == 0), isRevert = false) {
                    insertNextWordSuggestion(nextWord)
                })
            }
        }
    }

    private fun buildSuggestionChip(
        label: String,
        isHighlight: Boolean,
        isRevert: Boolean,
        onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hPad = dp(OverlayUiSpec.SUGGESTION_PILL_PADDING_H_DP).toInt()
        setPadding(hPad, 0, hPad, 0)
        val style = OverlayUiSpec.resolveSuggestionPillStyle(
            palette = currentThemePalette,
            isPrimary = isHighlight,
            isRevert = isRevert
        )
        background = roundedRipple(style.bgColor, dp(OverlayUiSpec.SUGGESTION_PILL_CORNER_DP), style.borderColor)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(OverlayUiSpec.SUGGESTION_PILL_HEIGHT_DP).toInt()
        ).apply {
            rightMargin = dp(6)
        }

        if (isRevert) {
            val undoIv = createIconView(IconDrawable(IconType.UNDO, style.textColor, 12.5f)).apply {
                setPadding(0, 0, dp(5), 0)
            }
            addView(undoIv)
        }

        val tv = TextView(this@ClipMaxImeService).apply {
            text = label
            textSize = OverlayUiSpec.SUGGESTION_PILL_TEXT_SP
            typeface = if (isHighlight) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(style.textColor)
            includeFontPadding = false
            maxWidth = dp(OverlayUiSpec.SUGGESTION_PILL_MAX_WIDTH_DP).toInt()
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(tv)
        setOnClickListener { onClick() }
    }

    private fun acceptTypedWordAsIs(word: String) {
        val conn = currentInputConnection ?: return
        val ctx = extractComposingContext(conn) ?: return
        if (ctx.composingWord != word || ctx.midWord) return
        if (fieldCapabilities.allowSessionLearning) {
            suppressedAutocorrectWords.add(word.lowercase(Locale.ROOT))
        }
        lastRevertOriginalWord = null
        insertionHistory.invalidateImmediateBackspaceRevert()
        val after = runCatching { conn.getTextAfterCursor(1, 0)?.toString() }.getOrNull().orEmpty()
        if (after.isEmpty() || !after[0].isWhitespace()) {
            conn.commitText(" ", 1)
        }
        shiftStateMachine.onNonLetterInput()
        updateInsertionHistoryButtons()
        refreshTypingSuggestionsAsync()
        syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = ctx.beforeAnchor + word + " ")
    }

    private fun applyManualSuggestion(chosenWord: String) {
        val conn = currentInputConnection ?: return
        val ctx = extractComposingContext(conn) ?: return
        val word = ctx.composingWord
        if (word.isEmpty() || ctx.midWord) return
        val after = runCatching { conn.getTextAfterCursor(1, 0)?.toString() }.getOrNull().orEmpty()
        val trailingSpace = if (after.isEmpty() || !after[0].isWhitespace()) " " else ""
        val committed = try {
            conn.beginBatchEdit()
            conn.deleteSurroundingText(word.length, 0) && conn.commitText(chosenWord + trailingSpace, 1)
        } finally {
            runCatching { conn.endBatchEdit() }
        }
        if (committed) {
            lastRevertOriginalWord = null
            shiftStateMachine.onNonLetterInput()
            insertionHistory.invalidateImmediateBackspaceRevert()
            updateInsertionHistoryButtons()
            currentWordAnalysis = null
            activeComposingWord = ""
            currentNextWordSuggestions = if (isAutocompleteEnabled && fieldCapabilities.allowAutocomplete) {
                typingEngine.nextWordSuggestions(chosenWord, true)
            } else emptyList()
            if (currentNextWordSuggestions.isNotEmpty() && isSuggestionsEnabled && fieldCapabilities.allowSuggestions) {
                toolbarStateACenterMode = 1
                renderSuggestionStrip()
                updateToolbarCenterVisibility()
            } else {
                showClipboardPillsInToolbar()
            }
            syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = ctx.beforeAnchor + chosenWord + trailingSpace)
        }
    }

    private fun insertNextWordSuggestion(nextWord: String) {
        val conn = currentInputConnection ?: return
        val beforeFallback = runCatching { conn.getTextBeforeCursor(48, 0)?.toString() }.getOrNull()
        val after = runCatching { conn.getTextAfterCursor(1, 0)?.toString() }.getOrNull().orEmpty()
        val trailingSpace = if (after.isEmpty() || !after[0].isWhitespace()) " " else ""
        conn.commitText(nextWord + trailingSpace, 1)
        lastRevertOriginalWord = null
        shiftStateMachine.onNonLetterInput()
        insertionHistory.invalidateImmediateBackspaceRevert()
        updateInsertionHistoryButtons()
        refreshTypingSuggestionsAsync()
        syncAutoCapitalization(
            preserveManualShift = false,
            predictedBeforeText = beforeFallback?.plus(nextWord + trailingSpace)
        )
    }

    private fun isSensitiveField(inputType: Int): Boolean {
        val fieldClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (fieldClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (fieldClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun captureCurrentSelection() {
        if (!inputSessionActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode)) return
        val selected = runCatching { currentInputConnection?.getSelectedText(0) }.getOrNull()
        if (selected != null && selected.isNotEmpty()) {
            val mode = captureMode
            ClipboardCaptureCoordinator.captureSelection(
                context = this,
                selectedText = selected,
                isSensitiveField = isPasswordTarget
            ) { outcome ->
                if (!serviceDestroyed && outcome != null && outcome.savedCount > 0) {
                    setStatus("Captured ${outcome.savedCount} ${if (outcome.savedCount == 1) "card" else "cards"} • ${CapturePreferences.modeLabel(mode)}")
                    reloadItems()
                    scheduleExpiryCleanup()
                }
            }
        }
    }

    private fun updateCaptureMonitoring() {
        val manager = clipboardManager ?: return
        val listener = clipboardListener ?: return
        val shouldListen = !serviceDestroyed && CapturePreferences.isEnabled(captureMode)
        if (shouldListen && !clipboardRegistered) {
            runCatching { manager.addPrimaryClipChangedListener(listener); clipboardRegistered = true }
        } else if (!shouldListen && clipboardRegistered) {
            runCatching { manager.removePrimaryClipChangedListener(listener) }
            clipboardRegistered = false
        }
    }

    private fun updateCaptureStatus() {
        setStatus(if (CapturePreferences.isEnabled(captureMode)) "Auto-capture active • ${CapturePreferences.modeLabel(captureMode)}" else "Auto-capture off")
    }

    private fun capturePrimaryClip(source: CaptureSource) {
        if (isPasswordTarget || !CapturePreferences.isEnabled(captureMode)) return
        val mode = captureMode
        ClipboardCaptureCoordinator.captureCurrentClipboard(
            context = this,
            source = source,
            isSensitiveField = isPasswordTarget
        ) { outcome ->
            if (!serviceDestroyed && outcome != null && outcome.savedCount > 0) {
                setStatus("Captured ${outcome.savedCount} ${if (outcome.savedCount == 1) "card" else "cards"} • ${CapturePreferences.modeLabel(mode)}")
                reloadItems()
                scheduleExpiryCleanup()
            }
        }
    }

    private fun scheduleExpiryCleanup() {
        expiryCleanupRunnable?.let(main::removeCallbacks)
        if (!inputSessionActive) return
        val next = CapturePreferences.nextExpiry(capturePreferences) ?: return
        val task = Runnable {
            io.execute {
                val deleted = CapturePreferences.cleanupExpired(store, capturePreferences)
                main.post {
                    if (deleted > 0) reloadItems()
                    scheduleExpiryCleanup()
                }
            }
        }
        expiryCleanupRunnable = task
        main.postDelayed(task, (next - System.currentTimeMillis()).coerceAtLeast(1_000L))
    }

    // =========================================================================
    // DRAWABLE & MEASUREMENT HELPERS
    // =========================================================================
    private fun roundedDrawable(bgColor: Int, cornerRadiusPx: Float, strokeColor: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = cornerRadiusPx
            if (strokeColor != 0) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun roundedRipple(
        normalColor: Int,
        cornerRadiusPx: Float,
        strokeColor: Int = 0,
        rippleColor: Int = if (isClassicTheme) (if (isNightMode) 0x332DD4BF else 0x330D9488) else 0x24FFFFFF
    ): RippleDrawable {
        val content = if (normalColor == 0 && strokeColor == 0) {
            null
        } else {
            roundedDrawable(normalColor, cornerRadiusPx, strokeColor)
        }
        val mask = roundedDrawable(0xFFFFFFFF.toInt(), cornerRadiusPx)
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            content,
            mask
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun Int.grouped(): String = String.format(Locale.ROOT, "%,d", this)

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    // =========================================================================
    // PROFESSIONAL MONOCHROME VECTOR ICONS (Zero Unicode Emojis)
    // =========================================================================
    enum class IconType {
        CLIPBOARD, PIN, PIN_FILLED, KEYBOARD, IME_SWITCH, SEARCH, INSERT, EDIT, DELETE,
        CHEVRON_DOWN, CHEVRON_UP, CHEVRON_LEFT, CHEVRON_RIGHT, ARROW_LEFT, CLOSE, LOCK, SHIFT, SHIFT_ACTIVE, CAPS_LOCK, BACKSPACE, ENTER,
        SEND, GO, NEXT, DONE, SPACE,
        EMOJI_RECENT, EMOJI, EMOJI_PEOPLE, EMOJI_NATURE, EMOJI_FOOD, EMOJI_ACTIVITIES, EMOJI_TRAVEL, EMOJI_OBJECTS, EMOJI_SYMBOLS,
        MERGE, SPLIT, UNDO, REDO, SETTINGS
    }

    inner class IconDrawable(
        private val type: IconType,
        private var iconColor: Int,
        private val sizeDp: Float = 12f
    ) : Drawable() {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            style = Paint.Style.FILL
        }

        fun updateColor(newColor: Int) {
            iconColor = newColor
            strokePaint.color = newColor
            fillPaint.color = newColor
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val save = canvas.save()
            val side = minOf(b.width(), b.height()).toFloat()
            val dx = b.left + (b.width() - side) / 2f
            val dy = b.top + (b.height() - side) / 2f
            canvas.translate(dx, dy)
            val scale = side / 16f
            canvas.scale(scale, scale)

            when (type) {
                IconType.CLIPBOARD -> {
                    val path = Path().apply {
                        moveTo(5.5f, 3f)
                        lineTo(3.5f, 3f)
                        quadTo(2.5f, 3f, 2.5f, 4f)
                        lineTo(2.5f, 13.5f)
                        quadTo(2.5f, 14.5f, 3.5f, 14.5f)
                        lineTo(12.5f, 14.5f)
                        quadTo(13.5f, 14.5f, 13.5f, 13.5f)
                        lineTo(13.5f, 4f)
                        quadTo(13.5f, 3f, 12.5f, 3f)
                        lineTo(10.5f, 3f)
                    }
                    canvas.drawPath(path, strokePaint)
                    canvas.drawRoundRect(RectF(5f, 1.5f, 11f, 4.5f), 1f, 1f, strokePaint)
                    canvas.drawLine(5f, 7.5f, 11f, 7.5f, strokePaint)
                    canvas.drawLine(5f, 10.5f, 9.5f, 10.5f, strokePaint)
                }
                IconType.PIN -> {
                    // Upright classic pushpin outline matching approved secondary toolbar
                    canvas.drawLine(4.8f, 2.3f, 11.2f, 2.3f, strokePaint)
                    val body = Path().apply {
                        moveTo(5.7f, 2.3f)
                        lineTo(5.7f, 7.4f)
                        lineTo(3.8f, 9.9f)
                        lineTo(12.2f, 9.9f)
                        lineTo(10.3f, 7.4f)
                        lineTo(10.3f, 2.3f)
                    }
                    canvas.drawPath(body, strokePaint)
                    canvas.drawLine(8f, 9.9f, 8f, 14.1f, strokePaint)
                }
                IconType.PIN_FILLED -> {
                    val body = Path().apply {
                        moveTo(4.9f, 2.3f)
                        lineTo(11.1f, 2.3f)
                        lineTo(10.3f, 3.6f)
                        lineTo(10.3f, 7.4f)
                        lineTo(12.2f, 9.9f)
                        lineTo(3.8f, 9.9f)
                        lineTo(5.7f, 7.4f)
                        lineTo(5.7f, 3.6f)
                        close()
                    }
                    canvas.drawPath(body, fillPaint)
                    canvas.drawPath(body, strokePaint)
                    canvas.drawLine(8f, 9.9f, 8f, 14.1f, strokePaint)
                }
                IconType.KEYBOARD -> {
                    canvas.drawRoundRect(RectF(2f, 3.5f, 14f, 12.5f), 2f, 2f, strokePaint)
                    canvas.drawCircle(5f, 6.5f, 0.75f, fillPaint)
                    canvas.drawCircle(8f, 6.5f, 0.75f, fillPaint)
                    canvas.drawCircle(11f, 6.5f, 0.75f, fillPaint)
                    canvas.drawLine(5f, 9.5f, 11f, 9.5f, strokePaint)
                }
                IconType.IME_SWITCH -> {
                    canvas.drawCircle(8f, 8f, 5.8f, strokePaint)
                    canvas.drawOval(RectF(5.4f, 2.2f, 10.6f, 13.8f), strokePaint)
                    canvas.drawLine(8f, 2.2f, 8f, 13.8f, strokePaint)
                    canvas.drawLine(2.55f, 6.1f, 13.45f, 6.1f, strokePaint)
                    canvas.drawLine(2.55f, 9.9f, 13.45f, 9.9f, strokePaint)
                }
                IconType.SEARCH -> {
                    canvas.drawCircle(6.7f, 6.7f, 4.4f, strokePaint)
                    canvas.drawLine(9.9f, 9.9f, 13.8f, 13.8f, strokePaint)
                }
                IconType.INSERT -> {
                    val p = Path().apply {
                        moveTo(13.5f, 4.5f)
                        lineTo(13.5f, 9.5f)
                        lineTo(4.5f, 9.5f)
                        moveTo(7.5f, 6.5f)
                        lineTo(4.5f, 9.5f)
                        lineTo(7.5f, 12.5f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.EDIT -> {
                    val p = Path().apply {
                        moveTo(12.5f, 2.5f)
                        lineTo(14f, 4f)
                        lineTo(6f, 12f)
                        lineTo(3.5f, 13f)
                        lineTo(4.5f, 10.5f)
                        close()
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.DELETE -> {
                    canvas.drawLine(3f, 4.5f, 13f, 4.5f, strokePaint)
                    canvas.drawLine(6.5f, 2.5f, 9.5f, 2.5f, strokePaint)
                    val p = Path().apply {
                        moveTo(4.5f, 4.5f)
                        lineTo(5.5f, 13.5f)
                        lineTo(10.5f, 13.5f)
                        lineTo(11.5f, 4.5f)
                    }
                    canvas.drawPath(p, strokePaint)
                    canvas.drawLine(7f, 7f, 7f, 11f, strokePaint)
                    canvas.drawLine(9f, 7f, 9f, 11f, strokePaint)
                }
                IconType.CHEVRON_DOWN -> {
                    val p = Path().apply {
                        moveTo(3.5f, 6f)
                        lineTo(8f, 10.5f)
                        lineTo(12.5f, 6f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.CHEVRON_UP -> {
                    val p = Path().apply {
                        moveTo(3.5f, 10.5f)
                        lineTo(8f, 6f)
                        lineTo(12.5f, 10.5f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.CHEVRON_LEFT -> {
                    val p = Path().apply {
                        moveTo(10f, 3.5f)
                        lineTo(5.5f, 8f)
                        lineTo(10f, 12.5f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.CHEVRON_RIGHT -> {
                    val p = Path().apply {
                        moveTo(6f, 3.5f)
                        lineTo(10.5f, 8f)
                        lineTo(6f, 12.5f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.ARROW_LEFT -> {
                    val p = Path().apply {
                        moveTo(12.5f, 8f)
                        lineTo(3.5f, 8f)
                        moveTo(7.5f, 4f)
                        lineTo(3.5f, 8f)
                        lineTo(7.5f, 12f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.CLOSE -> {
                    canvas.drawLine(4f, 4f, 12f, 12f, strokePaint)
                    canvas.drawLine(12f, 4f, 4f, 12f, strokePaint)
                }
                IconType.SETTINGS -> {
                    val gear = Path().apply {
                        fillType = Path.FillType.EVEN_ODD
                        val rOut = 6.7f
                        val rIn = 4.95f
                        for (i in 0 until 8) {
                            val base = i * (Math.PI / 4.0)
                            val a1 = base - 0.25
                            val a2 = base - 0.13
                            val a3 = base + 0.13
                            val a4 = base + 0.25
                            val x1 = 8f + (rIn * Math.cos(a1)).toFloat()
                            val y1 = 8f + (rIn * Math.sin(a1)).toFloat()
                            val x2 = 8f + (rOut * Math.cos(a2)).toFloat()
                            val y2 = 8f + (rOut * Math.sin(a2)).toFloat()
                            val x3 = 8f + (rOut * Math.cos(a3)).toFloat()
                            val y3 = 8f + (rOut * Math.sin(a3)).toFloat()
                            val x4 = 8f + (rIn * Math.cos(a4)).toFloat()
                            val y4 = 8f + (rIn * Math.sin(a4)).toFloat()
                            if (i == 0) moveTo(x1, y1) else lineTo(x1, y1)
                            lineTo(x2, y2)
                            lineTo(x3, y3)
                            lineTo(x4, y4)
                        }
                        close()
                        addCircle(8f, 8f, 2.25f, Path.Direction.CW)
                    }
                    canvas.drawPath(gear, fillPaint)
                }
                IconType.LOCK -> {
                    val p = Path().apply {
                        moveTo(5.5f, 7f)
                        lineTo(5.5f, 4.5f)
                        quadTo(5.5f, 2f, 8f, 2f)
                        quadTo(10.5f, 2f, 10.5f, 4.5f)
                        lineTo(10.5f, 7f)
                    }
                    canvas.drawPath(p, strokePaint)
                    canvas.drawRoundRect(RectF(4f, 7f, 12f, 14f), 1.5f, 1.5f, strokePaint)
                }
                IconType.SHIFT -> {
                    val p = Path().apply {
                        moveTo(8f, 2.5f)
                        lineTo(13.2f, 8.2f)
                        lineTo(10.2f, 8.2f)
                        lineTo(10.2f, 13.2f)
                        lineTo(5.8f, 13.2f)
                        lineTo(5.8f, 8.2f)
                        lineTo(2.8f, 8.2f)
                        close()
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.SHIFT_ACTIVE -> {
                    val p = Path().apply {
                        moveTo(8f, 2.5f)
                        lineTo(13.2f, 8.2f)
                        lineTo(10.2f, 8.2f)
                        lineTo(10.2f, 13.2f)
                        lineTo(5.8f, 13.2f)
                        lineTo(5.8f, 8.2f)
                        lineTo(2.8f, 8.2f)
                        close()
                    }
                    canvas.drawPath(p, fillPaint)
                    canvas.drawPath(p, strokePaint)
                }
                IconType.CAPS_LOCK -> {
                    val p = Path().apply {
                        moveTo(8f, 2f)
                        lineTo(13.2f, 7.5f)
                        lineTo(10.2f, 7.5f)
                        lineTo(10.2f, 11.5f)
                        lineTo(5.8f, 11.5f)
                        lineTo(5.8f, 7.5f)
                        lineTo(2.8f, 7.5f)
                        close()
                    }
                    canvas.drawPath(p, fillPaint)
                    canvas.drawPath(p, strokePaint)
                    canvas.drawLine(5.4f, 13.8f, 10.6f, 13.8f, strokePaint)
                }
                IconType.BACKSPACE -> {
                    val p = Path().apply {
                        moveTo(5.4f, 3.6f)
                        lineTo(13.5f, 3.6f)
                        quadTo(14.3f, 3.6f, 14.3f, 4.4f)
                        lineTo(14.3f, 11.6f)
                        quadTo(14.3f, 12.4f, 13.5f, 12.4f)
                        lineTo(5.4f, 12.4f)
                        lineTo(1.8f, 8f)
                        close()
                    }
                    canvas.drawPath(p, strokePaint)
                    canvas.drawLine(7.7f, 6.2f, 11.3f, 9.8f, strokePaint)
                    canvas.drawLine(11.3f, 6.2f, 7.7f, 9.8f, strokePaint)
                }
                IconType.ENTER -> {
                    val p = Path().apply {
                        moveTo(12.6f, 4.2f)
                        lineTo(12.6f, 9.2f)
                        lineTo(3.6f, 9.2f)
                        moveTo(6.4f, 6.4f)
                        lineTo(3.6f, 9.2f)
                        lineTo(6.4f, 12f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.SEND -> {
                    val p = Path().apply {
                        moveTo(2.4f, 7.8f)
                        lineTo(13.8f, 2.8f)
                        lineTo(10.8f, 13.4f)
                        lineTo(8.0f, 9.2f)
                        close()
                    }
                    canvas.drawPath(p, strokePaint)
                    canvas.drawLine(13.8f, 2.8f, 8.0f, 9.2f, strokePaint)
                }
                IconType.GO -> {
                    val p = Path().apply {
                        moveTo(2.8f, 8.0f)
                        lineTo(13.2f, 8.0f)
                        moveTo(9.0f, 3.8f)
                        lineTo(13.2f, 8.0f)
                        lineTo(9.0f, 12.2f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.NEXT -> {
                    val p = Path().apply {
                        moveTo(2.4f, 8.0f)
                        lineTo(10.8f, 8.0f)
                        moveTo(7.2f, 4.2f)
                        lineTo(10.8f, 8.0f)
                        lineTo(7.2f, 11.8f)
                        moveTo(13.2f, 4.0f)
                        lineTo(13.2f, 12.0f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.DONE -> {
                    val p = Path().apply {
                        moveTo(2.8f, 8.4f)
                        lineTo(6.4f, 12.0f)
                        lineTo(13.4f, 4.4f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.SPACE -> {
                    val p = Path().apply {
                        moveTo(2.6f, 8.2f)
                        lineTo(2.6f, 11.4f)
                        lineTo(13.4f, 11.4f)
                        lineTo(13.4f, 8.2f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.EMOJI_RECENT -> {
                    canvas.drawCircle(8f, 8f, 5.8f, strokePaint)
                    val hands = Path().apply {
                        moveTo(8f, 4.7f)
                        lineTo(8f, 8.2f)
                        lineTo(10.5f, 9.7f)
                    }
                    canvas.drawPath(hands, strokePaint)
                }
                IconType.EMOJI -> {
                    canvas.drawCircle(8f, 8f, 5.8f, strokePaint)
                    canvas.drawCircle(6.1f, 6.6f, 0.7f, fillPaint)
                    canvas.drawCircle(9.9f, 6.6f, 0.7f, fillPaint)
                    val smile = Path().apply {
                        moveTo(5.6f, 9.5f)
                        quadTo(8f, 11.6f, 10.4f, 9.5f)
                    }
                    canvas.drawPath(smile, strokePaint)
                }
                IconType.EMOJI_PEOPLE -> {
                    canvas.drawCircle(8f, 5.2f, 2.3f, strokePaint)
                    val shoulders = Path().apply {
                        moveTo(3.4f, 13.4f)
                        quadTo(3.8f, 9.6f, 8f, 9.6f)
                        quadTo(12.2f, 9.6f, 12.6f, 13.4f)
                    }
                    canvas.drawPath(shoulders, strokePaint)
                }
                IconType.EMOJI_NATURE -> {
                    val leaf = Path().apply {
                        moveTo(3.5f, 12.5f)
                        quadTo(3.5f, 4.0f, 12.5f, 3.5f)
                        quadTo(12.0f, 12.0f, 3.5f, 12.5f)
                        close()
                        moveTo(3.5f, 12.5f)
                        lineTo(9.5f, 6.5f)
                    }
                    canvas.drawPath(leaf, strokePaint)
                }
                IconType.EMOJI_FOOD -> {
                    val cup = Path().apply {
                        moveTo(3.4f, 6.0f)
                        lineTo(11.0f, 6.0f)
                        lineTo(11.0f, 9.6f)
                        quadTo(11.0f, 12.6f, 7.2f, 12.6f)
                        quadTo(3.4f, 12.6f, 3.4f, 9.6f)
                        close()
                        moveTo(11.0f, 6.8f)
                        quadTo(13.4f, 6.8f, 13.4f, 8.6f)
                        quadTo(13.4f, 10.4f, 11.0f, 10.4f)
                        moveTo(5.8f, 3.0f)
                        lineTo(5.8f, 4.6f)
                        moveTo(8.6f, 3.0f)
                        lineTo(8.6f, 4.6f)
                    }
                    canvas.drawPath(cup, strokePaint)
                }
                IconType.EMOJI_ACTIVITIES -> {
                    canvas.drawCircle(8f, 8f, 5.6f, strokePaint)
                    val seam1 = Path().apply {
                        moveTo(4.2f, 4.2f)
                        quadTo(8f, 7.6f, 4.2f, 11.8f)
                        moveTo(11.8f, 4.2f)
                        quadTo(8f, 7.6f, 11.8f, 11.8f)
                    }
                    canvas.drawPath(seam1, strokePaint)
                }
                IconType.EMOJI_TRAVEL -> {
                    val car = Path().apply {
                        moveTo(2.8f, 9.2f)
                        lineTo(4.4f, 5.4f)
                        lineTo(11.6f, 5.4f)
                        lineTo(13.2f, 9.2f)
                        lineTo(13.2f, 12.0f)
                        lineTo(2.8f, 12.0f)
                        close()
                    }
                    canvas.drawPath(car, strokePaint)
                    canvas.drawCircle(5.2f, 12.2f, 1.1f, fillPaint)
                    canvas.drawCircle(10.8f, 12.2f, 1.1f, fillPaint)
                }
                IconType.EMOJI_OBJECTS -> {
                    val bulb = Path().apply {
                        moveTo(5.8f, 10.4f)
                        quadTo(3.6f, 8.6f, 4.2f, 5.8f)
                        quadTo(5.0f, 2.8f, 8.0f, 2.8f)
                        quadTo(11.0f, 2.8f, 11.8f, 5.8f)
                        quadTo(12.4f, 8.6f, 10.2f, 10.4f)
                        lineTo(10.2f, 12.2f)
                        lineTo(5.8f, 12.2f)
                        close()
                        moveTo(6.4f, 13.6f)
                        lineTo(9.6f, 13.6f)
                    }
                    canvas.drawPath(bulb, strokePaint)
                }
                IconType.EMOJI_SYMBOLS -> {
                    val hash = Path().apply {
                        moveTo(6.0f, 3.0f)
                        lineTo(4.8f, 13.0f)
                        moveTo(11.2f, 3.0f)
                        lineTo(10.0f, 13.0f)
                        moveTo(3.0f, 6.2f)
                        lineTo(13.0f, 6.2f)
                        moveTo(2.6f, 9.8f)
                        lineTo(12.6f, 9.8f)
                    }
                    canvas.drawPath(hash, strokePaint)
                }
                IconType.MERGE -> {
                    val p = Path().apply {
                        moveTo(3.5f, 3.5f)
                        lineTo(8f, 7.5f)
                        lineTo(8f, 13f)
                        moveTo(12.5f, 3.5f)
                        lineTo(8f, 7.5f)
                        moveTo(6f, 11f)
                        lineTo(8f, 13f)
                        lineTo(10f, 11f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.SPLIT -> {
                    canvas.drawRoundRect(RectF(2.5f, 3.5f, 13.5f, 12.5f), 1.5f, 1.5f, strokePaint)
                    canvas.drawLine(8f, 3.5f, 8f, 12.5f, strokePaint)
                    val leftArrow = Path().apply {
                        moveTo(5.5f, 6.5f)
                        lineTo(4f, 8f)
                        lineTo(5.5f, 9.5f)
                    }
                    canvas.drawPath(leftArrow, strokePaint)
                    val rightArrow = Path().apply {
                        moveTo(10.5f, 6.5f)
                        lineTo(12f, 8f)
                        lineTo(10.5f, 9.5f)
                    }
                    canvas.drawPath(rightArrow, strokePaint)
                }
                IconType.UNDO -> {
                    val p = Path().apply {
                        moveTo(12.5f, 12f)
                        quadTo(12.5f, 4.5f, 5.5f, 4.5f)
                        lineTo(7.5f, 2.5f)
                        moveTo(5.5f, 4.5f)
                        lineTo(7.5f, 6.5f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
                IconType.REDO -> {
                    val p = Path().apply {
                        moveTo(3.5f, 12f)
                        quadTo(3.5f, 4.5f, 10.5f, 4.5f)
                        lineTo(8.5f, 2.5f)
                        moveTo(10.5f, 4.5f)
                        lineTo(8.5f, 6.5f)
                    }
                    canvas.drawPath(p, strokePaint)
                }
            }
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            strokePaint.alpha = alpha
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            strokePaint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = dp(sizeDp).toInt()
        override fun getIntrinsicHeight(): Int = dp(sizeDp).toInt()
    }

    private fun Button.setIcon(icon: IconDrawable, paddingDp: Float = 3f) {
        icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
        setCompoundDrawables(icon, null, null, null)
        compoundDrawablePadding = dp(paddingDp).toInt()
    }

    private fun TextView.setIcon(icon: IconDrawable, paddingDp: Float = 3f) {
        icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
        setCompoundDrawables(icon, null, null, null)
        compoundDrawablePadding = dp(paddingDp).toInt()
    }

    private fun createIconView(icon: IconDrawable): ImageView = ImageView(this).apply {
        setImageDrawable(icon)
        layoutParams = ViewGroup.LayoutParams(icon.intrinsicWidth, icon.intrinsicHeight)
    }
}
