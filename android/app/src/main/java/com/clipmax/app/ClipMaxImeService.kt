package com.clipmax.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
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
import android.util.LruCache
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
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.io.Reader
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
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
    private fun daemonThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    private fun submitSafely(executor: ExecutorService, task: () -> Unit): Boolean {
        if (serviceDestroyed || executor.isShutdown) return false
        return try {
            executor.execute {
                if (!serviceDestroyed) {
                    task()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun submitFutureSafely(executor: ExecutorService, task: () -> Unit): Future<*>? {
        if (serviceDestroyed || executor.isShutdown) return null
        return try {
            executor.submit {
                if (!serviceDestroyed) {
                    task()
                }
            }
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory("ClipMax-IME-IO"))
    private val inserter: ExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory("ClipMax-IME-Insert"))
    private val suggestExecutor: ExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory("ClipMax-IME-Suggest"))
    private val icQueryExecutor: ExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory("ClipMax-IME-IC"))
    @Volatile private var generation = 0
    private var listRequest = 0
    @Volatile private var lastLoadedStoreVersion = -1L
    private var lastRenderedKeyboardRowsSig = ""
    private var lastRenderedClipChipsSig = ""
    private var lastRenderedPasteCapsuleSig = ""
    private val shadowEditor = ImeInputPipeline.EditorShadowBuffer()
    private var isPasswordTarget = false
    private var fieldCapabilities = InputFieldPolicy.evaluate(InputType.TYPE_CLASS_TEXT, 0)
    private var currentEditorTarget = InsertionHistoryManager.EditorTarget(null, 0, 0, 0L)
    private var editorSessionId = 0L
    private var lastEditorPackage: String? = null
    private var lastEditorFieldId: Int = 0
    private var lastNonZeroEditorFieldId: Int = 0
    private var lastEditorInputType: Int = 0
    private var lastNonZeroEditorInputClass: Int = 0
    private var pasteHistoryVerifyToken = 0
    private var pasteRestartVerifyToken = 0
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
    private var currentMediaSubMode = MediaKeyboardSpec.MediaSubMode.EMOJI
    private var isMediaSearchTyping = false
    private var mediaSearchQuery = ""
    private var mediaSearchInputText: TextView? = null
    private var mediaSearchClearBtn: View? = null
    private var mediaSearchResultsStrip: LinearLayout? = null
    private var selectedEmojiCategoryIdx = -1
    private var emojiPickerRoot: FrameLayout? = null
    private var emojiRemoveOverlay: View? = null
    private var emojiScrollView: android.widget.ScrollView? = null
    private var emojiTabsScrollView: HorizontalScrollView? = null
    private var emojiGridContainer: LinearLayout? = null
    private var emojiRecentSectionContainer: LinearLayout? = null
    private val emojiSectionViews = mutableListOf<View>()
    private val emojiTabViews = mutableListOf<Pair<Int, FrameLayout>>()
    private val emojiScrollTabController = EmojiInterfaceSpec.ContinuousScrollTabController()

    private var selectedStickerSectionIdx = 2
    private var isStickerPackManagerOpen = false
    private var stickerStoreHost: LinearLayout? = null
    private var stickerScrollView: android.widget.ScrollView? = null
    private var stickerTabsScrollView: HorizontalScrollView? = null
    private val stickerSectionViews = mutableListOf<View>()
    private val stickerTabViews = mutableListOf<Pair<Int, FrameLayout>>()
    private val stickerScrollTabController = EmojiInterfaceSpec.ContinuousScrollTabController(2)

    private var selectedKaomojiCategoryIdx = 1
    private var kaomojiScrollView: android.widget.ScrollView? = null
    private var kaomojiTabsScrollView: HorizontalScrollView? = null
    private var kaomojiRecentTabView: FrameLayout? = null
    private val kaomojiSectionViews = mutableListOf<View>()
    private val kaomojiTabViews = mutableListOf<Pair<Int, TextView>>()
    private val kaomojiScrollTabController = EmojiInterfaceSpec.ContinuousScrollTabController(1)

    private var mediaViewportGen = 0
    private val mediaIoExecutor: ExecutorService = Executors.newFixedThreadPool(2, daemonThreadFactory("ClipMax-Media"))
    private val stickerBitmapCache = LruCache<String, Bitmap>(64)
    private val mediaCacheManager: MediaCacheManager by lazy { MediaCacheManager(this) }
    private val mediaPreferences: SharedPreferences by lazy {
        getSharedPreferences(MediaKeyboardSpec.PREF_MEDIA_FILE, Context.MODE_PRIVATE)
    }
    private val stickerPackManager: StickerPackManager by lazy {
        StickerPackManager(this, mediaPreferences)
    }
    private val recentStickerHistory: MediaKeyboardSpec.RecentStickerHistory by lazy {
        val raw = runCatching { mediaPreferences.getString(MediaKeyboardSpec.PREF_RECENT_STICKERS, null) }.getOrNull()
        MediaKeyboardSpec.RecentStickerHistory(MediaKeyboardSpec.RecentStickerHistory.decode(raw))
    }
    private val favoriteStickers: MediaKeyboardSpec.StickerFavoritesHistory by lazy {
        val raw = runCatching { mediaPreferences.getString(MediaKeyboardSpec.PREF_FAVORITE_STICKERS, null) }.getOrNull()
        MediaKeyboardSpec.StickerFavoritesHistory(MediaKeyboardSpec.StickerFavoritesHistory.decode(raw))
    }
    private val recentKaomojiHistory: MediaKeyboardSpec.RecentKaomojiHistory by lazy {
        val raw = runCatching { mediaPreferences.getString(MediaKeyboardSpec.PREF_RECENT_KAOMOJI, null) }.getOrNull()
        MediaKeyboardSpec.RecentKaomojiHistory(MediaKeyboardSpec.RecentKaomojiHistory.decode(raw))
    }

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
    private var variantPopupHost: View? = null
    private var variantPopupInteractiveTapMode = false
    private var languagePopupHost: View? = null

    private val themePreferences: SharedPreferences by lazy {
        getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
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

    private var lastRenderedThemeKey: String = ""
    @Volatile private var prefsCached = false
    @Volatile private var cachedAppTheme: String = "system"
    @Volatile private var cachedAppUiLocale: String = SettingsPanelSpec.UI_LOCALE_ENGLISH
    @Volatile private var cachedKeyboardLanguage: String = SettingsPanelSpec.LANG_ENGLISH
    @Volatile private var cachedKeyboardHeight: String = "normal"
    @Volatile private var cachedOneHandedMode: String = "off"
    @Volatile private var cachedAutocompleteEnabled: Boolean = true
    @Volatile private var cachedAutoCorrectEnabled: Boolean = true
    @Volatile private var cachedSuggestionsEnabled: Boolean = true
    @Volatile private var cachedThemePalette: KeyboardLayoutSpec.ImeThemePalette? = null

    private fun ensurePrefsCached() {
        if (prefsCached) return
        refreshCachedPreferences()
    }

    private fun refreshCachedPreferences() {
        val prefs = themePreferences
        cachedAppTheme = SettingsPanelSpec.normalizeThemeValue(
            runCatching { prefs.getString(SettingsPanelSpec.PREF_APP_THEME, "system") }.getOrNull()
        )
        cachedAppUiLocale = SettingsPanelSpec.normalizeAppUiLocale(
            runCatching { prefs.getString(SettingsPanelSpec.PREF_APP_UI_LOCALE, SettingsPanelSpec.UI_LOCALE_ENGLISH) }.getOrNull()
        )
        cachedKeyboardLanguage = SettingsPanelSpec.normalizeLanguageValue(
            runCatching { prefs.getString(SettingsPanelSpec.PREF_KEYBOARD_LANGUAGE, SettingsPanelSpec.LANG_ENGLISH) }.getOrNull()
        )
        cachedKeyboardHeight = runCatching {
            prefs.getString(SettingsPanelSpec.PREF_KEYBOARD_HEIGHT, "normal")
        }.getOrNull() ?: "normal"
        cachedOneHandedMode = runCatching {
            prefs.getString(SettingsPanelSpec.PREF_ONE_HANDED, "off")
        }.getOrNull() ?: "off"
        cachedAutocompleteEnabled = runCatching { prefs.getBoolean("typing_autocomplete", true) }.getOrDefault(true)
        cachedAutoCorrectEnabled = runCatching { prefs.getBoolean("typing_autocorrect", true) }.getOrDefault(true)
        cachedSuggestionsEnabled = runCatching { prefs.getBoolean("typing_suggestions", true) }.getOrDefault(true)
        cachedThemePalette = KeyboardLayoutSpec.resolveThemePalette(cachedAppTheme, isSystemNightMode())
        prefsCached = true
    }

    private fun refreshCachedThemePalette() {
        ensurePrefsCached()
        cachedThemePalette = KeyboardLayoutSpec.resolveThemePalette(cachedAppTheme, isSystemNightMode())
    }

    private fun currentHeightPref(): String {
        ensurePrefsCached()
        return cachedKeyboardHeight
    }

    private fun currentOneHandedMode(): String {
        ensurePrefsCached()
        return cachedOneHandedMode
    }

    private val appTheme: String
        get() {
            ensurePrefsCached()
            return cachedAppTheme
        }

    private val appUiLocale: String
        get() {
            ensurePrefsCached()
            return cachedAppUiLocale
        }

    private val isUiRtl: Boolean
        get() = AppUiStrings.isRtl(keyboardLanguage)

    private val uiLayoutDirection: Int
        get() = if (isUiRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    private val keyboardLanguage: String
        get() {
            ensurePrefsCached()
            return cachedKeyboardLanguage
        }

    private fun isSystemNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun computeThemeStateKey(): String =
        "${appTheme}:${keyboardLanguage}:${isNightMode}:${isClassicTheme}:${cachedOneHandedMode}:${cachedKeyboardHeight}"

    private val currentThemePalette: KeyboardLayoutSpec.ImeThemePalette
        get() {
            ensurePrefsCached()
            return cachedThemePalette ?: KeyboardLayoutSpec.resolveThemePalette(cachedAppTheme, isSystemNightMode()).also {
                cachedThemePalette = it
            }
        }

    private val isClassicTheme: Boolean
        get() = currentThemePalette.isClassicTheme

    private val isAutocompleteEnabled: Boolean
        get() {
            ensurePrefsCached()
            return cachedAutocompleteEnabled
        }

    private val isAutoCorrectEnabled: Boolean
        get() {
            ensurePrefsCached()
            return cachedAutoCorrectEnabled
        }

    private val isSuggestionsEnabled: Boolean
        get() {
            ensurePrefsCached()
            return cachedSuggestionsEnabled
        }

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
            val nextMode = CapturePreferences.mode(capturePreferences)
            if (nextMode != captureMode) {
                captureMode = nextMode
                updateCaptureMonitoring()
                main.post {
                    if (!serviceDestroyed) {
                        updateCaptureStatus()
                        updateClipboardCaptureToggleUI()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed = false
        refreshCachedPreferences()
        store = ClipStore(filesDir)
        ClipStore.addChangeListener(storeChangeListener)
        capturePreferences = CapturePreferences.preferences(this)
        captureMode = CapturePreferences.mode(capturePreferences)
        capturePreferences.registerOnSharedPreferenceChangeListener(capturePreferenceListener)

        // Observe in-app theme, UI locale, keyboard language & typing preference changes in real-time
        themePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                SettingsPanelSpec.PREF_APP_THEME -> main.post {
                    if (serviceDestroyed) return@post
                    cachedAppTheme = SettingsPanelSpec.normalizeThemeValue(
                        runCatching { prefs.getString(SettingsPanelSpec.PREF_APP_THEME, "system") }.getOrNull()
                    )
                    refreshCachedThemePalette()
                    if (computeThemeStateKey() != lastRenderedThemeKey) {
                        window?.window?.let { applySystemNavBarTheme(it) }
                        renderFullImeView()
                    }
                }
                SettingsPanelSpec.PREF_APP_UI_LOCALE -> main.post {
                    if (serviceDestroyed) return@post
                    cachedAppUiLocale = SettingsPanelSpec.normalizeAppUiLocale(
                        runCatching { prefs.getString(SettingsPanelSpec.PREF_APP_UI_LOCALE, SettingsPanelSpec.UI_LOCALE_ENGLISH) }.getOrNull()
                    )
                    onAppUiLocalePreferenceChanged()
                }
                SettingsPanelSpec.PREF_KEYBOARD_LANGUAGE -> main.post {
                    if (serviceDestroyed) return@post
                    cachedKeyboardLanguage = SettingsPanelSpec.normalizeLanguageValue(
                        runCatching { prefs.getString(SettingsPanelSpec.PREF_KEYBOARD_LANGUAGE, SettingsPanelSpec.LANG_ENGLISH) }.getOrNull()
                    )
                    onKeyboardLanguagePreferenceChanged()
                }
                SettingsPanelSpec.PREF_KEYBOARD_HEIGHT -> main.post {
                    if (serviceDestroyed) return@post
                    cachedKeyboardHeight = runCatching {
                        prefs.getString(SettingsPanelSpec.PREF_KEYBOARD_HEIGHT, "normal")
                    }.getOrNull() ?: "normal"
                    applyCanonicalViewportHeightInPlace()
                }
                SettingsPanelSpec.PREF_ONE_HANDED -> main.post {
                    if (serviceDestroyed) return@post
                    cachedOneHandedMode = runCatching {
                        prefs.getString(SettingsPanelSpec.PREF_ONE_HANDED, "off")
                    }.getOrNull() ?: "off"
                    applyOneHandedPadding()
                }
                "typing_autocomplete" -> main.post {
                    if (serviceDestroyed) return@post
                    cachedAutocompleteEnabled = runCatching { prefs.getBoolean("typing_autocomplete", true) }.getOrDefault(true)
                    onTypingSettingsChanged()
                }
                "typing_autocorrect" -> main.post {
                    if (serviceDestroyed) return@post
                    cachedAutoCorrectEnabled = runCatching { prefs.getBoolean("typing_autocorrect", true) }.getOrDefault(true)
                    onTypingSettingsChanged()
                }
                "typing_suggestions" -> main.post {
                    if (serviceDestroyed) return@post
                    cachedSuggestionsEnabled = runCatching { prefs.getBoolean("typing_suggestions", true) }.getOrDefault(true)
                    onTypingSettingsChanged()
                }
            }
        }
        themePreferences.registerOnSharedPreferenceChangeListener(themePreferenceListener)

        // Load bundled local-only English dictionary off the main UI thread
        submitSafely(io) {
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

    private fun applySystemNavBarTheme(win: android.view.Window) {
        win.navigationBarColor = colorBg
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
        }
        val lightNav = !isNightMode
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            win.insetsController?.setSystemBarsAppearance(
                if (lightNav) android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val vis = win.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = if (lightNav) {
                vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        applySystemNavBarTheme(win)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        window?.window?.let { applySystemNavBarTheme(it) }
        if (imeRootFrame != null && computeThemeStateKey() != lastRenderedThemeKey) {
            renderFullImeView()
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
                    val langHost = languagePopupHost
                    if (langHost != null && langHost.visibility == View.VISIBLE && langHost.parent === this) {
                        val rect = android.graphics.Rect()
                        langHost.getHitRect(rect)
                        if (!rect.contains(ev.x.toInt(), ev.y.toInt())) {
                            dismissLanguagePopup()
                            return true
                        }
                    }
                    val varHost = variantPopupHost
                    if (varHost != null && variantPopupInteractiveTapMode && varHost.visibility == View.VISIBLE && varHost.parent === this) {
                        val rect = android.graphics.Rect()
                        varHost.getHitRect(rect)
                        if (!rect.contains(ev.x.toInt(), ev.y.toInt())) {
                            dismissVariantPopup()
                            return true
                        }
                    }
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
        val oneHanded = currentOneHandedMode()
        val (padLeft, padRight) = when (oneHanded) {
            "left" -> 0 to dp(54)
            "right" -> dp(54) to 0
            else -> 0 to 0
        }
        content.setPadding(padLeft, 0, padRight, 0)
    }

    private fun renderFullImeView() {
        val content = contentContainer ?: return
        refreshCachedThemePalette()
        lastRenderedThemeKey = computeThemeStateKey()
        lastRenderedKeyboardRowsSig = ""
        lastRenderedClipChipsSig = ""
        lastRenderedPasteCapsuleSig = ""
        dismissLanguagePopup()
        dismissVariantPopup()
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
        mediaViewportGen++
        emojiRemoveOverlay = null
        emojiPickerRoot = null
        emojiScrollView = null
        emojiTabsScrollView = null
        emojiGridContainer = null
        emojiRecentSectionContainer = null
        emojiSectionViews.clear()
        emojiTabViews.clear()
        stickerScrollView = null
        stickerTabsScrollView = null
        stickerSectionViews.clear()
        stickerTabViews.clear()
        kaomojiScrollView = null
        kaomojiTabsScrollView = null
        kaomojiRecentTabView = null
        kaomojiSectionViews.clear()
        kaomojiTabViews.clear()
        mediaSearchInputText = null
        mediaSearchClearBtn = null
        mediaSearchResultsStrip = null
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
            // 3C. Live Search Clips Bar or Media Search Bar above keyboard
            if (isEmojiPickerOpen && isMediaSearchTyping) {
                viewportContainer.addView(buildMediaSearchInterface(viewport))
            } else if (isFilterMode) {
                viewportContainer.addView(buildSearchInterface(viewport))
            }

            if (isEmojiPickerOpen && !isMediaSearchTyping) {
                // Unified 3-Mode Native Media Keyboard (Emoji | Stickers | Kaomoji)
                viewportContainer.addView(buildMediaPickerPanel())
                if (isFilterMode) {
                    renderSearchResults()
                }
                renderClipChips()
            } else {
                // 4. Approved 4-row Ergonomic QWERTY / Numeric Keyboard Container
                val geom = refreshKeyboardGeometry()
                syncAutoCapitalization(preserveManualShift = true)

                val wrapper = FrameLayout(this).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
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
                if (isEmojiPickerOpen && isMediaSearchTyping) {
                    renderMediaSearchStripResults()
                } else if (isFilterMode) {
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
            layoutDirection = uiLayoutDirection
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
        val slideOffset = if (isUiRtl) -w else w

        viewA.animate().withEndAction(null).cancel()
        viewB.animate().withEndAction(null).cancel()

        if (targetState == 1) {
            viewB.translationX = slideOffset
            viewB.alpha = 1f
            viewB.visibility = View.VISIBLE

            viewA.animate()
                .translationX(-slideOffset)
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
            viewA.translationX = -slideOffset
            viewA.alpha = 1f
            viewA.visibility = View.VISIBLE

            viewB.animate()
                .translationX(slideOffset)
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
        layoutDirection = uiLayoutDirection
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        val vPad = dp(OverlayUiSpec.TOOLBAR_VERTICAL_PADDING_DP).toInt()
        setPadding(dp(6), vPad, dp(6), vPad)
        setBackgroundColor(colorToolbarBg)

        // 1. Start-side Clipboard icon button (switches back from Suggestions to Clipboard Pills, or opens Clipboard Panel)
        val clipIconBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(colorChipBg, dp(17f), colorChipBorder)
            contentDescription = AppUiStrings.clipboardHistoryA11y(keyboardLanguage)
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
                    isMediaSearchTyping = false
                    isStickerPackManagerOpen = false
                    isSettingsOpen = false
                    settingsSavedScrollY = 0
                    isClipboardPanelOpen = !isClipboardPanelOpen
                    clipboardSavedScrollY = 0
                    renderFullImeView()
                }
            }
        }
        addView(clipIconBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginEnd = dp(6)
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
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(2), 0, dp(16), 0)
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
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(2), 0, dp(8), 0)
            }
            suggestionsContainer = container
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        suggestionScrollView = suggestScroll
        centerFrame.addView(suggestScroll)
        renderSuggestionStrip()
        updateToolbarCenterVisibility()

        addView(centerFrame)

        // 3. Circular navigation chevron button at end edge
        val tbPalette = SecondaryToolbarSpec.resolvePalette(isNightMode, isClassicTheme)
        val rightArrowBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(tbPalette.backCircleBg, dp(17f), 0, tbPalette.rippleColor)
            contentDescription = AppUiStrings.moreActionsA11y(keyboardLanguage)
            val chevron = if (isUiRtl) IconType.CHEVRON_LEFT else IconType.CHEVRON_RIGHT
            val iv = createIconView(IconDrawable(chevron, tbPalette.backIconFg, 14.5f))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                switchToolbarState(1)
            }
        }
        addView(rightArrowBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginStart = dp(6)
            marginEnd = dp(2)
        })
    }

    private fun buildToolbarStateB(): View = FrameLayout(this).apply {
        layoutDirection = uiLayoutDirection
        val tbPalette = SecondaryToolbarSpec.resolvePalette(isNightMode, isClassicTheme)
        setBackgroundColor(tbPalette.toolbarBg)

        val row = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
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

        // 1. Circular Back Button (Start edge, mirrors direction in RTL)
        val backBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(tbPalette.backCircleBg, endRadius, 0, tbPalette.rippleColor)
            contentDescription = AppUiStrings.backToSnippetsA11y(keyboardLanguage)
            val backChevron = if (isUiRtl) IconType.CHEVRON_RIGHT else IconType.CHEVRON_LEFT
            val iv = createIconView(
                IconDrawable(
                    backChevron,
                    tbPalette.backIconFg,
                    SecondaryToolbarSpec.BACK_ICON_SIZE_DP
                )
            )
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                dismissPasteUndoCapsule()
                val wasPanelOpen = isSettingsOpen || isClipboardPanelOpen || isFilterMode || pinnedOnly || isEmojiPickerOpen || isMediaSearchTyping || isStickerPackManagerOpen
                val wasFilterMode = isFilterMode
                toolbarState = 0
                isSettingsOpen = false
                isClipboardPanelOpen = false
                isEmojiPickerOpen = false
                isMediaSearchTyping = false
                isStickerPackManagerOpen = false
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
            marginEnd = dp(4)
        })

        // Central icon-only actions container: Pinned, Search Clips, Settings evenly distributed
        val actionsContainer = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
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
            AppUiStrings.pinnedA11y(keyboardLanguage)
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
                isMediaSearchTyping = false
                isStickerPackManagerOpen = false
                clipboardSavedScrollY = 0
                isSettingsOpen = false
                settingsSavedScrollY = 0
                isFilterMode = false
                keyboardSearchQuery = ""
                keyboardSearchResults = emptyList()
            }
            renderFullImeView()
            reloadItems()
        }
        actionsContainer.addView(pinBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // 3. Search Clips (icon-only, no persistent rectangular card)
        val searchBtn = makeStateBIconAction(
            IconType.SEARCH,
            AppUiStrings.searchClipsA11y(keyboardLanguage)
        ) {
            dismissPasteUndoCapsule()
            if (isPasswordTarget) {
                setStatus(AppUiStrings.passwordSearchDisabledDot(keyboardLanguage))
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
                isMediaSearchTyping = false
                isStickerPackManagerOpen = false
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
            AppUiStrings.settingsA11y(keyboardLanguage)
        ) {
            dismissPasteUndoCapsule()
            isSettingsOpen = !isSettingsOpen
            settingsSavedScrollY = 0
            isClipboardPanelOpen = false
            isEmojiPickerOpen = false
            isMediaSearchTyping = false
            isStickerPackManagerOpen = false
            clipboardSavedScrollY = 0
            isFilterMode = false
            pinnedOnly = false
            renderFullImeView()
        }
        actionsContainer.addView(settingsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        row.addView(actionsContainer)

        // 5. Circular Globe/Language button (end edge)
        val imeSwitchBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(tbPalette.globeCircleBg, endRadius, tbPalette.globeCircleBorder, tbPalette.rippleColor)
            contentDescription = AppUiStrings.switchKeyboardA11y(keyboardLanguage)
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
            marginStart = dp(4)
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
        val capsuleWidthDp = if (isUiRtl) 96 else PasteUndoCapsuleSpec.WIDTH_DP.toInt()
        val host = FrameLayout(this).apply {
            layoutDirection = uiLayoutDirection
            val lp = FrameLayout.LayoutParams(
                dp(capsuleWidthDp),
                dp(PasteUndoCapsuleSpec.HEIGHT_DP.toInt())
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dp(PasteUndoCapsuleSpec.RIGHT_MARGIN_DP.toInt())
            }
            layoutParams = lp
            visibility = View.GONE
            isClickable = false
            clipChildren = false
        }
        pasteUndoCapsuleHost = host

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val capsuleBg = if (isNightMode) {
                if (isClassicTheme) 0xFF1F2024.toInt() else colorChipBg
            } else {
                0xFFFFFFFF.toInt()
            }
            val capsuleBorder = if (isNightMode) 0xD9FFFFFF.toInt() else 0xFF202124.toInt()
            val capsuleFg = if (isNightMode) {
                if (isClassicTheme) colorKeyText else colorAltPrimaryText
            } else {
                0xFF202124.toInt()
            }
            background = roundedRipple(capsuleBg, dp(PasteUndoCapsuleSpec.CORNER_RADIUS_DP), capsuleBorder)

            val iv = ImageView(this@ClipMaxImeService).apply {
                setImageDrawable(IconDrawable(IconType.UNDO, capsuleFg, PasteUndoCapsuleSpec.ICON_SIZE_DP))
            }
            pasteUndoCapsuleIcon = iv
            addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val tv = TextView(this@ClipMaxImeService).apply {
                text = AppUiStrings.undoLabel(keyboardLanguage)
                textSize = PasteUndoCapsuleSpec.TEXT_SIZE_SP
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(capsuleFg)
                setSingleLine(true)
            }
            pasteUndoCapsuleText = tv
            val tvLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(5)
            }
            addView(tv, tvLp)

            setOnClickListener {
                if (!pasteCapsuleController.isActive || pasteCapsuleController.isPendingConfirmation) return@setOnClickListener
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
            if (host.visibility != View.GONE) {
                host.visibility = View.GONE
                host.isClickable = false
                btn?.isClickable = false
            }
            lastRenderedPasteCapsuleSig = "HIDDEN"
            return
        }

        val iconType = if (config.action == PasteUndoCapsuleSpec.CapsuleAction.REDO) IconType.REDO else IconType.UNDO
        val capsuleFg = if (isNightMode) {
            if (isClassicTheme) colorKeyText else colorAltPrimaryText
        } else {
            0xFF202124.toInt()
        }
        val label = AppUiStrings.pasteCapsuleLabel(keyboardLanguage, config.action)
        val sig = ImeInputPipeline.ViewSignatureSpec.pasteCapsuleUiSignature(config, toolbarState, label, capsuleFg)
        if (host.visibility == View.VISIBLE && sig == lastRenderedPasteCapsuleSig) {
            return
        }
        lastRenderedPasteCapsuleSig = sig

        host.visibility = View.VISIBLE
        host.bringToFront()
        btn?.isClickable = true
        btn?.isEnabled = true
        pasteUndoCapsuleIcon?.setImageDrawable(IconDrawable(iconType, capsuleFg, PasteUndoCapsuleSpec.ICON_SIZE_DP))
        pasteUndoCapsuleText?.text = label
        btn?.contentDescription = AppUiStrings.pasteCapsuleA11y(keyboardLanguage, config.action)
    }

    private fun dismissPasteUndoCapsule() {
        if (!pasteCapsuleController.isActive && !pasteCapsuleController.isRedoMode) return
        pasteCapsuleController.dismiss()
        updatePasteUndoCapsuleUI()
    }

    private fun buildSettingsPanel(): View = LinearLayout(this).apply {
        val pillPalette = SettingsPanelSpec.resolvePillCardPalette(currentThemePalette)
        orientation = LinearLayout.VERTICAL
        layoutDirection = uiLayoutDirection
        setBackgroundColor(pillPalette.panelBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        // Header (36dp preserved)
        val header = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(12), dp(4), dp(8), dp(4))
            setBackgroundColor(pillPalette.headerBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(SettingsPanelSpec.HEADER_HEIGHT_DP.toInt())
            )

            val icon = createIconView(IconDrawable(IconType.SETTINGS, colorModText, 14f)).apply {
                setPaddingRelative(0, 0, dp(6), 0)
            }
            addView(icon)

            val title = TextView(this@ClipMaxImeService).apply {
                text = AppUiStrings.settingsHeaderTitle(keyboardLanguage)
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(pillPalette.sectionTitleFg)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val closeBtn = TextView(this@ClipMaxImeService).apply {
                text = AppUiStrings.closeButton(keyboardLanguage)
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
            layoutDirection = uiLayoutDirection
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                settingsSavedScrollY = scrollY
            }
        }
        settingsScrollView = scroll

        val list = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
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
                layoutDirection = uiLayoutDirection
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
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            card.addView(lbl, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            })

            val row = LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
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
                    if (idx < options.size - 1) marginEnd = pillGapPx
                })
            }
            refreshSelection(initialSelected)

            card.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            list.addView(card)
        }

        // 0. App Language (strictly independent of Keyboard Language)
        addSegmentedSettingCard(
            title = AppUiStrings.appLanguageSettingTitle(keyboardLanguage),
            options = AppUiStrings.appUiLanguageOptions(keyboardLanguage),
            initialSelected = keyboardLanguage
        ) { nextLocale ->
            setAppUiLocale(nextLocale)
        }

        // 1. Keyboard Language (English | العربية)
        addSegmentedSettingCard(
            title = AppUiStrings.keyboardLanguageSettingTitle(keyboardLanguage),
            options = SettingsPanelSpec.LANGUAGE_OPTIONS,
            initialSelected = keyboardLanguage
        ) { nextLanguage ->
            setKeyboardLanguage(nextLanguage)
        }

        // 2. Theme (Graphite vs Classic)
        addSegmentedSettingCard(
            title = AppUiStrings.themeSettingTitle(keyboardLanguage),
            options = AppUiStrings.themeOptions(keyboardLanguage),
            initialSelected = appTheme
        ) { nextTheme ->
            if (appTheme != nextTheme) {
                cachedAppTheme = SettingsPanelSpec.normalizeThemeValue(nextTheme)
                refreshCachedThemePalette()
                themePreferences.edit().putString(SettingsPanelSpec.PREF_APP_THEME, nextTheme).apply()
                renderFullImeView()
            }
        }

        // 3. Keyboard Height (Compact | Normal | Tall)
        val currentHeight = currentHeightPref()
        addSegmentedSettingCard(
            title = AppUiStrings.keyboardHeightSettingTitle(keyboardLanguage),
            options = AppUiStrings.heightOptions(keyboardLanguage),
            initialSelected = currentHeight
        ) { nextHeight ->
            cachedKeyboardHeight = nextHeight
            themePreferences.edit().putString(SettingsPanelSpec.PREF_KEYBOARD_HEIGHT, nextHeight).apply()
            applyCanonicalViewportHeightInPlace()
        }

        // 4. One-Handed Mode (Left | Off | Right)
        val currentOneHanded = currentOneHandedMode()
        addSegmentedSettingCard(
            title = AppUiStrings.oneHandedSettingTitle(keyboardLanguage),
            options = AppUiStrings.oneHandedOptions(keyboardLanguage),
            initialSelected = currentOneHanded
        ) { nextMode ->
            cachedOneHandedMode = nextMode
            themePreferences.edit().putString(SettingsPanelSpec.PREF_ONE_HANDED, nextMode).apply()
            applyOneHandedPadding()
        }

        // 5. Typing Assistance Card (Autocomplete, Automatic Correction, Suggestions — two equal [On] [Off] pills each)
        val typingCard = makeSettingCard()
        val typingHeader = TextView(this@ClipMaxImeService).apply {
            text = AppUiStrings.typingAssistanceTitle(keyboardLanguage)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pillPalette.sectionTitleFg)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        typingCard.addView(typingHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(6)
        })

        val localizedOnOff = AppUiStrings.binaryOnOffOptions(keyboardLanguage)
        SettingsPanelSpec.TYPING_SETTINGS.forEachIndexed { settingIdx, def ->
            val localizedSettingTitle = AppUiStrings.typingSettingTitle(keyboardLanguage, def.prefKey, def.title)
            val subLbl = TextView(this@ClipMaxImeService).apply {
                text = localizedSettingTitle
                textSize = 11f
                setTextColor(pillPalette.subLabelFg)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            typingCard.addView(subLbl, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            })

            val pairRow = LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
            }

            val pairPills = mutableListOf<Pair<Boolean, TextView>>()
            fun refreshBinary(selectedVal: Boolean) {
                pairPills.forEach { (value, view) ->
                    applyPillVisual(view, value == selectedVal)
                }
            }

            localizedOnOff.forEachIndexed { idx, opt ->
                val pill = makeOptionPill(opt.label, localizedSettingTitle, dp(30))
                pill.setOnClickListener {
                    when (def.prefKey) {
                        "typing_autocomplete" -> cachedAutocompleteEnabled = opt.value
                        "typing_autocorrect" -> cachedAutoCorrectEnabled = opt.value
                        "typing_suggestions" -> cachedSuggestionsEnabled = opt.value
                    }
                    themePreferences.edit().putBoolean(def.prefKey, opt.value).apply()
                    onTypingSettingsChanged()
                    refreshBinary(opt.value)
                }
                pairPills.add(opt.value to pill)
                pairRow.addView(pill, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                    if (idx == 0) marginEnd = pillGapPx
                })
            }
            val initialBinaryVal = when (def.prefKey) {
                "typing_autocomplete" -> isAutocompleteEnabled
                "typing_autocorrect" -> isAutoCorrectEnabled
                "typing_suggestions" -> isSuggestionsEnabled
                else -> def.defaultValue
            }
            refreshBinary(initialBinaryVal)

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

        // 6. Full-width Action Pill: Share ClipMax
        val shareLabel = AppUiStrings.shareClipMaxLabel(keyboardLanguage)
        val sharePill = TextView(this@ClipMaxImeService).apply {
            text = shareLabel
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            isClickable = true
            isFocusable = true
            contentDescription = shareLabel
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
                    putExtra(Intent.EXTRA_TEXT, AppUiStrings.sharePromoMessage(keyboardLanguage))
                }
                val chooser = Intent.createChooser(shareIntent, shareLabel).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(chooser) }
                setStatus(AppUiStrings.shareDialogOpenedStatus(keyboardLanguage))
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
        layoutDirection = uiLayoutDirection
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
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH)
        }

        val pill = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
            background = roundedDrawable(colorKeyBg, dp(8f), colorAccent)
            layoutParams = LinearLayout.LayoutParams(0, pillH, 1f).apply {
                marginEnd = dp(6)
            }
        }
        searchPillView = pill

        val searchTv = TextView(this@ClipMaxImeService).apply {
            text = if (keyboardSearchQuery.isEmpty()) AppUiStrings.searchPlaceholder(keyboardLanguage) else keyboardSearchQuery
            textSize = 11.5f
            setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = if (keyboardSearchQuery.isEmpty()) View.TEXT_DIRECTION_LOCALE else View.TEXT_DIRECTION_ANY_RTL
            setIcon(IconDrawable(IconType.SEARCH, colorAccent, 12f), 5f)
        }
        searchInputText = searchTv
        pill.addView(searchTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val clearBtn = FrameLayout(this@ClipMaxImeService).apply {
            visibility = if (keyboardSearchQuery.isEmpty()) View.GONE else View.VISIBLE
            contentDescription = AppUiStrings.clearSearchQueryA11y(keyboardLanguage)
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
            text = AppUiStrings.doneButton(keyboardLanguage)
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
            layoutDirection = uiLayoutDirection
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(viewport.searchResultsHeightDp).toInt()).apply {
                topMargin = dp(viewport.searchResultsTopMarginDp).toInt()
            }
        }
        val resultsRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(2), 0, dp(8), 0)
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
                text = AppUiStrings.passwordSearchDisabledShort(keyboardLanguage)
                textSize = 10.5f
                setTextColor(colorHintText)
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            return
        }

        val matches = if (keyboardSearchQuery.isNotEmpty()) keyboardSearchResults else items
        if (matches.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (keyboardSearchQuery.isNotEmpty()) {
                    AppUiStrings.noMatchingClips(keyboardLanguage, keyboardSearchQuery)
                } else {
                    AppUiStrings.noSavedClipsToSearch(keyboardLanguage)
                }
                textSize = 11f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            })
            return
        }

        matches.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundedRipple(colorChipBg, dp(10f), colorChipBorder)
                val chipH = (dp(computeCurrentViewportSpec().searchResultsHeightDp).toInt() - dp(4)).coerceAtLeast(dp(26))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, chipH).apply {
                    marginEnd = dp(6)
                }

                if (item.pinned) {
                    val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 10.5f)).apply {
                        setPaddingRelative(0, 0, dp(4), 0)
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
                    textDirection = View.TEXT_DIRECTION_ANY_RTL
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
        btn.text = if (isFilterMode) {
            AppUiStrings.searchModeFilterLabel(keyboardLanguage)
        } else {
            AppUiStrings.searchModeHostLabel(keyboardLanguage)
        }
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
        keyboardSearchQuery.isNotEmpty() -> AppUiStrings.matchesTitle(keyboardLanguage)
        pinnedOnly -> AppUiStrings.pinnedClipsTitle(keyboardLanguage)
        else -> AppUiStrings.clipboardTitle(keyboardLanguage)
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
        toggle.contentDescription = AppUiStrings.captureToggleA11y(keyboardLanguage, enabled)

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
            label.text = AppUiStrings.captureToggleOn(keyboardLanguage)
            label.setTextColor(colorAccentText)
            labelLp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            labelLp.leftMargin = dp(8)
            labelLp.rightMargin = 0
        } else {
            label.text = AppUiStrings.captureToggleOff(keyboardLanguage)
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
                text = AppUiStrings.passwordClipsWithheldActive(keyboardLanguage)
                textSize = 11f
                setTextColor(colorHintText)
                setPadding(dp(12), dp(20), dp(12), dp(20))
                gravity = Gravity.CENTER
                setIcon(IconDrawable(IconType.LOCK, colorHintText, 13f), 4f)
            })
        } else if (visible.isEmpty()) {
            cardsList.addView(TextView(this@ClipMaxImeService).apply {
                text = when {
                    keyboardSearchQuery.isNotEmpty() -> AppUiStrings.noMatchingClips(keyboardLanguage, keyboardSearchQuery)
                    pinnedOnly -> AppUiStrings.noPinnedClipsEmpty(keyboardLanguage)
                    else -> AppUiStrings.noSavedClipsEmpty(keyboardLanguage)
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
                val hasSecondCard = (i + 1) < totalVisible
                val gridRowSpec = ClipboardPanelSpec.resolveGridRowLayout(hasSecondCard)
                val primaryHeightLp = if (gridRowSpec.primaryCardHeightMode == ClipboardPanelSpec.RowHeightMode.MATCH_PARENT) {
                    ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
                val rowLayout = LinearLayout(this@ClipMaxImeService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = uiLayoutDirection
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
                rowLayout.addView(card1, LinearLayout.LayoutParams(0, primaryHeightLp, 1f).apply {
                    marginEnd = halfGapLeft
                })

                if (hasSecondCard) {
                    val item2 = visible[i + 1]
                    val serial2 = ClipboardPanelSpec.computeSerialNumber(i + 1, totalVisible)
                    val card2 = buildCompactCard(item2, serialNumber = serial2)
                    rowLayout.addView(card2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = halfGapRight
                    })
                } else {
                    // Empty spacer view for odd count so 1st card maintains exact 50% width without collapsing row height
                    val spacer = View(this@ClipMaxImeService).apply {
                        visibility = View.INVISIBLE
                    }
                    rowLayout.addView(spacer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = halfGapRight
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
        layoutDirection = uiLayoutDirection
        setBackgroundColor(colorBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        val visible = getVisibleClipboardItems()

        // Gboard-inspired Clipboard Navigation Header:
        // Start: circular back arrow | Center: Clipboard title | End: ON/OFF capture toggle
        val subHeader = FrameLayout(this@ClipMaxImeService).apply {
            layoutDirection = uiLayoutDirection
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setBackgroundColor(if (isClassicTheme) colorAltDeepBg else colorChipBg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ClipboardPanelSpec.HEADER_HEIGHT_DP).toInt())

            val backBtn = FrameLayout(this@ClipMaxImeService).apply {
                background = roundedRipple(colorKeyBg, dp(16f), colorKeyBorder)
                contentDescription = AppUiStrings.backToKeyboardA11y(keyboardLanguage)
                val backArrow = if (isUiRtl) IconType.ARROW_RIGHT else IconType.ARROW_LEFT
                val iv = createIconView(IconDrawable(backArrow, colorKeyText, 14.5f))
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
                layoutDirection = View.LAYOUT_DIRECTION_LTR
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
            val toggleWidthPx = dp(if (isUiRtl) 64 else 54)
            addView(captureToggle, FrameLayout.LayoutParams(toggleWidthPx, dp(24), Gravity.END or Gravity.CENTER_VERTICAL))
            updateClipboardCaptureToggleUI()
        }
        addView(subHeader)

        val headerDivider = View(this@ClipMaxImeService).apply {
            setBackgroundColor(colorKeyBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        addView(headerDivider)

        val mergeBanner = TextView(this@ClipMaxImeService).apply {
            text = AppUiStrings.mergeModeBannerText(keyboardLanguage)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentText)
            setBackgroundColor(colorAccent)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            visibility = if (isMergeMode) View.VISIBLE else View.GONE
            setOnClickListener {
                exitMergeMode(refresh = true)
                setStatus(AppUiStrings.mergeCancelledStatus(keyboardLanguage))
            }
        }
        clipboardMergeBanner = mergeBanner
        addView(mergeBanner)

        // Scrollable cards list with 1.5x card spacing (9dp)
        val scrollView = android.widget.ScrollView(this@ClipMaxImeService).apply {
            layoutDirection = uiLayoutDirection
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
            layoutDirection = uiLayoutDirection
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
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = roundedDrawable(colorKeyBg, dp(16f), colorAccent)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(10f)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

        val undoBtn = Button(this).apply {
            text = AppUiStrings.undoLabel(keyboardLanguage)
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
            text = AppUiStrings.redoLabel(keyboardLanguage)
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
            setStatus(AppUiStrings.mergeSelfErrorStatus(keyboardLanguage))
            return
        }
        if (isSplitMergeHistoryBusy) {
            setStatus(AppUiStrings.operationBusyStatus(keyboardLanguage))
            return
        }
        isMergeMode = false
        mergeSourceId = null
        isSplitMergeHistoryBusy = true
        val submitted = submitSafely(io) {
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
                        setStatus(AppUiStrings.mergedSuccessStatus(keyboardLanguage))
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus(AppUiStrings.mergeFailedStatus(keyboardLanguage, e.message))
                        if (isClipboardPanelOpen && clipboardCardsContainer != null) {
                            refreshClipboardPanelInPlace()
                        }
                    }
                }
            }
        }
        if (!submitted) {
            isSplitMergeHistoryBusy = false
        }
    }

    private fun splitItem(targetId: String) {
        if (isSplitMergeHistoryBusy) {
            setStatus(AppUiStrings.operationBusyStatus(keyboardLanguage))
            return
        }
        isSplitMergeHistoryBusy = true
        val submitted = submitSafely(io) {
            try {
                val splitRes = store.split(targetId)
                if (splitRes == null) {
                    main.post {
                        isSplitMergeHistoryBusy = false
                        if (!serviceDestroyed) {
                            setStatus(AppUiStrings.splitTooShortStatus(keyboardLanguage))
                        }
                    }
                    return@submitSafely
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
                        setStatus(AppUiStrings.splitSuccessStatus(keyboardLanguage))
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus(AppUiStrings.splitFailedStatus(keyboardLanguage, e.message))
                    }
                }
            }
        }
        if (!submitted) {
            isSplitMergeHistoryBusy = false
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
                createdAt = orig.createdAt,
                isExpiryLocked = orig.isExpiryLocked
            )
        }
        val submitted = submitSafely(io) {
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
                        setStatus(AppUiStrings.undidOperationStatus(keyboardLanguage, snapshot.description))
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus(AppUiStrings.undoFailedStatus(keyboardLanguage, e.message))
                        updateUndoRedoControl()
                    }
                }
            }
        }
        if (!submitted) {
            isSplitMergeHistoryBusy = false
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
                createdAt = added.createdAt,
                isExpiryLocked = added.isExpiryLocked
            )
        }
        val submitted = submitSafely(io) {
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
                        setStatus(AppUiStrings.redidOperationStatus(keyboardLanguage, snapshot.description))
                        reloadItems()
                        showUndoRedoControl()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    isSplitMergeHistoryBusy = false
                    if (!serviceDestroyed) {
                        setStatus(AppUiStrings.redoFailedStatus(keyboardLanguage, e.message))
                        updateUndoRedoControl()
                    }
                }
            }
        }
        if (!submitted) {
            isSplitMergeHistoryBusy = false
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
            contentDescription = AppUiStrings.dismissClipOptionsA11y(keyboardLanguage)
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

        // Layer 3: Side-mounted 6-action contextual popup (Pin, Lock expiry, Edit, Delete, Merge, Split)
        // Sized dynamically via OverlayUiSpec.resolveContextMenuLayout so all 6 options fit immediately without vertical scrolling.
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val surfaceWDp = surfaceW / density
        val surfaceHDp = surfaceH / density
        val menuSpec = OverlayUiSpec.resolveContextMenuLayout(
            surfaceWidthDp = surfaceWDp,
            surfaceHeightDp = surfaceHDp,
            isArabic = isUiRtl,
            dividerHeightDp = 1f / density
        )
        val panelWidth = dp(menuSpec.panelWidthDp).toInt().coerceAtMost((surfaceW - dp(16)).coerceAtLeast(dp(120)))
        val rowHeight = dp(menuSpec.rowHeightDp).toInt()
        val vPadPx = dp(menuSpec.verticalPaddingDp).toInt()
        val maxMenuHeightPx = dp(menuSpec.maxMenuHeightDp).toInt().coerceAtMost((surfaceH - dp(8)).coerceAtLeast(dp(120)))
        val iconSize = OverlayUiSpec.CONTEXT_MENU_ICON_SIZE_DP
        val menuSurfaceBg = OverlayUiSpec.resolveContextMenuBg(currentThemePalette)
        val panelCornerPx = dp(OverlayUiSpec.CONTEXT_MENU_CORNER_DP)
        val panelBg = roundedDrawable(menuSurfaceBg, panelCornerPx, colorKeyBorder)

        val panelRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
            setPadding(0, vPadPx, 0, vPadPx)

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
                    layoutDirection = uiLayoutDirection
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
                        marginEnd = dp(OverlayUiSpec.CONTEXT_MENU_ICON_GAP_DP).toInt()
                    })

                    val tv = TextView(this@ClipMaxImeService).apply {
                        text = label
                        textSize = OverlayUiSpec.CONTEXT_MENU_TEXT_SP
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(tint)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        textDirection = View.TEXT_DIRECTION_LOCALE
                    }
                    addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    setOnClickListener { onClick() }
                }
                addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
            }

            val latestItem = store.getItem(targetId) ?: item
            val pinColor = if (latestItem.pinned) colorActiveTabText else colorKeyText
            addActionRow(
                label = AppUiStrings.actionPin(keyboardLanguage, latestItem.pinned),
                iconType = if (latestItem.pinned) IconType.PIN_FILLED else IconType.PIN,
                tint = pinColor
            ) {
                dismissContextMenu(animate = true)
                submitSafely(io) {
                    val fresh = store.getItem(targetId) ?: return@submitSafely
                    val nextPinned = !fresh.pinned
                    store.setPinned(targetId, nextPinned)
                    if (nextPinned) {
                        CapturePreferences.removeExpiry(capturePreferences, targetId)
                    }
                    main.post { if (!serviceDestroyed) reloadItems() }
                }
            }

            val lockColor = if (latestItem.isExpiryLocked) colorAccent else colorKeyText
            addActionRow(
                label = AppUiStrings.actionLockExpiry(keyboardLanguage, latestItem.isExpiryLocked),
                iconType = IconType.LOCK,
                tint = lockColor
            ) {
                dismissContextMenu(animate = true)
                submitSafely(io) {
                    val fresh = store.getItem(targetId) ?: return@submitSafely
                    val nextLocked = !fresh.isExpiryLocked
                    val updated = store.setExpiryLocked(targetId, nextLocked)
                    if (nextLocked || updated.pinned) {
                        CapturePreferences.removeExpiry(capturePreferences, targetId)
                    } else {
                        CapturePreferences.recalculateExpiryOnUnlock(capturePreferences, targetId, captureMode)
                    }
                    CaptureExpiryReceiver.schedule(this@ClipMaxImeService)
                    main.post {
                        if (!serviceDestroyed) {
                            setStatus(AppUiStrings.expiryLockedStatus(keyboardLanguage, nextLocked))
                            reloadItems()
                        }
                    }
                }
            }

            addActionRow(
                label = AppUiStrings.actionEdit(keyboardLanguage),
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
                label = AppUiStrings.actionDelete(keyboardLanguage),
                iconType = IconType.DELETE,
                tint = colorDangerText
            ) {
                dismissContextMenu(animate = true)
                submitSafely(io) {
                    if (store.delete(targetId)) {
                        CapturePreferences.removeExpiry(capturePreferences, targetId)
                        CapturePreferences.onItemsDeleted(capturePreferences, listOf(targetId))
                    }
                    main.post { if (!serviceDestroyed) reloadItems() }
                }
            }

            addActionRow(
                label = AppUiStrings.actionMerge(keyboardLanguage),
                iconType = IconType.MERGE,
                tint = colorKeyText
            ) {
                dismissContextMenu(animate = true)
                isMergeMode = true
                mergeSourceId = targetId
                setStatus(AppUiStrings.mergeModeStatusWithTitle(keyboardLanguage, item.title))
                if (isClipboardPanelOpen && clipboardCardsContainer != null) {
                    refreshClipboardPanelInPlace()
                } else {
                    reloadItems()
                }
            }

            addActionRow(
                label = AppUiStrings.actionSplit(keyboardLanguage),
                iconType = IconType.SPLIT,
                tint = colorKeyText
            ) {
                dismissContextMenu(animate = true)
                splitItem(targetId)
            }
        }

        val panel = FrameLayout(this).apply {
            background = panelBg
            isClickable = true
            clipToOutline = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(10f)
            }
            addView(panelRows, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        val maxTop = (surfaceH - panelHeight - dp(4)).coerceAtLeast(dp(4))
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
        return AppUiStrings.genericSavedClipTitle(keyboardLanguage)
    }

    private fun buildCompactCard(
        item: ClipItem,
        serialNumber: Int = 1,
        isSpotlight: Boolean = false
    ): View = LinearLayout(this).apply {
        val rawContent = snippetCache[item.id] ?: getRawSnippetPrefix(item, 240)
        val previewContent = ClipboardPanelSpec.resolveCardContentPreview(
            rawContent = rawContent,
            maxChars = 220,
            blankSpacesLabel = AppUiStrings.blankSpacesFallback(keyboardLanguage),
            emptyClipLabel = AppUiStrings.emptyClipFallback(keyboardLanguage)
        )
        val serialLabel = "#$serialNumber"
        val radiusPx = dp(ClipboardPanelSpec.CARD_CORNER_RADIUS_DP)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val minCardHeightPx = dp(
            if (isLandscape) ClipboardPanelSpec.CARD_MIN_HEIGHT_LANDSCAPE_DP
            else ClipboardPanelSpec.CARD_MIN_HEIGHT_PORTRAIT_DP
        ).toInt()

        orientation = LinearLayout.VERTICAL
        layoutDirection = uiLayoutDirection
        isFocusable = true
        minimumHeight = minCardHeightPx
        contentDescription = AppUiStrings.clipCardA11y(keyboardLanguage, serialLabel, previewContent)
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

        // Row 1: Dynamic bottom-up positional serial number ("#1", "#2", ...) + subtle expiry lock indicator
        val headerRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
        }
        val serialTv = TextView(this@ClipMaxImeService).apply {
            text = serialLabel
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorModText)
            setSingleLine(true)
            includeFontPadding = false
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        headerRow.addView(serialTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (item.isExpiryLocked) {
            val lockIcon = createIconView(IconDrawable(IconType.LOCK, colorAccent, 10.5f)).apply {
                contentDescription = AppUiStrings.protectedFromExpiryA11y(keyboardLanguage)
            }
            headerRow.addView(lockIcon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(
            headerRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(5)
            }
        )

        // Row 2: Direct beginning of the original clipboard content (original direction preserved via ANY_RTL)
        val contentTv = TextView(this@ClipMaxImeService).apply {
            text = previewContent
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colorKeyText)
            setLineSpacing(dp(2f), 1f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_ANY_RTL
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
                        setStatus(AppUiStrings.mergeCancelledStatus(keyboardLanguage))
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
        layoutDirection = uiLayoutDirection
        setBackgroundColor(colorAltDeepBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))

        val container = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        chipsContainer = container
        addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderClipChips() {
        val container = chipsContainer ?: return
        val base = if (keyboardSearchQuery.isNotEmpty()) keyboardSearchResults else items
        val visible = if (pinnedOnly) base.filter { it.pinned } else base
        val chipsSig = ImeInputPipeline.ViewSignatureSpec.clipChipsSignature(
            storeRevision = lastLoadedStoreVersion,
            itemCount = visible.size,
            firstId = visible.firstOrNull()?.id,
            isPasswordTarget = isPasswordTarget,
            pinnedOnly = pinnedOnly,
            filterQuery = keyboardSearchQuery,
            themeKey = computeThemeStateKey(),
            oneHandedMode = currentOneHandedMode()
        )
        if (container.childCount > 0 && chipsSig == lastRenderedClipChipsSig) {
            return
        }
        lastRenderedClipChipsSig = chipsSig
        container.removeAllViews()

        if (isPasswordTarget) {
            container.addView(TextView(this).apply {
                text = AppUiStrings.passwordClipsWithheldShort(keyboardLanguage)
                textSize = 10.5f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setIcon(IconDrawable(IconType.LOCK, colorHintText, 11f), 4f)
            })
            return
        }

        if (visible.isEmpty()) {
            container.addView(TextView(this).apply {
                text = when {
                    keyboardSearchQuery.isNotEmpty() -> AppUiStrings.noMatchingClips(keyboardLanguage, keyboardSearchQuery)
                    pinnedOnly -> AppUiStrings.noPinnedClipsCompact(keyboardLanguage)
                    else -> AppUiStrings.noSavedClipsCompact(keyboardLanguage)
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
        layoutDirection = uiLayoutDirection
        gravity = Gravity.CENTER_VERTICAL
        val chipPadH = dp(OverlayUiSpec.CLIP_CHIP_PADDING_H_DP).toInt()
        setPadding(chipPadH, 0, chipPadH, 0)
        background = roundedRipple(colorChipBg, dp(OverlayUiSpec.CLIP_CHIP_CORNER_DP), colorChipBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(OverlayUiSpec.CLIP_CHIP_HEIGHT_DP).toInt()).apply {
            marginEnd = dp(6)
        }

        if (item.pinned) {
            val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 11f)).apply {
                setPaddingRelative(0, 0, dp(4), 0)
            }
            addView(pinIcon)
        }
        if (item.isExpiryLocked) {
            val lockIcon = createIconView(IconDrawable(IconType.LOCK, colorHintText, 10f)).apply {
                contentDescription = AppUiStrings.protectedFromExpiryA11y(keyboardLanguage)
                setPaddingRelative(0, 0, dp(4), 0)
            }
            addView(lockIcon)
        }

        val rawSnippet = snippetCache[item.id] ?: getRawSnippetPrefix(item, 180)
        val previewText = ClipPreviewFormatter.resolveChipPreview(
            baseTitle = baseTitle,
            rawSnippet = rawSnippet,
            isPasswordTarget = isPasswordTarget,
            maxChars = 72,
            blankSpacesLabel = AppUiStrings.blankSpacesFallback(keyboardLanguage),
            emptyClipLabel = AppUiStrings.emptyClipFallback(keyboardLanguage)
        )

        val screenW = resources.displayMetrics.widthPixels
        val oneHandedPad = if (currentOneHandedMode() != "off") dp(54) else 0
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
            textDirection = View.TEXT_DIRECTION_ANY_RTL
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
                marginStart = dp(5)
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
        layoutDirection = uiLayoutDirection
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
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        statusLabel = lbl
        addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val cancel = Button(this@ClipMaxImeService).apply {
            text = AppUiStrings.cancelLabel(keyboardLanguage)
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
        val heightPref = currentHeightPref()
        val isExpandedMediaView = isEmojiPickerOpen && !isMediaSearchTyping &&
            !isSettingsOpen && !isClipboardPanelOpen
        return if (isExpandedMediaView) {
            KeyboardLayoutSpec.computeMediaViewportSpecDp(
                heightPref = heightPref,
                screenHeightDp = screenHeightDp,
                isLandscape = isLandscape
            )
        } else {
            KeyboardLayoutSpec.computeViewportSpecDp(
                heightPref = heightPref,
                screenHeightDp = screenHeightDp,
                isLandscape = isLandscape
            )
        }
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
        val heightPref = currentHeightPref()
        val geom = KeyboardLayoutSpec.computeGeometryDp(
            heightPref = heightPref,
            screenHeightDp = screenHeightDp,
            isLandscape = isLandscape,
            hasFilterBar = isFilterMode || (isEmojiPickerOpen && isMediaSearchTyping),
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

    private fun setAppUiLocale(locale: String) {
        val normalized = SettingsPanelSpec.normalizeAppUiLocale(locale)
        if (keyboardLanguage == normalized) return
        cachedAppUiLocale = normalized
        themePreferences.edit().putString(SettingsPanelSpec.PREF_APP_UI_LOCALE, normalized).apply()
        onAppUiLocalePreferenceChanged()
    }

    private fun onAppUiLocalePreferenceChanged() {
        dismissLanguagePopup()
        dismissVariantPopup()
        hideKeyPreview()
        lastRenderedThemeKey = computeThemeStateKey()
        if (imeRootFrame != null) {
            renderFullImeView()
        }
        if (isPasswordTarget) {
            setStatus(AppUiStrings.passwordClipsWithheldDot(keyboardLanguage))
        } else {
            updateCaptureStatus()
        }
    }

    private fun setKeyboardLanguage(language: String) {
        val normalized = SettingsPanelSpec.normalizeLanguageValue(language)
        if (keyboardLanguage == normalized) return
        cachedKeyboardLanguage = normalized
        themePreferences.edit().putString(SettingsPanelSpec.PREF_KEYBOARD_LANGUAGE, normalized).apply()
        onKeyboardLanguagePreferenceChanged()
    }

    private fun onKeyboardLanguagePreferenceChanged() {
        dismissLanguagePopup()
        dismissVariantPopup()
        hideKeyPreview()
        lastRenderedThemeKey = computeThemeStateKey()
        if (keyboardLanguage == SettingsPanelSpec.LANG_ARABIC) {
            clearTypingSuggestionsView()
            shiftStateMachine.onInputSessionReset()
        } else {
            refreshTypingSuggestionsAsync()
            syncAutoCapitalization(preserveManualShift = false)
        }
        when {
            isSettingsOpen -> return
            isEmojiPickerOpen -> renderFullImeView()
            keyboardRowsContainer != null -> renderKeyboardRowsIfNeeded()
        }
    }

    private fun currentKeyboardRowsSignature(): String {
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val screenHeightDp = (dm.heightPixels / density).toInt()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return ImeInputPipeline.ViewSignatureSpec.keyboardRowsSignature(
            language = keyboardLanguage,
            mode = keyboardMode,
            themeKey = computeThemeStateKey(),
            heightPref = currentHeightPref(),
            screenHeightDp = screenHeightDp,
            isLandscape = isLandscape,
            isFilterMode = isFilterMode,
            isInserting = isInserting,
            isPasswordTarget = isPasswordTarget
        )
    }

    private fun renderKeyboardRowsIfNeeded() {
        val container = keyboardRowsContainer ?: return
        val nextSig = currentKeyboardRowsSignature()
        if (container.childCount > 0 && nextSig == lastRenderedKeyboardRowsSig) {
            updateEnterKeyUI()
            updateShiftUI()
            updateToolbarCenterVisibility()
            return
        }
        renderKeyboardRows()
    }

    private fun renderKeyboardRows() {
        val container = keyboardRowsContainer ?: return
        lastRenderedKeyboardRowsSig = currentKeyboardRowsSignature()
        dismissVariantPopup()
        dismissLanguagePopup()
        container.removeAllViews()
        container.layoutDirection = View.LAYOUT_DIRECTION_LTR
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

        val isArabic = keyboardLanguage == SettingsPanelSpec.LANG_ARABIC
        when (keyboardMode) {
            KeyboardLayoutSpec.KeyboardMode.ALPHA -> {
                if (isArabic) renderArabicAlphaRows(container) else renderAlphaRows(container)
            }
            KeyboardLayoutSpec.KeyboardMode.SYMBOLS -> {
                if (isArabic) renderArabicNumericRows(container) else renderNumericRows(container)
            }
            KeyboardLayoutSpec.KeyboardMode.SYMBOLS_EXT -> {
                if (isArabic) renderArabicSymbolRows(container) else renderSymbolRows(container)
            }
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

    // 4A-AR. ARABIC ALPHA (32 standard Arabic letters matching reference layout + long-press variants & diacritics)
    private fun renderArabicAlphaRows(parent: LinearLayout) {
        val uiSpec = KeyboardLayoutSpec.languageUiSpec(SettingsPanelSpec.LANG_ARABIC)

        // Row 1: 11 keys (ض١ ص٢ ث٣ ق٤ ف٥ غ٦ ع٧ ه٨ خ٩ ح ج)
        val row1 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.ARABIC_ALPHA_ROW1.forEach { keySpec ->
            row1.addView(createArabicKey(keySpec.primary, keySpec.hintDigit), keyLp(1f))
        }
        parent.addView(row1)

        // Row 2: 11 keys (ش س ي ب ل ا ت ن م ك ط)
        val row2 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.ARABIC_ALPHA_ROW2.forEach { ch ->
            row2.addView(createArabicKey(ch, null), keyLp(1f))
        }
        parent.addView(row2)

        // Row 3: 10 letters (ذ ء ؤ ر ى ة و ز ظ د) + Backspace = 10.0f flush width
        val row3 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.ARABIC_ALPHA_ROW3.forEach { ch ->
            row3.addView(createArabicKey(ch, null), keyLp(0.855f))
        }
        row3.addView(createBackspaceKey(), keyLp(1.45f))
        parent.addView(row3)

        // Row 4: ؟٣٢١ (1.45f), ،/emoji (0.9f), Globe (0.9f), Space العربية (4.4f), ./diacritics (0.9f), Enter (1.45f)
        val row4 = createKeyRow(isBottomRow = true)
        row4.addView(createModifierKey(uiSpec.symbolsToggleLabel) {
            dismissPasteUndoCapsule()
            modeController.toSymbols()
            renderKeyboardRows()
        }, keyLp(1.45f))

        row4.addView(createCommaEmojiKey(uiSpec.alphaCommaChar), keyLp(0.9f))
        row4.addView(createGlobeKey(), keyLp(0.9f))
        row4.addView(createSpaceKey(), keyLp(4.4f))
        row4.addView(createArabicPeriodKey(), keyLp(0.9f))
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

    // 4B-AR. ARABIC NUMERIC / SYMBOLS (؟٣٢١ with Arabic-Indic ١–٠ and Western 1–0 long-press)
    private fun renderArabicNumericRows(parent: LinearLayout) {
        val uiSpec = KeyboardLayoutSpec.languageUiSpec(SettingsPanelSpec.LANG_ARABIC)

        val row1 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.ARABIC_SYMBOLS_ROW1.forEach { digit ->
            row1.addView(createArabicKey(digit, null), keyLp(1f))
        }
        parent.addView(row1)

        val row2 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.ARABIC_SYMBOLS_ROW2.forEach { sym ->
            row2.addView(createStandardKey(sym), keyLp(1f))
        }
        parent.addView(row2)

        val row3 = createKeyRow(isBottomRow = false)
        row3.addView(createModifierKey("=\\<") {
            dismissPasteUndoCapsule()
            modeController.toSymbolsExt()
            renderKeyboardRows()
        }, keyLp(1.5f))

        KeyboardLayoutSpec.ARABIC_SYMBOLS_ROW3.forEach { sym ->
            row3.addView(createArabicKey(sym, null), keyLp(1f))
        }

        row3.addView(createBackspaceKey(), keyLp(1.5f))
        parent.addView(row3)

        val row4 = createKeyRow(isBottomRow = true)
        row4.addView(createModifierKey(uiSpec.alphaToggleLabel) {
            dismissPasteUndoCapsule()
            modeController.toAlpha()
            renderKeyboardRows()
        }, keyLp(1.45f))

        row4.addView(createCommaEmojiKey(uiSpec.alphaCommaChar), keyLp(0.9f))
        row4.addView(createNumpadToggleKey(), keyLp(0.9f))
        row4.addView(createSpaceKey(), keyLp(4.4f))
        row4.addView(createArabicPeriodKey(), keyLp(0.9f))
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

    // 4C-AR. ARABIC SECONDARY SYMBOLS (=\<)
    private fun renderArabicSymbolRows(parent: LinearLayout) {
        val uiSpec = KeyboardLayoutSpec.languageUiSpec(SettingsPanelSpec.LANG_ARABIC)

        val row1 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.ARABIC_SYMBOLS_EXT_ROW1.forEach { sym ->
            row1.addView(createArabicKey(sym, null), keyLp(1f))
        }
        parent.addView(row1)

        val row2 = createKeyRow(isBottomRow = false)
        KeyboardLayoutSpec.ARABIC_SYMBOLS_EXT_ROW2.forEach { sym ->
            row2.addView(createArabicKey(sym, null), keyLp(1f))
        }
        parent.addView(row2)

        val row3 = createKeyRow(isBottomRow = false)
        row3.addView(createModifierKey(uiSpec.symbolsToggleLabel) {
            dismissPasteUndoCapsule()
            modeController.toSymbols()
            renderKeyboardRows()
        }, keyLp(1.5f))

        KeyboardLayoutSpec.ARABIC_SYMBOLS_EXT_ROW3.forEach { sym ->
            row3.addView(createArabicKey(sym, null), keyLp(0.875f))
        }

        row3.addView(createBackspaceKey(), keyLp(1.5f))
        parent.addView(row3)

        val row4 = createKeyRow(isBottomRow = true)
        row4.addView(createModifierKey(uiSpec.alphaToggleLabel) {
            dismissPasteUndoCapsule()
            modeController.toAlpha()
            renderKeyboardRows()
        }, keyLp(1.45f))

        row4.addView(createCommaEmojiKey(uiSpec.alphaCommaChar), keyLp(0.9f))
        row4.addView(createGlobeKey(), keyLp(0.9f))
        row4.addView(createSpaceKey(), keyLp(4.4f))
        row4.addView(createArabicPeriodKey(), keyLp(0.9f))
        row4.addView(createEnterKey(), keyLp(1.45f))
        parent.addView(row4)
    }

    // 4D. EXACT GBOARD-STYLE NUMERIC KEYPAD (Reached via SYMBOLS -> "12 34")
    private fun createNumpadToggleKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = AppUiStrings.switchToNumpadA11y(keyboardLanguage)

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
        contentDescription = AppUiStrings.spaceKeyA11y(keyboardLanguage)

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
        KeyboardLayoutSpec.NumpadKeyRole.RETURN_ALPHA -> createModifierKey(
            KeyboardLayoutSpec.languageUiSpec(keyboardLanguage).alphaToggleLabel
        ) {
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
            val arabicAlt = if (keyboardLanguage == SettingsPanelSpec.LANG_ARABIC) {
                KeyboardLayoutSpec.arabicVariantsFor(ch)
            } else {
                emptyList()
            }
            if (arabicAlt.isNotEmpty()) {
                attachArabicVariantTouchFeedback(
                    keyView = this,
                    primaryChar = ch,
                    variants = arabicAlt,
                    preselectFirstOnTimeout = true
                )
            } else {
                attachKeyTouchFeedback(
                    keyView = this,
                    onCommit = { handleGenericCharPress(ch) },
                    previewChar = { spec.label }
                )
            }
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
            EmojiCatalog.CategoryId.FLAGS -> IconType.EMOJI_SYMBOLS
        }
    }

    private fun labelForEmojiTab(tabIdx: Int): String {
        if (tabIdx == 0) return AppUiStrings.emojiRecentLabel(keyboardLanguage)
        val cat = EmojiCatalog.CATEGORIES[(tabIdx - 1).coerceIn(0, EmojiCatalog.CATEGORIES.lastIndex)]
        return AppUiStrings.emojiCategoryLabel(keyboardLanguage, cat)
    }

    private fun resolveActiveEmojiTabIndex(): Int {
        if (selectedEmojiCategoryIdx in 0..EmojiCatalog.CATEGORIES.size) {
            return selectedEmojiCategoryIdx
        }
        selectedEmojiCategoryIdx = 0
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
            layoutDirection = uiLayoutDirection
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
                setPaddingRelative(0, 0, dp(10), 0)
            }
            addView(previewTv)

            val removeBtn = TextView(this@ClipMaxImeService).apply {
                text = AppUiStrings.removeFromRecents(keyboardLanguage)
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
                        refreshRecentEmojiSectionInPlace()
                    }
                    dismissEmojiRemoveOverlay()
                }
            }
            addView(removeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val cancelBtn = TextView(this@ClipMaxImeService).apply {
                text = AppUiStrings.cancelLabel(keyboardLanguage)
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
                marginStart = dp(6)
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

    private var mediaStatusBannerRunnable: Runnable? = null

    private fun persistRecentStickers() {
        mediaPreferences.edit()
            .putString(MediaKeyboardSpec.PREF_RECENT_STICKERS, recentStickerHistory.encode())
            .apply()
    }

    private fun persistFavoriteStickers() {
        mediaPreferences.edit()
            .putString(MediaKeyboardSpec.PREF_FAVORITE_STICKERS, favoriteStickers.encode())
            .apply()
    }

    private fun persistRecentKaomojis() {
        mediaPreferences.edit()
            .putString(MediaKeyboardSpec.PREF_RECENT_KAOMOJI, recentKaomojiHistory.encode())
            .apply()
    }

    private fun dismissMediaStatusBanners(root: FrameLayout) {
        mediaStatusBannerRunnable?.let(main::removeCallbacks)
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.tag == "media_status_banner") {
                root.removeViewAt(i)
            }
        }
    }

    private fun showMediaStatusBanner(msg: String) {
        val root = emojiPickerRoot
        if (root == null) {
            setStatus(msg)
            return
        }
        dismissMediaStatusBanners(root)
        val pill = TextView(this).apply {
            tag = "media_status_banner"
            text = msg
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedDrawable(
                if (isClassicTheme) colorAltDeepBg else colorChipBg,
                dp(12f),
                colorAccent
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(6f)
            }
            isClickable = true
            setOnClickListener { root.removeView(this) }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val barH = if (isLandscape) MediaKeyboardSpec.BOTTOM_BAR_HEIGHT_LANDSCAPE_DP else MediaKeyboardSpec.BOTTOM_BAR_HEIGHT_PORTRAIT_DP
            bottomMargin = dp(barH + 8f).toInt()
            marginStart = dp(12)
            marginEnd = dp(12)
        }
        root.addView(pill, lp)
        pill.bringToFront()
        val task = Runnable {
            if (!serviceDestroyed && pill.parent === root) {
                root.removeView(pill)
            }
        }
        mediaStatusBannerRunnable = task
        main.postDelayed(task, 3200L)
    }

    private fun shareMediaViaChooser(file: File, mimeType: String, title: String) {
        val uri = MediaContentProvider.uriForFile(file.name)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val launched = runCatching {
            startActivity(chooser)
            true
        }.getOrDefault(false)
        if (!launched) {
            showMediaStatusBanner(AppUiStrings.mediaSaveFailedStatus(keyboardLanguage))
        }
    }

    private fun saveMediaToPictures(file: File, mimeType: String, title: String) {
        submitSafely(mediaIoExecutor) {
            val savedOk = runCatching {
                val ext = when (mimeType) {
                    "image/webp" -> "webp"
                    else -> "png"
                }
                val cleanTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(28).ifEmpty { "clipmax_media" }
                val fileName = "${cleanTitle}_${System.currentTimeMillis()}.$ext"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/ClipMax")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val outUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching false
                    resolver.openOutputStream(outUri)?.use { out ->
                        file.inputStream().use { inp -> inp.copyTo(out) }
                    } ?: return@runCatching false
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(outUri, values, null, null)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            main.post {
                if (serviceDestroyed) return@post
                if (savedOk) {
                    showMediaStatusBanner(AppUiStrings.mediaSavedSuccessStatus(keyboardLanguage))
                } else {
                    shareMediaViaChooser(file, mimeType, title)
                }
            }
        }
    }

    private fun showRichMediaFallbackBanner(msg: String, file: File, mimeType: String, title: String) {
        val root = emojiPickerRoot
        if (root == null) {
            setStatus(msg)
            return
        }
        dismissMediaStatusBanners(root)

        val card = LinearLayout(this).apply {
            tag = "media_status_banner"
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(
                if (isClassicTheme) colorAltDeepBg else colorChipBg,
                dp(14f),
                colorAccent
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(8f)
            }
            isClickable = true
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = msg
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colorKeyText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        val closeBtn = FrameLayout(this).apply {
            background = roundedRipple(colorModBg, dp(11f), 0)
            isClickable = true
            isFocusable = true
            addView(createIconView(IconDrawable(IconType.CLOSE, colorModText, 10f)), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { root.removeView(card) }
        }
        topRow.addView(closeBtn, LinearLayout.LayoutParams(dp(22), dp(22)))
        card.addView(topRow)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        val shareBtn = TextView(this).apply {
            text = AppUiStrings.mediaShareLabel(keyboardLanguage)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentText)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundedRipple(colorAccent, dp(8f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                root.removeView(card)
                shareMediaViaChooser(file, mimeType, title)
            }
        }
        val saveBtn = TextView(this).apply {
            text = AppUiStrings.mediaSaveLabel(keyboardLanguage)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundedRipple(colorModBg, dp(8f), colorKeyBorder)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                root.removeView(card)
                saveMediaToPictures(file, mimeType, title)
            }
        }
        actions.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        actions.addView(shareBtn)
        card.addView(actions)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val barH = if (isLandscape) MediaKeyboardSpec.BOTTOM_BAR_HEIGHT_LANDSCAPE_DP else MediaKeyboardSpec.BOTTOM_BAR_HEIGHT_PORTRAIT_DP
            bottomMargin = dp(barH + 8f).toInt()
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        root.addView(card, lp)
        card.bringToFront()
        val task = Runnable {
            if (!serviceDestroyed && card.parent === root) {
                root.removeView(card)
            }
        }
        mediaStatusBannerRunnable = task
        main.postDelayed(task, 8000L)
    }

    private fun startMediaSearchTyping() {
        dismissPasteUndoCapsule()
        dismissEmojiRemoveOverlay()
        isStickerPackManagerOpen = false
        isMediaSearchTyping = true
        modeController.toAlpha()
        renderFullImeView()
    }

    private fun buildMediaPickerPanel(): View = FrameLayout(this).apply {
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
            if (isLandscape) MediaKeyboardSpec.TOP_BAR_HEIGHT_LANDSCAPE_DP
            else MediaKeyboardSpec.TOP_BAR_HEIGHT_PORTRAIT_DP
        ).toInt()
        val bottomBarH = dp(
            if (isLandscape) MediaKeyboardSpec.BOTTOM_BAR_HEIGHT_LANDSCAPE_DP
            else MediaKeyboardSpec.BOTTOM_BAR_HEIGHT_PORTRAIT_DP
        ).toInt()
        val navCtrlH = (headerH - dp(8)).coerceAtLeast(dp(28))
        val navHeaderBg = if (isClassicTheme) colorAltDeepBg else colorChipBg

        // 1. TOP NAVIGATION BAR: [← Back] | [🔍 Search ...] | Mode-specific category strip
        val header = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(navHeaderBg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerH)
        }

        val backBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(colorModBg, dp(navCtrlH / 2f), colorKeyBorder)
            contentDescription = AppUiStrings.backToKeyboardA11y(keyboardLanguage)
            isClickable = true
            isFocusable = true
            val arrowIcon = if (isUiRtl) IconType.ARROW_RIGHT else IconType.ARROW_LEFT
            val iv = createIconView(IconDrawable(arrowIcon, colorKeyText, 15f))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                dismissPasteUndoCapsule()
                dismissEmojiRemoveOverlay()
                if (currentMediaSubMode == MediaKeyboardSpec.MediaSubMode.STICKERS && isStickerPackManagerOpen) {
                    isStickerPackManagerOpen = false
                    renderFullImeView()
                } else if (mediaSearchQuery.isNotBlank()) {
                    mediaSearchQuery = ""
                    renderFullImeView()
                } else {
                    isEmojiPickerOpen = false
                    isMediaSearchTyping = false
                    isStickerPackManagerOpen = false
                    renderFullImeView()
                }
            }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(navCtrlH, navCtrlH).apply {
            marginEnd = dp(6)
        })

        // Search pill/button inside top bar (hidden while browsing in-keyboard Pack Store)
        val inPackStore = currentMediaSubMode == MediaKeyboardSpec.MediaSubMode.STICKERS && isStickerPackManagerOpen
        val isSearching = !inPackStore && mediaSearchQuery.isNotBlank()
        val showSearchLabel = !inPackStore && (isSearching ||
            currentMediaSubMode == MediaKeyboardSpec.MediaSubMode.EMOJI)

        if (inPackStore) {
            val installedCount = stickerPackManager.installedPacks().size
            val totalBytes = stickerPackManager.totalDiskBytes()
            val storeTitleTv = TextView(this@ClipMaxImeService).apply {
                text = AppUiStrings.stickerPackStoreTitle(keyboardLanguage)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            val storageTv = TextView(this@ClipMaxImeService).apply {
                text = "$installedCount/${StickerCatalog.PACKS.size} · ${MediaKeyboardSpec.StickerPackStorePolicy.formatStorageSize(totalBytes)}"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccentText)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedDrawable(colorAccent, dp(10f))
            }
            header.addView(storeTitleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            header.addView(storageTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val searchControl = LinearLayout(this@ClipMaxImeService).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                val pillRadius = dp(navCtrlH / 2f)
                background = roundedRipple(
                    if (isSearching) colorActiveTabBg else colorModBg,
                    pillRadius,
                    if (isSearching) colorAccent else colorKeyBorder
                )
                if (showSearchLabel) {
                    setPadding(dp(10), 0, if (isSearching) dp(6) else dp(12), 0)
                }
                contentDescription = when (currentMediaSubMode) {
                    MediaKeyboardSpec.MediaSubMode.EMOJI -> AppUiStrings.searchEmojisPlaceholder(keyboardLanguage)
                    MediaKeyboardSpec.MediaSubMode.STICKERS -> AppUiStrings.searchStickersPlaceholder(keyboardLanguage)
                    MediaKeyboardSpec.MediaSubMode.KAOMOJI -> AppUiStrings.searchKaomojiPlaceholder(keyboardLanguage)
                }
                val iconColor = if (isSearching) colorActiveTabText else colorModText
                val iconIv = createIconView(IconDrawable(IconType.SEARCH, iconColor, 14f))
                addView(iconIv, if (showSearchLabel) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                } else {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                })

                if (showSearchLabel) {
                    val queryLabel = TextView(this@ClipMaxImeService).apply {
                        text = if (isSearching) mediaSearchQuery else AppUiStrings.searchMediaPlaceholder(keyboardLanguage).trimEnd('…', '.')
                        textSize = 12f
                        typeface = if (isSearching) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(if (isSearching) colorActiveTabText else colorHintText)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                    }
                    addView(queryLabel, LinearLayout.LayoutParams(
                        if (isSearching) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        if (isSearching) 1f else 0f
                    ).apply {
                        marginStart = dp(5)
                    })
                }

                if (isSearching) {
                    val clearSearchBtn = FrameLayout(this@ClipMaxImeService).apply {
                        contentDescription = AppUiStrings.clearSearchQueryA11y(keyboardLanguage)
                        isClickable = true
                        isFocusable = true
                        background = roundedRipple(0, dp(10f), 0)
                        val iv = createIconView(IconDrawable(IconType.CLOSE, colorActiveTabText, 11f))
                        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                        setOnClickListener {
                            mediaSearchQuery = ""
                            renderFullImeView()
                        }
                    }
                    addView(clearSearchBtn, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        marginStart = dp(4)
                    })
                }

                setOnClickListener { startMediaSearchTyping() }
            }

            if (isSearching) {
                header.addView(searchControl, LinearLayout.LayoutParams(0, navCtrlH, 1f))
            } else {
                val ctrlW = if (showSearchLabel) ViewGroup.LayoutParams.WRAP_CONTENT else navCtrlH
                header.addView(searchControl, LinearLayout.LayoutParams(ctrlW, navCtrlH).apply {
                    marginEnd = dp(6)
                })
            }
        }

        // Mode-specific Category Strip (shown when not searching and not inside Pack Store)
        if (!isSearching && !inPackStore) {
            when (currentMediaSubMode) {
                MediaKeyboardSpec.MediaSubMode.EMOJI -> {
                    val tabsScroll = HorizontalScrollView(this@ClipMaxImeService).apply {
                        isHorizontalScrollBarEnabled = false
                        layoutDirection = uiLayoutDirection
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    }
                    emojiTabsScrollView = tabsScroll
                    val tabsRow = LinearLayout(this@ClipMaxImeService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = uiLayoutDirection
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    emojiTabViews.clear()
                    emojiScrollTabController.reset(resolveActiveEmojiTabIndex())
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
                                val targetView = emojiSectionViews.getOrNull(tabIdx)
                                val targetY = targetView?.top ?: 0
                                selectedEmojiCategoryIdx = emojiScrollTabController.onTabSelected(
                                    tabIdx = tabIdx,
                                    targetScrollY = targetY,
                                    nowMs = SystemClock.elapsedRealtime()
                                )
                                updateEmojiCategoryTabsUI()
                                if (targetView != null) {
                                    emojiScrollView?.smoothScrollTo(0, targetY)
                                } else {
                                    emojiScrollView?.scrollTo(0, 0)
                                }
                            }
                        }
                        emojiTabViews.add(tabIdx to tab)
                        tabsRow.addView(tab, LinearLayout.LayoutParams(tabW, navCtrlH).apply {
                            if (tabIdx < totalTabs - 1) marginEnd = tabGap
                        })
                    }
                    tabsScroll.addView(tabsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    header.addView(tabsScroll)
                }

                MediaKeyboardSpec.MediaSubMode.STICKERS -> {
                    val addPackBtn = FrameLayout(this@ClipMaxImeService).apply {
                        contentDescription = AppUiStrings.stickerPackStoreTitle(keyboardLanguage)
                        isClickable = true
                        isFocusable = true
                        background = roundedRipple(colorModBg, dp(navCtrlH / 2f), colorKeyBorder)
                        addView(createIconView(IconDrawable(IconType.PLUS, colorKeyText, 14f)), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                        setOnClickListener {
                            isStickerPackManagerOpen = true
                            renderFullImeView()
                        }
                    }
                    header.addView(addPackBtn, LinearLayout.LayoutParams(navCtrlH, navCtrlH).apply {
                        marginEnd = dp(6)
                    })

                    val tabsScroll = HorizontalScrollView(this@ClipMaxImeService).apply {
                        isHorizontalScrollBarEnabled = false
                        layoutDirection = uiLayoutDirection
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    }
                    stickerTabsScrollView = tabsScroll
                    val tabsRow = LinearLayout(this@ClipMaxImeService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = uiLayoutDirection
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    stickerTabViews.clear()
                    val installedPacks = stickerPackManager.installedPacks()
                    val maxTabIdx = 1 + installedPacks.size
                    val defaultStickerTab = if (selectedStickerSectionIdx == 0 && recentStickerHistory.items().isEmpty()) {
                        if (favoriteStickers.items().isNotEmpty()) 1 else if (installedPacks.isNotEmpty()) 2 else 0
                    } else if (selectedStickerSectionIdx == 1 && favoriteStickers.items().isEmpty() && installedPacks.isNotEmpty()) {
                        selectedStickerSectionIdx
                    } else {
                        selectedStickerSectionIdx.coerceIn(0, maxTabIdx)
                    }
                    selectedStickerSectionIdx = defaultStickerTab
                    stickerScrollTabController.reset(defaultStickerTab)
                    val tabSize = navCtrlH
                    val tabGap = dp(5)
                    val totalStickerTabs = 2 + installedPacks.size
                    val gen = mediaViewportGen
                    for (tabIdx in 0 until totalStickerTabs) {
                        val tab = FrameLayout(this@ClipMaxImeService).apply {
                            contentDescription = when (tabIdx) {
                                0 -> AppUiStrings.emojiRecentLabel(keyboardLanguage)
                                1 -> AppUiStrings.stickerFavoritesLabel(keyboardLanguage)
                                else -> {
                                    val pack = installedPacks[tabIdx - 2]
                                    if (AppUiStrings.isArabic(keyboardLanguage)) pack.titleAr else pack.titleEn
                                }
                            }
                            isClickable = true
                            isFocusable = true
                            when (tabIdx) {
                                0 -> {
                                    val iv = createIconView(IconDrawable(IconType.EMOJI_RECENT, colorModText, 16.5f))
                                    tag = iv
                                    addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                                }
                                1 -> {
                                    val iv = createIconView(IconDrawable(IconType.STAR, colorModText, 16f))
                                    tag = iv
                                    addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                                }
                                else -> {
                                    val pack = installedPacks[tabIdx - 2]
                                    val repItem = pack.items.firstOrNull()
                                    val iv = ImageView(this@ClipMaxImeService).apply {
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                        setPadding(dp(3), dp(3), dp(3), dp(3))
                                    }
                                    loadStickerBitmapAsync(pack.iconAssetPath, iv, gen, repItem)
                                    addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                                }
                            }
                            setOnClickListener {
                                val targetView = stickerSectionViews.getOrNull(tabIdx)
                                val targetY = targetView?.top ?: 0
                                selectedStickerSectionIdx = stickerScrollTabController.onTabSelected(
                                    tabIdx = tabIdx,
                                    targetScrollY = targetY,
                                    nowMs = SystemClock.elapsedRealtime()
                                )
                                updateStickerCategoryTabsUI()
                                if (targetView != null) {
                                    stickerScrollView?.smoothScrollTo(0, targetY)
                                } else {
                                    stickerScrollView?.scrollTo(0, 0)
                                }
                            }
                        }
                        stickerTabViews.add(tabIdx to tab)
                        tabsRow.addView(tab, LinearLayout.LayoutParams(tabSize, navCtrlH).apply {
                            if (tabIdx < totalStickerTabs - 1) marginEnd = tabGap
                        })
                    }
                    tabsScroll.addView(tabsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    header.addView(tabsScroll)
                }

                MediaKeyboardSpec.MediaSubMode.KAOMOJI -> {
                    val tabsScroll = HorizontalScrollView(this@ClipMaxImeService).apply {
                        isHorizontalScrollBarEnabled = false
                        layoutDirection = uiLayoutDirection
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    }
                    kaomojiTabsScrollView = tabsScroll
                    val tabsRow = LinearLayout(this@ClipMaxImeService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = uiLayoutDirection
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    kaomojiTabViews.clear()
                    val defaultKaomojiTab = if (recentKaomojiHistory.items().isEmpty() && selectedKaomojiCategoryIdx == 0) 1 else selectedKaomojiCategoryIdx.coerceIn(0, KaomojiCatalog.CATEGORIES.size)
                    selectedKaomojiCategoryIdx = defaultKaomojiTab
                    kaomojiScrollTabController.reset(defaultKaomojiTab)

                    // Recent clock icon tab at idx 0
                    val recentTab = FrameLayout(this@ClipMaxImeService).apply {
                        contentDescription = AppUiStrings.emojiRecentLabel(keyboardLanguage)
                        isClickable = true
                        isFocusable = true
                        val iv = createIconView(IconDrawable(IconType.EMOJI_RECENT, colorModText, 16.5f))
                        tag = iv
                        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                        setOnClickListener {
                            val targetView = kaomojiSectionViews.getOrNull(0)
                            val targetY = targetView?.top ?: 0
                            selectedKaomojiCategoryIdx = kaomojiScrollTabController.onTabSelected(
                                tabIdx = 0,
                                targetScrollY = targetY,
                                nowMs = SystemClock.elapsedRealtime()
                            )
                            updateKaomojiCategoryTabsUI()
                            if (targetView != null) kaomojiScrollView?.smoothScrollTo(0, targetY)
                            else kaomojiScrollView?.scrollTo(0, 0)
                        }
                    }
                    kaomojiRecentTabView = recentTab
                    tabsRow.addView(recentTab, LinearLayout.LayoutParams(navCtrlH, navCtrlH).apply {
                        marginEnd = dp(5)
                    })

                    KaomojiCatalog.CATEGORIES.forEachIndexed { idx, cat ->
                        val tabIdx = idx + 1
                        val pill = TextView(this@ClipMaxImeService).apply {
                            text = if (AppUiStrings.isArabic(keyboardLanguage)) cat.labelAr else cat.labelEn
                            textSize = 12f
                            gravity = Gravity.CENTER
                            includeFontPadding = false
                            setSingleLine(true)
                            isClickable = true
                            isFocusable = true
                            setPadding(dp(11), 0, dp(11), 0)
                            setOnClickListener {
                                val targetView = kaomojiSectionViews.getOrNull(tabIdx)
                                val targetY = targetView?.top ?: 0
                                selectedKaomojiCategoryIdx = kaomojiScrollTabController.onTabSelected(
                                    tabIdx = tabIdx,
                                    targetScrollY = targetY,
                                    nowMs = SystemClock.elapsedRealtime()
                                )
                                updateKaomojiCategoryTabsUI()
                                if (targetView != null) kaomojiScrollView?.smoothScrollTo(0, targetY)
                                else kaomojiScrollView?.scrollTo(0, 0)
                            }
                        }
                        kaomojiTabViews.add(tabIdx to pill)
                        tabsRow.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, navCtrlH).apply {
                            if (idx < KaomojiCatalog.CATEGORIES.lastIndex) marginEnd = dp(5)
                        })
                    }
                    tabsScroll.addView(tabsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    header.addView(tabsScroll)
                }
            }
        }

        column.addView(header)

        val topDivider = View(this@ClipMaxImeService).apply {
            setBackgroundColor(colorKeyBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        column.addView(topDivider)

        // 2. CONTENT BODY (switches cleanly per active sub-mode: Emoji, Stickers, Kaomoji)
        when (currentMediaSubMode) {
            MediaKeyboardSpec.MediaSubMode.EMOJI -> column.addView(buildEmojiBrowserBody())
            MediaKeyboardSpec.MediaSubMode.STICKERS -> {
                if (isStickerPackManagerOpen) column.addView(buildStickersPackManagerBody())
                else column.addView(buildStickersBrowserBody())
            }
            MediaKeyboardSpec.MediaSubMode.KAOMOJI -> column.addView(buildKaomojiBrowserBody())
        }

        val bottomDivider = View(this@ClipMaxImeService).apply {
            setBackgroundColor(colorKeyBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        column.addView(bottomDivider)

        // 3. SHARED BOTTOM SELECTOR BAR: [ABC] | [ 😊 | Sticker | :-) ] | [⌫]
        val bottomBar = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(navHeaderBg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomBarH)
        }
        val ctrlH = (bottomBarH - dp(8)).coerceAtLeast(dp(28))

        val abcBtn = TextView(this@ClipMaxImeService).apply {
            text = KeyboardLayoutSpec.languageUiSpec(keyboardLanguage).alphaToggleLabel
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            minWidth = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(colorKeyText)
            background = roundedRipple(colorModBg, dp(10f), colorKeyBorder)
            contentDescription = AppUiStrings.returnToKeyboardA11y(keyboardLanguage)
            setOnClickListener {
                dismissPasteUndoCapsule()
                dismissEmojiRemoveOverlay()
                isEmojiPickerOpen = false
                isMediaSearchTyping = false
                isStickerPackManagerOpen = false
                modeController.toAlpha()
                renderFullImeView()
            }
        }
        bottomBar.addView(abcBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctrlH).apply {
            marginEnd = dp(6)
        })

        // Centered 3-Mode Selector Group: [ 😊 | Sticker | :-) ]
        val modeGroup = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        fun makeModePill(
            mode: MediaKeyboardSpec.MediaSubMode,
            a11yLabel: String,
            iconType: IconType? = null,
            textLabel: String? = null
        ): FrameLayout {
            val active = (currentMediaSubMode == mode)
            val bg = if (active) colorActiveTabBg else colorModBg
            val fg = if (active) colorActiveTabText else colorModText
            val stroke = if (active) (if (isClassicTheme) colorAccent else colorChipBorder) else 0
            return FrameLayout(this@ClipMaxImeService).apply {
                contentDescription = a11yLabel
                isClickable = true
                isFocusable = true
                background = roundedRipple(bg, dp(ctrlH / 2f), stroke)
                if (iconType != null) {
                    val iv = createIconView(IconDrawable(iconType, fg, 17f))
                    addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                } else {
                    val tv = TextView(this@ClipMaxImeService).apply {
                        text = textLabel
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(fg)
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                    }
                    addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }
                setOnClickListener {
                    if (currentMediaSubMode != mode) {
                        dismissPasteUndoCapsule()
                        dismissEmojiRemoveOverlay()
                        currentMediaSubMode = mode
                        mediaSearchQuery = ""
                        isMediaSearchTyping = false
                        isStickerPackManagerOpen = false
                        renderFullImeView()
                    }
                }
            }
        }

        val pillW = dp(52)
        val pillGap = dp(10)
        val emojiPill = makeModePill(MediaKeyboardSpec.MediaSubMode.EMOJI, AppUiStrings.mediaEmojiTabA11y(keyboardLanguage), iconType = IconType.EMOJI)
        val stickerPill = makeModePill(MediaKeyboardSpec.MediaSubMode.STICKERS, AppUiStrings.mediaStickersTabA11y(keyboardLanguage), iconType = IconType.MEDIA_STICKER)
        val kaomojiPill = makeModePill(MediaKeyboardSpec.MediaSubMode.KAOMOJI, AppUiStrings.mediaKaomojiTabA11y(keyboardLanguage), textLabel = ":-)")

        modeGroup.addView(emojiPill, LinearLayout.LayoutParams(pillW, ctrlH).apply { marginEnd = pillGap })
        modeGroup.addView(stickerPill, LinearLayout.LayoutParams(pillW, ctrlH).apply { marginEnd = pillGap })
        modeGroup.addView(kaomojiPill, LinearLayout.LayoutParams(pillW, ctrlH))
        bottomBar.addView(modeGroup)

        val backspaceBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(colorModBg, dp(10f), colorKeyBorder)
            contentDescription = AppUiStrings.deleteKeyA11y(keyboardLanguage)
            isClickable = true
            isFocusable = true
            val iv = createIconView(IconDrawable(IconType.BACKSPACE, colorKeyText, EmojiInterfaceSpec.BACKSPACE_ICON_SIZE_DP))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setupBackspaceRepeat(this)
        }
        bottomBar.addView(backspaceBtn, LinearLayout.LayoutParams(dp(EmojiInterfaceSpec.BACKSPACE_WIDTH_DP).toInt(), ctrlH).apply {
            marginStart = dp(6)
        })

        column.addView(bottomBar)
        addView(column)
    }

    private fun buildEmojiBrowserBody(): View {
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    emojiScrollTabController.onUserTouchInteraction()
                }
                false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { v, _, scrollY, _, _ ->
                    if (mediaSearchQuery.isBlank()) {
                        val tops = emojiSectionViews.map { it.top }
                        val bottomReached = scrollY > 0 && !v.canScrollVertically(1)
                        val changed = emojiScrollTabController.onVerticalScroll(
                            sectionTops = tops,
                            scrollY = scrollY,
                            bottomReached = bottomReached,
                            anchorOffsetPx = dp(32),
                            settleTolerancePx = dp(4),
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        if (changed) {
                            selectedEmojiCategoryIdx = emojiScrollTabController.activeTabIdx
                            updateEmojiCategoryTabsUI()
                        }
                    }
                }
            }
        }
        emojiScrollView = scroll

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }
        emojiGridContainer = grid
        scroll.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (mediaSearchQuery.isNotBlank()) {
            populateEmojiSearchResults(grid, EmojiCatalog.search(mediaSearchQuery))
        } else {
            updateEmojiCategoryTabsUI()
            populateEmojiGrid()
        }
        return scroll
    }

    private fun populateEmojiSearchResults(grid: LinearLayout, results: List<String>) {
        grid.removeAllViews()
        emojiSectionViews.clear()
        emojiRecentSectionContainer = null
        val (cols, cellH, rowGap) = emojiGridMetrics()
        if (results.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = if (AppUiStrings.isArabic(keyboardLanguage)) "لا توجد رموز تعبيرية مطابقة" else "No matching emojis"
                textSize = 13f
                setTextColor(colorHintText)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(16))
            })
            return
        }
        var i = 0
        while (i < results.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH).apply {
                    bottomMargin = rowGap
                }
            }
            for (c in 0 until cols) {
                val idx = i + c
                if (idx < results.size) {
                    val emoji = results[idx]
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
                            handleEmojiPress(emoji)
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

    private fun updateEmojiCategoryTabsUI() {
        val activeIdx = resolveActiveEmojiTabIndex()
        var activeTabView: FrameLayout? = null
        for ((tabIdx, tab) in emojiTabViews) {
            val active = (tabIdx == activeIdx)
            if (active) activeTabView = tab
            val bg = if (active) colorActiveTabBg else 0
            val fg = if (active) colorActiveTabText else colorModText
            val stroke = if (active) (if (isClassicTheme) colorAccent else colorChipBorder) else 0
            tab.background = roundedRipple(bg, dp(9f), stroke)
            val iv = tab.tag as? ImageView
            iv?.setImageDrawable(IconDrawable(iconTypeForEmojiTab(tabIdx), fg, EmojiInterfaceSpec.TAB_ICON_SIZE_DP))
        }
        val tabsScroll = emojiTabsScrollView
        if (tabsScroll != null && activeTabView != null && tabsScroll.width > 0) {
            val targetX = activeTabView.left - (tabsScroll.width - activeTabView.width) / 2
            tabsScroll.smoothScrollTo(targetX.coerceAtLeast(0), 0)
        }
    }

    private fun emojiGridMetrics(): Triple<Int, Int, Int> {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cols = if (isLandscape) EmojiInterfaceSpec.LANDSCAPE_COLUMNS else EmojiInterfaceSpec.PORTRAIT_COLUMNS
        val cellH = dp(
            if (isLandscape) EmojiInterfaceSpec.CELL_HEIGHT_LANDSCAPE_DP
            else EmojiInterfaceSpec.CELL_HEIGHT_PORTRAIT_DP
        ).toInt()
        val rowGap = dp(EmojiInterfaceSpec.CELL_ROW_GAP_DP).toInt()
        return Triple(cols, cellH, rowGap)
    }

    private fun populateEmojiSectionContent(
        sectionContainer: LinearLayout,
        sectionIdx: Int,
        cols: Int,
        cellH: Int,
        rowGap: Int
    ) {
        sectionContainer.removeAllViews()
        sectionContainer.layoutDirection = uiLayoutDirection
        val sectionTitle = if (sectionIdx == 0) {
            AppUiStrings.emojiRecentLabel(keyboardLanguage)
        } else {
            AppUiStrings.emojiCategoryLabel(keyboardLanguage, EmojiCatalog.CATEGORIES[sectionIdx - 1])
        }
        val emojis = if (sectionIdx == 0) {
            recentEmojiHistory.items()
        } else {
            EmojiCatalog.CATEGORIES[sectionIdx - 1].emojis
        }

        val headerTv = TextView(this).apply {
            text = sectionTitle
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorHintText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = View.TEXT_DIRECTION_LOCALE
            setPadding(dp(12), dp(8), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sectionContainer.addView(headerTv)

        if (sectionIdx == 0 && emojis.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = AppUiStrings.emojiEmptyRecents(keyboardLanguage)
                textSize = 12f
                setTextColor(colorHintText)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            sectionContainer.addView(emptyTv)
            return
        }

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
                            handleEmojiPress(emoji)
                        }
                        if (sectionIdx == 0) {
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
            sectionContainer.addView(row)
            i += cols
        }
    }

    private fun refreshRecentEmojiSectionInPlace() {
        val recentContainer = emojiRecentSectionContainer ?: return
        val scroll = emojiScrollView
        val oldHeight = recentContainer.height
        val oldScrollY = scroll?.scrollY ?: 0
        val (cols, cellH, rowGap) = emojiGridMetrics()
        populateEmojiSectionContent(recentContainer, 0, cols, cellH, rowGap)

        if (scroll != null && oldScrollY > 0 && oldHeight > 0) {
            val vto = recentContainer.viewTreeObserver
            if (vto.isAlive) {
                vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (recentContainer.viewTreeObserver.isAlive) {
                            recentContainer.viewTreeObserver.removeOnPreDrawListener(this)
                        }
                        val deltaH = recentContainer.height - oldHeight
                        if (deltaH != 0) {
                            scroll.scrollTo(0, (oldScrollY + deltaH).coerceAtLeast(0))
                        }
                        return true
                    }
                })
            }
        }
    }

    private fun populateEmojiGrid() {
        val grid = emojiGridContainer ?: return
        grid.removeAllViews()
        emojiSectionViews.clear()
        emojiRecentSectionContainer = null

        val (cols, cellH, rowGap) = emojiGridMetrics()
        val totalSections = 1 + EmojiCatalog.CATEGORIES.size
        for (sectionIdx in 0 until totalSections) {
            val sectionContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
            }
            if (sectionIdx == 0) {
                emojiRecentSectionContainer = sectionContainer
            }
            emojiSectionViews.add(sectionContainer)
            populateEmojiSectionContent(sectionContainer, sectionIdx, cols, cellH, rowGap)
            grid.addView(sectionContainer)
        }
    }

    private fun handleEmojiPress(emoji: String) {
        dismissPasteUndoCapsule()
        dismissEmojiRemoveOverlay()
        shiftStateMachine.onNonLetterInput()
        if (isEmojiPickerOpen && isMediaSearchTyping) {
            mediaSearchQuery += emoji
            updateMediaSearchUI()
            renderMediaSearchStripResults()
        } else if (isFilterMode) {
            keyboardSearchQuery += emoji
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection ?: return
            insertionHistory.invalidateImmediateBackspaceRevert()
            lastRevertOriginalWord = null
            clearTypingSuggestionsView()
            if (commitTextSafely(conn, emoji, 1)) {
                shadowEditor.onCommitText(emoji)
            }
            updateInsertionHistoryButtons()
            syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
        }
        recentEmojiHistory.record(emoji)
        persistRecentEmojis()
        refreshRecentEmojiSectionInPlace()
    }

    // -------------------------------------------------------------------------
    // STICKERS BROWSER (10 Coherent Packs, 330+ Unique Stickers, Recent, Favourites, Local Pack Store)
    // -------------------------------------------------------------------------
    private fun updateStickerCategoryTabsUI() {
        val maxIdx = (stickerTabViews.maxOfOrNull { it.first } ?: 1).coerceAtLeast(1)
        val activeIdx = selectedStickerSectionIdx.coerceIn(0, maxIdx)
        var activeView: FrameLayout? = null
        for ((tabIdx, tab) in stickerTabViews) {
            val active = (tabIdx == activeIdx)
            if (active) activeView = tab
            val bg = if (active) colorActiveTabBg else 0
            val stroke = if (active) (if (isClassicTheme) colorAccent else colorChipBorder) else 0
            tab.background = roundedRipple(bg, dp(16f), stroke)
            when (tabIdx) {
                0 -> {
                    val fg = if (active) colorActiveTabText else colorModText
                    (tab.tag as? ImageView)?.setImageDrawable(IconDrawable(IconType.EMOJI_RECENT, fg, 16.5f))
                }
                1 -> {
                    val fg = if (active) colorActiveTabText else colorModText
                    (tab.tag as? ImageView)?.setImageDrawable(IconDrawable(IconType.STAR, fg, 16f))
                }
            }
        }
        val tabsScroll = stickerTabsScrollView
        if (tabsScroll != null && activeView != null && tabsScroll.width > 0) {
            val targetX = activeView.left - (tabsScroll.width - activeView.width) / 2
            tabsScroll.smoothScrollTo(targetX.coerceAtLeast(0), 0)
        }
    }

    private fun loadStickerBitmapAsync(
        assetPath: String,
        imageView: ImageView,
        expectedGen: Int,
        item: StickerCatalog.StickerItem? = null
    ) {
        val cached = stickerBitmapCache.get(assetPath)
        if (cached != null && !cached.isRecycled) {
            imageView.setImageBitmap(cached)
            return
        }
        imageView.tag = assetPath
        submitSafely(mediaIoExecutor) {
            val bmp = runCatching {
                val stream = if (item != null) stickerPackManager.resolveStickerStream(item) else assets.open(assetPath)
                stream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) {
                stickerBitmapCache.put(assetPath, bmp)
                main.post {
                    if (!serviceDestroyed && expectedGen == mediaViewportGen && imageView.tag == assetPath) {
                        imageView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun createStickerTileView(item: StickerCatalog.StickerItem, heightPx: Int, gen: Int): FrameLayout {
        val tileBg = if (isClassicTheme) colorKeyBg else colorChipBg
        val isFav = favoriteStickers.isFavorite(item.id)
        return FrameLayout(this).apply {
            contentDescription = item.title
            isClickable = true
            isFocusable = true
            isLongClickable = true
            background = roundedRipple(tileBg, dp(10f), colorKeyBorder)
            val iv = ImageView(this@ClipMaxImeService).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            loadStickerBitmapAsync(item.assetPath, iv, gen, item)
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            if (isFav) {
                val starBadge = createIconView(IconDrawable(IconType.STAR, colorAccent, 11f))
                addView(
                    starBadge,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(3)
                        marginEnd = dp(3)
                    }
                )
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handleStickerPress(item)
            }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val nowFav = favoriteStickers.toggle(item.id)
                persistFavoriteStickers()
                showMediaStatusBanner(AppUiStrings.stickerAddedFavoriteStatus(keyboardLanguage, nowFav))
                renderFullImeView()
                true
            }
        }
    }

    private fun addStickerGridRows(
        container: LinearLayout,
        items: List<StickerCatalog.StickerItem>,
        cols: Int,
        tileHeightPx: Int,
        gapPx: Int,
        gen: Int
    ) {
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileHeightPx).apply {
                    bottomMargin = gapPx
                }
            }
            for (c in 0 until cols) {
                val idx = i + c
                if (idx < items.size) {
                    val tile = createStickerTileView(items[idx], tileHeightPx, gen)
                    row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (c < cols - 1) marginEnd = gapPx
                    })
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (c < cols - 1) marginEnd = gapPx
                    })
                }
            }
            container.addView(row)
            i += cols
        }
    }

    private fun buildStickersBrowserBody(): View {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cols = if (isLandscape) MediaKeyboardSpec.STICKER_COLS_LANDSCAPE else MediaKeyboardSpec.STICKER_COLS_PORTRAIT
        val tileHeightPx = dp(if (isLandscape) 68 else 82)
        val gapPx = dp(5)
        val gen = mediaViewportGen

        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    stickerScrollTabController.onUserTouchInteraction()
                }
                false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { v, _, scrollY, _, _ ->
                    if (mediaSearchQuery.isBlank()) {
                        val tops = stickerSectionViews.map { it.top }
                        val bottomReached = scrollY > 0 && !v.canScrollVertically(1)
                        val changed = stickerScrollTabController.onVerticalScroll(
                            sectionTops = tops,
                            scrollY = scrollY,
                            bottomReached = bottomReached,
                            anchorOffsetPx = dp(32),
                            settleTolerancePx = dp(4),
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        if (changed) {
                            selectedStickerSectionIdx = stickerScrollTabController.activeTabIdx
                            updateStickerCategoryTabsUI()
                        }
                    }
                }
            }
        }
        stickerScrollView = scroll

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        stickerSectionViews.clear()

        val installedIds = stickerPackManager.installedPackIds()
        val installedPacks = stickerPackManager.installedPacks()

        if (mediaSearchQuery.isNotBlank()) {
            val matches = StickerCatalog.search(mediaSearchQuery, installedIds.takeIf { it.isNotEmpty() })
            if (matches.isEmpty()) {
                body.addView(TextView(this).apply {
                    text = AppUiStrings.emptyStickersMessage(keyboardLanguage)
                    textSize = 13f
                    setTextColor(colorHintText)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(32), dp(16), dp(16))
                })
            } else {
                addStickerGridRows(body, matches, cols, tileHeightPx, gapPx, gen)
            }
        } else {
            // Section 0: Recent stickers
            val recentSec = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val recentItems = recentStickerHistory.items().filter { it.packId in installedIds }
            if (recentItems.isNotEmpty()) {
                recentSec.addView(TextView(this).apply {
                    text = AppUiStrings.emojiRecentLabel(keyboardLanguage)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorHintText)
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(dp(4), dp(4), dp(4), dp(6))
                })
                addStickerGridRows(recentSec, recentItems, cols, tileHeightPx, gapPx, gen)
            }
            stickerSectionViews.add(recentSec)
            body.addView(recentSec)

            // Section 1: Favourites (long-press any sticker to save)
            val favSec = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val favItems = favoriteStickers.items().filter { it.packId in installedIds }
            favSec.addView(TextView(this).apply {
                text = AppUiStrings.stickerFavoritesLabel(keyboardLanguage)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorHintText)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(dp(4), dp(4), dp(4), dp(6))
            })
            if (favItems.isEmpty()) {
                favSec.addView(TextView(this).apply {
                    text = AppUiStrings.stickerFavoritesEmpty(keyboardLanguage)
                    textSize = 11.5f
                    setTextColor(colorHintText)
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(dp(6), dp(2), dp(6), dp(6))
                })
            } else {
                addStickerGridRows(favSec, favItems, cols, tileHeightPx, gapPx, gen)
            }
            stickerSectionViews.add(favSec)
            body.addView(favSec)

            // Sections 2..: Installed Sticker Packs
            if (installedPacks.isEmpty()) {
                val emptyCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(14), dp(18), dp(14), dp(18))
                    background = roundedDrawable(if (isClassicTheme) colorKeyBg else colorChipBg, dp(12f), colorKeyBorder)
                }
                emptyCard.addView(TextView(this).apply {
                    text = AppUiStrings.stickerPacksNoneInstalled(keyboardLanguage)
                    textSize = 12.5f
                    setTextColor(colorKeyText)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(10))
                })
                emptyCard.addView(TextView(this).apply {
                    text = AppUiStrings.stickerPackInstallAllLabel(keyboardLanguage)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccentText)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = roundedRipple(colorAccent, dp(8f), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        isStickerPackManagerOpen = true
                        renderFullImeView()
                    }
                })
                body.addView(emptyCard)
            } else {
                for (pack in installedPacks) {
                    val packSec = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(10)
                        }
                    }
                    packSec.addView(TextView(this).apply {
                        val title = if (AppUiStrings.isArabic(keyboardLanguage)) pack.titleAr else pack.titleEn
                        text = "$title (${pack.items.size})"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(colorHintText)
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                    })
                    addStickerGridRows(packSec, pack.items, cols, tileHeightPx, gapPx, gen)
                    stickerSectionViews.add(packSec)
                    body.addView(packSec)
                }
            }
            updateStickerCategoryTabsUI()
        }

        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun buildStickersPackManagerBody(): View {
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        stickerStoreHost = host
        populateStickerPackStoreContent(host)
        scroll.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun populateStickerPackStoreContent(container: LinearLayout) {
        container.removeAllViews()
        val gen = mediaViewportGen
        val cardBg = if (isClassicTheme) colorKeyBg else colorChipBg
        val allInstalled = StickerCatalog.PACKS.all { stickerPackManager.isPackInstalled(it.packId) }

        if (!allInstalled) {
            val summaryRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedDrawable(cardBg, dp(12f), colorKeyBorder)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            summaryRow.addView(TextView(this).apply {
                val installedCount = stickerPackManager.installedPacks().size
                text = "$installedCount/${StickerCatalog.PACKS.size} · ${MediaKeyboardSpec.StickerPackStorePolicy.formatStorageSize(stickerPackManager.totalDiskBytes())}"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val installAllBtn = TextView(this).apply {
                text = AppUiStrings.stickerPackInstallAllLabel(keyboardLanguage)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccentText)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = roundedRipple(colorAccent, dp(8f), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    stickerPackManager.installAllPacksAsync(
                        onEachUpdate = {
                            if (!serviceDestroyed && isStickerPackManagerOpen) {
                                stickerStoreHost?.let(::populateStickerPackStoreContent)
                            }
                        },
                        onComplete = {
                            if (!serviceDestroyed && isStickerPackManagerOpen) {
                                stickerStoreHost?.let(::populateStickerPackStoreContent)
                            }
                        }
                    )
                }
            }
            summaryRow.addView(installAllBtn)
            container.addView(summaryRow)
        }

        for (pack in StickerCatalog.PACKS) {
            val installed = stickerPackManager.isPackInstalled(pack.packId)
            val progress = stickerPackManager.activeProgress(pack.packId)
            val err = stickerPackManager.lastError(pack.packId)
            val sizeBytes = if (installed) stickerPackManager.packDiskBytes(pack.packId) else pack.estimatedSizeBytes
            val sizeLabel = MediaKeyboardSpec.StickerPackStorePolicy.formatStorageSize(sizeBytes)
            val title = if (AppUiStrings.isArabic(keyboardLanguage)) pack.titleAr else pack.titleEn
            val desc = if (AppUiStrings.isArabic(keyboardLanguage)) pack.descAr else pack.descEn

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = uiLayoutDirection
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedDrawable(cardBg, dp(12f), if (installed) colorAccent else colorKeyBorder)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
            }
            val iconIv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            loadStickerBitmapAsync(pack.iconAssetPath, iconIv, gen, pack.items.firstOrNull())
            topRow.addView(iconIv, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            infoCol.addView(TextView(this).apply {
                text = "$title · ${pack.items.size} · $sizeLabel"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
            infoCol.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(colorHintText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            })
            topRow.addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })

            if (progress != null) {
                topRow.addView(TextView(this).apply {
                    text = "$progress%"
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                })
            } else {
                val actionBtn = TextView(this).apply {
                    text = if (installed) AppUiStrings.stickerPackRemoveLabel(keyboardLanguage)
                    else AppUiStrings.stickerPackInstallLabel(keyboardLanguage)
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (installed) colorModText else colorAccentText)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = roundedRipple(if (installed) colorModBg else colorAccent, dp(8f), if (installed) colorKeyBorder else 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (installed) {
                            stickerPackManager.uninstallPack(pack.packId)
                            populateStickerPackStoreContent(container)
                        } else {
                            stickerPackManager.installPackAsync(
                                packId = pack.packId,
                                onProgress = {
                                    if (!serviceDestroyed && isStickerPackManagerOpen) {
                                        populateStickerPackStoreContent(container)
                                    }
                                },
                                onComplete = { _, _ ->
                                    if (!serviceDestroyed && isStickerPackManagerOpen) {
                                        populateStickerPackStoreContent(container)
                                    }
                                }
                            )
                            populateStickerPackStoreContent(container)
                        }
                    }
                }
                topRow.addView(actionBtn)
            }
            card.addView(topRow)

            // Sample preview strip of 4 stickers from the pack
            val stripRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                setPadding(0, dp(8), 0, 0)
            }
            for (sample in pack.items.take(4)) {
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    background = roundedDrawable(if (isClassicTheme) colorAltDeepBg else colorModBg, dp(8f))
                }
                loadStickerBitmapAsync(sample.assetPath, thumb, gen, sample)
                stripRow.addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })
            }
            card.addView(stripRow)

            if (progress != null) {
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    this.progress = progress
                }
                card.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                    topMargin = dp(6)
                })
            } else if (err != null) {
                val errRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = uiLayoutDirection
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                }
                errRow.addView(TextView(this).apply {
                    text = err
                    textSize = 11f
                    setTextColor(colorDangerText)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                errRow.addView(TextView(this).apply {
                    text = AppUiStrings.retryLabel(keyboardLanguage)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccentText)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = roundedRipple(colorAccent, dp(6f), 0)
                    setOnClickListener {
                        stickerPackManager.clearError(pack.packId)
                        stickerPackManager.installPackAsync(pack.packId) {
                            if (!serviceDestroyed && isStickerPackManagerOpen) {
                                populateStickerPackStoreContent(container)
                            }
                        }
                    }
                })
                card.addView(errRow)
            }

            container.addView(card)
        }
    }

    private fun commitRichMediaFile(
        file: File,
        mimeType: String,
        description: String,
        linkUri: android.net.Uri? = null,
        showFailureFallback: Boolean = true
    ): Boolean {
        val conn = currentInputConnection
        if (conn == null) {
            if (showFailureFallback) {
                showRichMediaFallbackBanner(AppUiStrings.noActiveTextFieldStatus(keyboardLanguage), file, mimeType, description)
            } else {
                showMediaStatusBanner(AppUiStrings.noActiveTextFieldStatus(keyboardLanguage))
            }
            return false
        }
        val info = currentInputEditorInfo
        val mimeSupported = MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(info?.contentMimeTypes, mimeType)
        if (!mimeSupported || !file.isFile || file.length() <= 0L) {
            if (showFailureFallback) {
                showRichMediaFallbackBanner(AppUiStrings.richMediaUnsupportedStatus(keyboardLanguage), file, mimeType, description)
            } else {
                showMediaStatusBanner(AppUiStrings.richMediaUnsupportedStatus(keyboardLanguage))
            }
            return false
        }
        val contentUri = MediaContentProvider.uriForFile(file.name)
        info?.packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
            runCatching { grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val clipDesc = ClipDescription(description, arrayOf(mimeType))
        val inputContentInfo = InputContentInfo(contentUri, clipDesc, linkUri)
        val committed = runCatching {
            conn.commitContent(inputContentInfo, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
        if (!committed && showFailureFallback) {
            showRichMediaFallbackBanner(AppUiStrings.richMediaUnsupportedStatus(keyboardLanguage), file, mimeType, description)
        }
        return committed
    }

    private fun handleStickerPress(item: StickerCatalog.StickerItem) {
        dismissPasteUndoCapsule()
        val info = currentInputEditorInfo
        val outcome = MediaKeyboardSpec.MediaRichContentPolicy.evaluateMediaEligibility(
            hasActiveConnection = currentInputConnection != null,
            inputType = info?.inputType ?: 0,
            isPasswordTarget = isPasswordTarget,
            acceptedMimeTypes = info?.contentMimeTypes
        )
        val bestMime = if (outcome == MediaKeyboardSpec.MediaRichContentPolicy.MediaFallbackOutcome.ELIGIBLE) {
            MediaKeyboardSpec.MediaRichContentPolicy.resolveBestStickerMime(info?.contentMimeTypes) ?: "image/png"
        } else {
            "image/png"
        }
        val pngCacheName = MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForSticker(item, "image/png")
        val cachedPng = mediaCacheManager.getCachedFile(pngCacheName)

        fun executeWithPng(pngFile: File) {
            if (outcome != MediaKeyboardSpec.MediaRichContentPolicy.MediaFallbackOutcome.ELIGIBLE) {
                val msg = if (outcome == MediaKeyboardSpec.MediaRichContentPolicy.MediaFallbackOutcome.NO_ACTIVE_FIELD) {
                    AppUiStrings.noActiveTextFieldStatus(keyboardLanguage)
                } else {
                    AppUiStrings.richMediaUnsupportedStatus(keyboardLanguage)
                }
                showRichMediaFallbackBanner(msg, pngFile, "image/png", item.title)
                return
            }
            if (bestMime == "image/webp") {
                val webpName = MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForSticker(item, "image/webp")
                val cachedWebp = mediaCacheManager.getCachedFile(webpName)
                if (cachedWebp != null) {
                    if (commitRichMediaFile(cachedWebp, "image/webp", item.title)) {
                        recentStickerHistory.record(item.id)
                        persistRecentStickers()
                    }
                    return
                }
                submitSafely(mediaIoExecutor) {
                    val webpFile = mediaCacheManager.ensureWebpFromPngCached(pngFile, webpName) ?: pngFile
                    val finalMime = if (webpFile.name.endsWith(".webp")) "image/webp" else "image/png"
                    main.post {
                        if (serviceDestroyed) return@post
                        if (commitRichMediaFile(webpFile, finalMime, item.title)) {
                            recentStickerHistory.record(item.id)
                            persistRecentStickers()
                        }
                    }
                }
            } else {
                if (commitRichMediaFile(pngFile, "image/png", item.title)) {
                    recentStickerHistory.record(item.id)
                    persistRecentStickers()
                }
            }
        }

        if (cachedPng != null) {
            executeWithPng(cachedPng)
            return
        }
        submitSafely(mediaIoExecutor) {
            val file = stickerPackManager.ensureStickerCachedForCommit(item, mediaCacheManager)
            main.post {
                if (serviceDestroyed) return@post
                if (file == null) {
                    showMediaStatusBanner(AppUiStrings.mediaSaveFailedStatus(keyboardLanguage))
                    return@post
                }
                executeWithPng(file)
            }
        }
    }

    // -------------------------------------------------------------------------
    // KAOMOJI BROWSER (All 13 Categories + Actual Kaomoji Entries + Search)
    // -------------------------------------------------------------------------
    private fun updateKaomojiCategoryTabsUI() {
        val activeIdx = selectedKaomojiCategoryIdx.coerceIn(0, KaomojiCatalog.CATEGORIES.size)
        var activeView: View? = null

        val recentTab = kaomojiRecentTabView
        if (recentTab != null) {
            val recentActive = (activeIdx == 0)
            if (recentActive) activeView = recentTab
            val bg = if (recentActive) colorActiveTabBg else 0
            val fg = if (recentActive) colorActiveTabText else colorModText
            val stroke = if (recentActive) (if (isClassicTheme) colorAccent else colorChipBorder) else 0
            recentTab.background = roundedRipple(bg, dp(16f), stroke)
            (recentTab.tag as? ImageView)?.setImageDrawable(IconDrawable(IconType.EMOJI_RECENT, fg, 16.5f))
        }

        for ((tabIdx, pill) in kaomojiTabViews) {
            val active = (tabIdx == activeIdx)
            if (active) activeView = pill
            val bg = if (active) colorActiveTabBg else 0
            val fg = if (active) colorActiveTabText else colorModText
            val stroke = if (active) (if (isClassicTheme) colorAccent else colorChipBorder) else 0
            pill.setTextColor(fg)
            pill.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            pill.background = roundedRipple(bg, dp(16f), stroke)
        }
        val tabsScroll = kaomojiTabsScrollView
        if (tabsScroll != null && activeView != null && tabsScroll.width > 0) {
            val targetX = activeView!!.left - (tabsScroll.width - activeView!!.width) / 2
            tabsScroll.smoothScrollTo(targetX.coerceAtLeast(0), 0)
        }
    }

    private fun addKaomojiGridRows(
        container: LinearLayout,
        items: List<String>,
        cols: Int,
        cellH: Int,
        gapPx: Int
    ) {
        val cardBg = if (isClassicTheme) colorKeyBg else 0x00000000
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH).apply {
                    bottomMargin = gapPx
                }
            }
            for (c in 0 until cols) {
                val idx = i + c
                if (idx < items.size) {
                    val kaomoji = items[idx]
                    val tv = TextView(this).apply {
                        text = kaomoji
                        textSize = 13.5f
                        setTextColor(colorKeyText)
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setSingleLine(true)
                        setPadding(dp(2), 0, dp(2), 0)
                        isClickable = true
                        isFocusable = true
                        background = roundedRipple(cardBg, dp(10f), if (isClassicTheme) colorKeyBorder else 0)
                        setOnClickListener {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            handleKaomojiPress(kaomoji)
                        }
                    }
                    row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (c < cols - 1) marginEnd = gapPx
                    })
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (c < cols - 1) marginEnd = gapPx
                    })
                }
            }
            container.addView(row)
            i += cols
        }
    }

    private fun buildKaomojiBrowserBody(): View {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val cols = if (isLandscape) MediaKeyboardSpec.KAOMOJI_COLS_LANDSCAPE else MediaKeyboardSpec.KAOMOJI_COLS_PORTRAIT
        val cellH = dp(if (isLandscape) 38 else 46)
        val gapPx = dp(4)

        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    kaomojiScrollTabController.onUserTouchInteraction()
                }
                false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { v, _, scrollY, _, _ ->
                    if (mediaSearchQuery.isBlank()) {
                        val tops = kaomojiSectionViews.map { it.top }
                        val bottomReached = scrollY > 0 && !v.canScrollVertically(1)
                        val changed = kaomojiScrollTabController.onVerticalScroll(
                            sectionTops = tops,
                            scrollY = scrollY,
                            bottomReached = bottomReached,
                            anchorOffsetPx = dp(32),
                            settleTolerancePx = dp(4),
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        if (changed) {
                            selectedKaomojiCategoryIdx = kaomojiScrollTabController.activeTabIdx
                            updateKaomojiCategoryTabsUI()
                        }
                    }
                }
            }
        }
        kaomojiScrollView = scroll

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
            setPadding(dp(6), dp(6), dp(6), dp(10))
        }
        kaomojiSectionViews.clear()

        if (mediaSearchQuery.isNotBlank()) {
            val matches = KaomojiCatalog.search(mediaSearchQuery)
            if (matches.isEmpty()) {
                body.addView(TextView(this).apply {
                    text = if (AppUiStrings.isArabic(keyboardLanguage)) "لا توجد رموز كاوماوجي مطابقة" else "No matching kaomoji"
                    textSize = 13f
                    setTextColor(colorHintText)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(32), dp(16), dp(16))
                })
            } else {
                addKaomojiGridRows(body, matches, cols, cellH, gapPx)
            }
        } else {
            // Section 0: Recent Kaomoji
            val recentSec = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val recentEntries = recentKaomojiHistory.items()
            if (recentEntries.isNotEmpty()) {
                recentSec.addView(TextView(this).apply {
                    text = AppUiStrings.emojiRecentLabel(keyboardLanguage)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorHintText)
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                })
                addKaomojiGridRows(recentSec, recentEntries, cols, cellH, gapPx)
            }
            kaomojiSectionViews.add(recentSec)
            body.addView(recentSec)

            // Sections 1..13: All 13 Kaomoji Categories + Actual Entries
            for (cat in KaomojiCatalog.CATEGORIES) {
                val catSec = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }
                }
                catSec.addView(TextView(this).apply {
                    text = if (AppUiStrings.isArabic(keyboardLanguage)) cat.labelAr else cat.labelEn
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorHintText)
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(dp(8), dp(6), dp(8), dp(4))
                })
                addKaomojiGridRows(catSec, cat.items, cols, cellH, gapPx)
                kaomojiSectionViews.add(catSec)
                body.addView(catSec)
            }
            updateKaomojiCategoryTabsUI()
        }

        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun handleKaomojiPress(kaomoji: String) {
        dismissPasteUndoCapsule()
        shiftStateMachine.onNonLetterInput()
        if (isEmojiPickerOpen && isMediaSearchTyping) {
            mediaSearchQuery += kaomoji
            updateMediaSearchUI()
            renderMediaSearchStripResults()
        } else if (isFilterMode) {
            keyboardSearchQuery += kaomoji
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection
            if (conn == null) {
                showMediaStatusBanner(AppUiStrings.noActiveTextFieldStatus(keyboardLanguage))
                return
            }
            insertionHistory.invalidateImmediateBackspaceRevert()
            lastRevertOriginalWord = null
            clearTypingSuggestionsView()
            if (commitTextSafely(conn, kaomoji, 1)) {
                shadowEditor.onCommitText(kaomoji)
            }
            updateInsertionHistoryButtons()
            syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
        }
        recentKaomojiHistory.record(kaomoji)
        persistRecentKaomojis()
    }

    // -------------------------------------------------------------------------
    // INLINE KEYBOARD INTERFACE FOR MEDIA SEARCH
    // -------------------------------------------------------------------------
    private fun buildMediaSearchInterface(viewport: KeyboardLayoutSpec.ViewportSpecDp): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = uiLayoutDirection
        setBackgroundColor(colorBg)
        setPadding(dp(6), dp(viewport.searchTopPaddingDp).toInt(), dp(6), dp(viewport.searchBottomPaddingDp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(viewport.searchInterfaceHeightDp).toInt()
        )

        val barH = dp(viewport.searchBarHeightDp).toInt()
        val pillH = (barH - dp(2)).coerceAtLeast(dp(20))

        val barRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH)
        }

        val pill = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
            background = roundedDrawable(colorKeyBg, dp(8f), colorAccent)
            layoutParams = LinearLayout.LayoutParams(0, pillH, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val placeholder = when (currentMediaSubMode) {
            MediaKeyboardSpec.MediaSubMode.EMOJI -> AppUiStrings.searchEmojisPlaceholder(keyboardLanguage)
            MediaKeyboardSpec.MediaSubMode.STICKERS -> AppUiStrings.searchStickersPlaceholder(keyboardLanguage)
            MediaKeyboardSpec.MediaSubMode.KAOMOJI -> AppUiStrings.searchKaomojiPlaceholder(keyboardLanguage)
        }

        val queryTv = TextView(this@ClipMaxImeService).apply {
            text = if (mediaSearchQuery.isEmpty()) placeholder else mediaSearchQuery
            textSize = 11.5f
            setTextColor(if (mediaSearchQuery.isEmpty()) colorHintText else colorKeyText)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = if (mediaSearchQuery.isEmpty()) View.TEXT_DIRECTION_LOCALE else View.TEXT_DIRECTION_ANY_RTL
            setIcon(IconDrawable(IconType.SEARCH, colorAccent, 12f), 5f)
        }
        mediaSearchInputText = queryTv
        pill.addView(queryTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val clearBtn = FrameLayout(this@ClipMaxImeService).apply {
            visibility = if (mediaSearchQuery.isEmpty()) View.GONE else View.VISIBLE
            contentDescription = AppUiStrings.clearSearchQueryA11y(keyboardLanguage)
            background = roundedRipple(colorModBg, dp(10f), 0)
            val iv = createIconView(IconDrawable(IconType.CLOSE, colorModText, 10f))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                mediaSearchQuery = ""
                updateMediaSearchUI()
                renderMediaSearchStripResults()
            }
        }
        mediaSearchClearBtn = clearBtn
        pill.addView(clearBtn, LinearLayout.LayoutParams(dp(20), dp(20)))
        barRow.addView(pill)

        val doneBtn = Button(this@ClipMaxImeService).apply {
            text = AppUiStrings.doneButton(keyboardLanguage)
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
                isMediaSearchTyping = false
                renderFullImeView()
            }
        }
        barRow.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillH))
        addView(barRow)

        val resultsScroll = HorizontalScrollView(this@ClipMaxImeService).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = uiLayoutDirection
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(viewport.searchResultsHeightDp).toInt()).apply {
                topMargin = dp(viewport.searchResultsTopMarginDp).toInt()
            }
        }
        val resultsRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = uiLayoutDirection
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(2), 0, dp(8), 0)
        }
        mediaSearchResultsStrip = resultsRow
        resultsScroll.addView(resultsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(resultsScroll)
    }

    private fun updateMediaSearchUI() {
        val placeholder = when (currentMediaSubMode) {
            MediaKeyboardSpec.MediaSubMode.EMOJI -> AppUiStrings.searchEmojisPlaceholder(keyboardLanguage)
            MediaKeyboardSpec.MediaSubMode.STICKERS -> AppUiStrings.searchStickersPlaceholder(keyboardLanguage)
            MediaKeyboardSpec.MediaSubMode.KAOMOJI -> AppUiStrings.searchKaomojiPlaceholder(keyboardLanguage)
        }
        mediaSearchInputText?.text = if (mediaSearchQuery.isEmpty()) placeholder else mediaSearchQuery
        mediaSearchInputText?.setTextColor(if (mediaSearchQuery.isEmpty()) colorHintText else colorKeyText)
        mediaSearchClearBtn?.visibility = if (mediaSearchQuery.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderMediaSearchStripResults() {
        val container = mediaSearchResultsStrip ?: return
        container.removeAllViews()
        val chipH = (dp(computeCurrentViewportSpec().searchResultsHeightDp).toInt() - dp(4)).coerceAtLeast(dp(26))

        when (currentMediaSubMode) {
            MediaKeyboardSpec.MediaSubMode.EMOJI -> {
                val list = if (mediaSearchQuery.isBlank()) recentEmojiHistory.items().ifEmpty { EmojiCatalog.SMILEYS_EMOJIS.take(16) }
                else EmojiCatalog.search(mediaSearchQuery).take(24)
                if (list.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = if (AppUiStrings.isArabic(keyboardLanguage)) "لا توجد نتائج" else "No matching emojis"
                        textSize = 11f
                        setTextColor(colorHintText)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    })
                    return
                }
                list.forEach { emoji ->
                    val tv = TextView(this).apply {
                        text = emoji
                        textSize = 20f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setPadding(dp(8), 0, dp(8), 0)
                        background = roundedRipple(colorChipBg, dp(10f), colorChipBorder)
                        setOnClickListener {
                            isMediaSearchTyping = false
                            handleEmojiPress(emoji)
                            renderFullImeView()
                        }
                    }
                    container.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, chipH).apply { marginEnd = dp(6) })
                }
            }

            MediaKeyboardSpec.MediaSubMode.STICKERS -> {
                val list = StickerCatalog.search(mediaSearchQuery).take(16)
                if (list.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = AppUiStrings.emptyStickersMessage(keyboardLanguage)
                        textSize = 11f
                        setTextColor(colorHintText)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    })
                    return
                }
                val gen = mediaViewportGen
                list.forEach { st ->
                    val tile = FrameLayout(this).apply {
                        contentDescription = st.title
                        background = roundedRipple(colorChipBg, dp(8f), colorChipBorder)
                        val iv = ImageView(this@ClipMaxImeService).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                        }
                        loadStickerBitmapAsync(st.assetPath, iv, gen)
                        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        setOnClickListener {
                            isMediaSearchTyping = false
                            renderFullImeView()
                            handleStickerPress(st)
                        }
                    }
                    container.addView(tile, LinearLayout.LayoutParams(chipH, chipH).apply { marginEnd = dp(6) })
                }
            }

            MediaKeyboardSpec.MediaSubMode.KAOMOJI -> {
                val list = if (mediaSearchQuery.isBlank()) KaomojiCatalog.CATEGORIES.first().items.take(16)
                else KaomojiCatalog.search(mediaSearchQuery).take(24)
                if (list.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = if (AppUiStrings.isArabic(keyboardLanguage)) "لا توجد نتائج" else "No matching kaomoji"
                        textSize = 11f
                        setTextColor(colorHintText)
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                    })
                    return
                }
                list.forEach { kao ->
                    val tv = TextView(this).apply {
                        text = kao
                        textSize = 12.5f
                        setTextColor(colorKeyText)
                        gravity = Gravity.CENTER
                        setPadding(dp(10), 0, dp(10), 0)
                        background = roundedRipple(colorChipBg, dp(10f), colorChipBorder)
                        setOnClickListener {
                            isMediaSearchTyping = false
                            handleKaomojiPress(kao)
                            renderFullImeView()
                        }
                    }
                    container.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, chipH).apply { marginEnd = dp(6) })
                }
            }
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

        val existingLp = popup.layoutParams as? FrameLayout.LayoutParams
        if (existingLp == null || existingLp.width != targetWidth || existingLp.height != targetHeight ||
            existingLp.leftMargin != 0 || existingLp.topMargin != 0
        ) {
            val lp = existingLp ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
            lp.width = targetWidth
            lp.height = targetHeight
            lp.leftMargin = 0
            lp.topMargin = 0
            popup.layoutParams = lp
        }

        val minX = dp(2).toFloat()
        val maxX = (root.width - targetWidth - dp(2)).toFloat().coerceAtLeast(minX)
        popup.translationX = (relLeft + (keyView.width - targetWidth) / 2).toFloat().coerceIn(minX, maxX)
        popup.translationY = (relTop - targetHeight + dp(2)).toFloat().coerceAtLeast(0f)
        if (popup.visibility != View.VISIBLE) {
            popup.visibility = View.VISIBLE
            popup.bringToFront()
        }
    }

    private fun hideKeyPreview() {
        keyPopupView?.visibility = View.GONE
    }

    private fun attachKeyTouchFeedback(keyView: View, onCommit: () -> Unit, previewChar: (() -> String)? = null) {
        keyView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.isPressed = false
                hideKeyPreview()
            }
        })
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
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
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
        var touchSessionGeneration = 0
        var activeTask: Runnable? = null

        fun cancelPendingLongPress() {
            touchSessionGeneration++
            activeTask?.let(main::removeCallbacks)
            activeTask = null
        }

        keyView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                cancelPendingLongPress()
                controller.onCancel()
                v.isPressed = false
                hideKeyPreview()
            }
        })

        keyView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingLongPress()
                    val gen = touchSessionGeneration
                    controller.onDown()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val displayChar = if (shiftState == 0) {
                        primaryChar.lowercase(Locale.ROOT)
                    } else {
                        primaryChar.uppercase(Locale.ROOT)
                    }
                    showKeyPreview(v, displayChar)
                    val task = Runnable {
                        if (gen != touchSessionGeneration || serviceDestroyed || !v.isAttachedToWindow) return@Runnable
                        if (controller.onLongPressTimeout()) {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showKeyPreview(v, longPressChar)
                            handleGenericCharPress(longPressChar)
                        }
                    }
                    activeTask = task
                    main.postDelayed(task, thresholdMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    val inside = x >= -dp(12) && x <= v.width + dp(12) && y >= -dp(24) && y <= v.height + dp(12)
                    if (!inside && controller.onMove(insideBounds = false)) {
                        cancelPendingLongPress()
                        v.isPressed = false
                        hideKeyPreview()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingLongPress()
                    v.isPressed = false
                    hideKeyPreview()
                    if (controller.onUp()) {
                        handleLetterPress(primaryChar)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    cancelPendingLongPress()
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

    private fun createCommaEmojiKey(commaChar: String = ","): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = AppUiStrings.commaEmojiA11y(keyboardLanguage)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = commaChar
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
        var touchSessionGeneration = 0
        var activeTask: Runnable? = null

        fun cancelPendingLongPress() {
            touchSessionGeneration++
            activeTask?.let(main::removeCallbacks)
            activeTask = null
        }

        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                cancelPendingLongPress()
                controller.onCancel()
                v.isPressed = false
                hideKeyPreview()
            }
        })

        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingLongPress()
                    val gen = touchSessionGeneration
                    controller.onDown()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showKeyPreview(v, commaChar)
                    val task = Runnable {
                        if (gen != touchSessionGeneration || serviceDestroyed || !v.isAttachedToWindow) return@Runnable
                        if (controller.onLongPressTimeout()) {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            hideKeyPreview()
                            dismissPasteUndoCapsule()
                            shiftStateMachine.resetDoubleTapTimer()
                            isEmojiPickerOpen = true
                            isMediaSearchTyping = false
                            isStickerPackManagerOpen = false
                            isClipboardPanelOpen = false
                            isSettingsOpen = false
                            renderFullImeView()
                        }
                    }
                    activeTask = task
                    main.postDelayed(task, thresholdMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    val inside = x >= -dp(12) && x <= v.width + dp(12) && y >= -dp(24) && y <= v.height + dp(12)
                    if (!inside && controller.onMove(insideBounds = false)) {
                        cancelPendingLongPress()
                        v.isPressed = false
                        hideKeyPreview()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingLongPress()
                    v.isPressed = false
                    hideKeyPreview()
                    if (controller.onUp()) {
                        handleGenericCharPress(commaChar)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    cancelPendingLongPress()
                    controller.onCancel()
                    v.isPressed = false
                    hideKeyPreview()
                    true
                }
                else -> false
            }
        }
    }

    private fun createArabicKey(primary: String, hintDigit: String?): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)

        val isLetter = primary.length == 1 && primary[0].isLetter()
        val tv = TextView(this@ClipMaxImeService).apply {
            text = primary
            textSize = if (isLetter) currentLayoutGeometry.primaryTextSizeSp else (currentLayoutGeometry.primaryTextSizeSp - 2f).coerceAtLeast(15f)
            typeface = Typeface.DEFAULT
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            if (hintDigit != null) {
                setPadding(0, dp(2), 0, 0)
            }
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        if (hintDigit != null) {
            val hintTv = TextView(this@ClipMaxImeService).apply {
                text = hintDigit
                textSize = currentLayoutGeometry.numberLegendSizeSp
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorHintText)
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(2), dp(4), 0)
            }
            addView(hintTv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val variants = KeyboardLayoutSpec.arabicVariantsFor(primary)
        if (variants.isNotEmpty()) {
            attachArabicVariantTouchFeedback(
                keyView = this,
                primaryChar = primary,
                variants = variants,
                preselectFirstOnTimeout = (hintDigit != null || variants.size == 1)
            )
        } else {
            attachKeyTouchFeedback(
                keyView = this,
                onCommit = {
                    if (isLetter) handleLetterPress(primary) else handleGenericCharPress(primary)
                },
                previewChar = { primary }
            )
        }
    }

    private fun createArabicPeriodKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)
        contentDescription = AppUiStrings.arabicPeriodA11y(keyboardLanguage)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = "."
            textSize = (currentLayoutGeometry.primaryTextSizeSp - 1.5f).coerceAtLeast(15f)
            typeface = Typeface.DEFAULT
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Subtle dotted-circle diacritics hint indicator matching reference screenshot
        val hintTv = TextView(this@ClipMaxImeService).apply {
            text = "\u25CC"
            textSize = 9f
            setTextColor(colorHintText)
            gravity = Gravity.TOP or Gravity.END
            includeFontPadding = false
            setPadding(0, dp(2), dp(5), 0)
        }
        addView(hintTv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachArabicVariantTouchFeedback(
            keyView = this,
            primaryChar = ".",
            variants = KeyboardLayoutSpec.ARABIC_PERIOD_VARIANTS,
            preselectFirstOnTimeout = false
        )
    }

    private fun commitArabicToken(token: String) {
        val isLetterOrMark = token.length == 1 &&
            (token[0].isLetter() || KeyboardLayoutSpec.isArabicCombiningMark(token[0].code))
        if (isLetterOrMark) {
            handleLetterPress(token)
        } else {
            handleGenericCharPress(token)
        }
    }

    private fun dismissVariantPopup() {
        variantPopupInteractiveTapMode = false
        val surface = imeSurfaceFrame
        val popup = variantPopupHost
        variantPopupHost = null
        if (surface != null && popup != null) {
            surface.removeView(popup)
        }
    }

    private fun showArabicVariantPopup(
        anchorView: View,
        variants: List<String>,
        preselectFirst: Boolean
    ): List<Pair<View, String>> {
        val surface = imeSurfaceFrame ?: return emptyList()
        dismissVariantPopup()
        dismissLanguagePopup()
        if (variants.isEmpty()) return emptyList()

        val chipViews = ArrayList<Pair<View, String>>(variants.size)
        val maxCols = if (variants.size > 7) 7 else variants.size
        val chipW = dp(if (variants.size > 7) 38 else 40)
        val chipH = dp(38)
        val chipGap = dp(4)

        val popupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedDrawable(
                if (isClassicTheme) colorKeyBg else colorChipBg,
                dp(14f),
                colorKeyBorder
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(12f)
            }
            isClickable = true
        }

        var idx = 0
        while (idx < variants.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            val rowEnd = minOf(idx + maxCols, variants.size)
            for (c in idx until rowEnd) {
                val token = variants[c]
                val isSelected = preselectFirst && c == 0
                val chip = TextView(this).apply {
                    text = KeyboardLayoutSpec.displayLabelForArabicVariant(token)
                    textSize = 18f
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(if (isSelected) colorActiveTabText else colorKeyText)
                    background = roundedRipple(
                        normalColor = if (isSelected) colorActiveTabBg else colorKeyBg,
                        cornerRadiusPx = dp(9f),
                        strokeColor = if (isSelected) colorAccent else colorKeyBorder
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        commitArabicToken(token)
                        dismissVariantPopup()
                    }
                }
                chipViews.add(chip to token)
                row.addView(chip, LinearLayout.LayoutParams(chipW, chipH).apply {
                    if (c < rowEnd - 1) rightMargin = chipGap
                })
            }
            popupCard.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (rowEnd < variants.size) bottomMargin = chipGap
            })
            idx = rowEnd
        }

        popupCard.measure(
            View.MeasureSpec.makeMeasureSpec(surface.width.coerceAtLeast(dp(240)), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(surface.height.coerceAtLeast(dp(120)), View.MeasureSpec.AT_MOST)
        )
        val popupW = popupCard.measuredWidth
        val popupH = popupCard.measuredHeight

        val anchorLoc = IntArray(2)
        val surfaceLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        surface.getLocationOnScreen(surfaceLoc)
        val relLeft = anchorLoc[0] - surfaceLoc[0]
        val relTop = anchorLoc[1] - surfaceLoc[1]

        val minLeft = dp(6)
        val maxLeft = (surface.width - popupW - dp(6)).coerceAtLeast(minLeft)
        val clampedLeft = (relLeft + (anchorView.width - popupW) / 2).coerceIn(minLeft, maxLeft)
        val clampedTop = (relTop - popupH - dp(6)).coerceAtLeast(dp(4))

        val lp = FrameLayout.LayoutParams(popupW, popupH).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = clampedLeft
            topMargin = clampedTop
        }
        variantPopupHost = popupCard
        surface.addView(popupCard, lp)
        popupCard.bringToFront()
        return chipViews
    }

    private fun updateVariantChipHighlight(chips: List<Pair<View, String>>, selectedIdx: Int) {
        chips.forEachIndexed { i, (v, _) ->
            val tv = v as? TextView ?: return@forEachIndexed
            val selected = i == selectedIdx
            tv.setTextColor(if (selected) colorActiveTabText else colorKeyText)
            tv.background = roundedRipple(
                normalColor = if (selected) colorActiveTabBg else colorKeyBg,
                cornerRadiusPx = dp(9f),
                strokeColor = if (selected) colorAccent else colorKeyBorder
            )
        }
    }

    private fun attachArabicVariantTouchFeedback(
        keyView: View,
        primaryChar: String,
        variants: List<String>,
        preselectFirstOnTimeout: Boolean
    ) {
        val controller = KeyboardLayoutSpec.LongPressKeyController()
        val thresholdMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(320L, 420L)
        var popupChips: List<Pair<View, String>> = emptyList()
        var selectedVariantIdx = -1
        var touchSessionGeneration = 0
        var activeTask: Runnable? = null

        fun cancelPendingLongPress() {
            touchSessionGeneration++
            activeTask?.let(main::removeCallbacks)
            activeTask = null
        }

        keyView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                cancelPendingLongPress()
                controller.onCancel()
                v.isPressed = false
                hideKeyPreview()
                dismissVariantPopup()
                popupChips = emptyList()
                selectedVariantIdx = -1
            }
        })

        keyView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingLongPress()
                    val gen = touchSessionGeneration
                    dismissVariantPopup()
                    popupChips = emptyList()
                    selectedVariantIdx = -1
                    controller.onDown()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showKeyPreview(v, primaryChar)
                    val task = Runnable {
                        if (gen != touchSessionGeneration || serviceDestroyed || !v.isAttachedToWindow) return@Runnable
                        if (controller.onLongPressTimeout()) {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            hideKeyPreview()
                            v.isPressed = false
                            selectedVariantIdx = if (preselectFirstOnTimeout && variants.isNotEmpty()) 0 else -1
                            variantPopupInteractiveTapMode = false
                            popupChips = showArabicVariantPopup(v, variants, preselectFirstOnTimeout)
                        }
                    }
                    activeTask = task
                    main.postDelayed(task, thresholdMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (controller.isLongPressTriggered() && popupChips.isNotEmpty()) {
                        val rawX = event.rawX.toInt()
                        val rawY = event.rawY.toInt()
                        val chipLoc = IntArray(2)
                        var hitIdx = -1
                        for ((i, pair) in popupChips.withIndex()) {
                            val chip = pair.first
                            chip.getLocationOnScreen(chipLoc)
                            val padY = dp(10)
                            if (rawX >= chipLoc[0] && rawX <= chipLoc[0] + chip.width &&
                                rawY >= chipLoc[1] - padY && rawY <= chipLoc[1] + chip.height + padY
                            ) {
                                hitIdx = i
                                break
                            }
                        }
                        val nextIdx = when {
                            hitIdx >= 0 -> hitIdx
                            preselectFirstOnTimeout -> 0
                            else -> -1
                        }
                        if (nextIdx != selectedVariantIdx) {
                            selectedVariantIdx = nextIdx
                            updateVariantChipHighlight(popupChips, selectedVariantIdx)
                        }
                        true
                    } else {
                        val x = event.x
                        val y = event.y
                        val inside = x >= -dp(12) && x <= v.width + dp(12) && y >= -dp(24) && y <= v.height + dp(12)
                        if (!inside && controller.onMove(insideBounds = false)) {
                            cancelPendingLongPress()
                            v.isPressed = false
                            hideKeyPreview()
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingLongPress()
                    v.isPressed = false
                    hideKeyPreview()
                    if (controller.onUp()) {
                        commitArabicToken(primaryChar)
                    } else if (popupChips.isNotEmpty()) {
                        val chosen = popupChips.getOrNull(selectedVariantIdx)?.second
                        if (chosen != null) {
                            commitArabicToken(chosen)
                            dismissVariantPopup()
                        } else {
                            // Keep multi-item popup open so user can tap any diacritic/variant comfortably
                            variantPopupInteractiveTapMode = true
                        }
                        popupChips = emptyList()
                        selectedVariantIdx = -1
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    cancelPendingLongPress()
                    controller.onCancel()
                    v.isPressed = false
                    hideKeyPreview()
                    dismissVariantPopup()
                    popupChips = emptyList()
                    selectedVariantIdx = -1
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
        contentDescription = AppUiStrings.switchInputMethodA11y(keyboardLanguage)

        val iv = createIconView(IconDrawable(IconType.IME_SWITCH, colorModText, 16f))
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        attachKeyTouchFeedback(this, { showSystemImePicker() })
    }

    private fun dismissLanguagePopup() {
        val surface = imeSurfaceFrame
        val popup = languagePopupHost
        languagePopupHost = null
        if (surface != null && popup != null) {
            surface.removeView(popup)
        }
    }

    private fun showLanguageSwitcherPopup(anchorView: View) {
        val surface = imeSurfaceFrame ?: return
        dismissLanguagePopup()
        dismissVariantPopup()
        dismissPasteUndoCapsule()

        val activeLang = keyboardLanguage
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = uiLayoutDirection
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedDrawable(
                if (isClassicTheme) colorKeyBg else colorChipBg,
                dp(14f),
                colorKeyBorder
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(12f)
            }
            isClickable = true
        }

        val headerTv = TextView(this).apply {
            text = AppUiStrings.keyboardLanguageSettingTitle(keyboardLanguage)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorHintText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = View.TEXT_DIRECTION_LOCALE
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        card.addView(headerTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        KeyboardLayoutSpec.LANGUAGE_POPUP_OPTIONS.forEachIndexed { idx, opt ->
            val selected = activeLang == opt.code
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = uiLayoutDirection
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                contentDescription = opt.accessibilityLabel
                isClickable = true
                isFocusable = true
                background = roundedRipple(
                    normalColor = if (selected) colorActiveTabBg else colorKeyBg,
                    cornerRadiusPx = dp(10f),
                    strokeColor = if (selected) colorAccent else colorKeyBorder
                )
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    dismissLanguagePopup()
                    setKeyboardLanguage(opt.code)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleTv = TextView(this).apply {
                text = opt.primaryLabel
                textSize = 14f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (selected) colorActiveTabText else colorKeyText)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                textDirection = View.TEXT_DIRECTION_ANY_RTL
            }
            val subTv = TextView(this).apply {
                text = opt.secondaryLabel
                textSize = 11f
                setTextColor(if (selected) colorActiveTabText else colorHintText)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                textDirection = View.TEXT_DIRECTION_ANY_RTL
            }
            textCol.addView(titleTv)
            textCol.addView(subTv)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (selected) {
                val checkIv = createIconView(IconDrawable(IconType.DONE, colorActiveTabText, 14f))
                row.addView(checkIv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10)
                })
            }

            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (idx < KeyboardLayoutSpec.LANGUAGE_POPUP_OPTIONS.lastIndex) {
                    bottomMargin = dp(6)
                }
            })
        }

        val targetW = minOf(dp(236), (surface.width - dp(16)).coerceAtLeast(dp(180)))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(surface.height.coerceAtLeast(dp(140)), View.MeasureSpec.AT_MOST)
        )
        val popupH = card.measuredHeight

        val anchorLoc = IntArray(2)
        val surfaceLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        surface.getLocationOnScreen(surfaceLoc)
        val relLeft = anchorLoc[0] - surfaceLoc[0]
        val relTop = anchorLoc[1] - surfaceLoc[1]

        val minLeft = dp(8)
        val maxLeft = (surface.width - targetW - dp(8)).coerceAtLeast(minLeft)
        val clampedLeft = (relLeft + (anchorView.width - targetW) / 2).coerceIn(minLeft, maxLeft)
        val clampedTop = (relTop - popupH - dp(8)).coerceAtLeast(dp(6))

        val lp = FrameLayout.LayoutParams(targetW, popupH).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = clampedLeft
            topMargin = clampedTop
        }
        languagePopupHost = card
        surface.addView(card, lp)
        card.bringToFront()
    }

    private fun createSpaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, keyCornerRadiusPx(), colorKeyBorder)
        val uiSpec = KeyboardLayoutSpec.languageUiSpec(keyboardLanguage)
        contentDescription = AppUiStrings.spaceKeyFullA11y(keyboardLanguage, uiSpec.spacebarLabel)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = uiSpec.spacebarLabel
            textSize = 13f
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val controller = KeyboardLayoutSpec.LongPressKeyController()
        val thresholdMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(320L, 420L)
        var touchSessionGeneration = 0
        var activeTask: Runnable? = null

        fun cancelPendingLongPress() {
            touchSessionGeneration++
            activeTask?.let(main::removeCallbacks)
            activeTask = null
        }

        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                cancelPendingLongPress()
                controller.onCancel()
                v.isPressed = false
            }
        })

        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingLongPress()
                    val gen = touchSessionGeneration
                    controller.onDown()
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val task = Runnable {
                        if (gen != touchSessionGeneration || serviceDestroyed || !v.isAttachedToWindow) return@Runnable
                        if (controller.onLongPressTimeout()) {
                            v.isPressed = false
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showLanguageSwitcherPopup(v)
                        }
                    }
                    activeTask = task
                    main.postDelayed(task, thresholdMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = event.x
                    val y = event.y
                    val inside = x >= -dp(12) && x <= v.width + dp(12) && y >= -dp(24) && y <= v.height + dp(12)
                    if (!inside && controller.onMove(insideBounds = false)) {
                        cancelPendingLongPress()
                        v.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelPendingLongPress()
                    v.isPressed = false
                    if (controller.onUp()) {
                        handleSpacePress()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    cancelPendingLongPress()
                    controller.onCancel()
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
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

    private fun contentDescriptionForAction(kind: InputFieldPolicy.ActionKeyKind): String =
        AppUiStrings.actionKeyA11y(keyboardLanguage, kind)

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
        if (isEmojiPickerOpen && isMediaSearchTyping) {
            mediaSearchQuery += letter
            updateMediaSearchUI()
            renderMediaSearchStripResults()
        } else if (isFilterMode) {
            keyboardSearchQuery += letter
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection
            if (conn != null) {
                insertionHistory.invalidateImmediateBackspaceRevert()
                lastRevertOriginalWord = null
                if (commitTextSafely(conn, letter, 1)) {
                    shadowEditor.onCommitText(letter)
                }
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
        if (isEmojiPickerOpen && isMediaSearchTyping) {
            mediaSearchQuery += char
            updateMediaSearchUI()
            renderMediaSearchStripResults()
        } else if (isFilterMode) {
            keyboardSearchQuery += char
            updateSearchUI()
            runKeyboardSearch()
        } else {
            val conn = currentInputConnection ?: return
            if (isWordBoundaryDelimiter(char)) {
                commitWordBoundaryWithIntelligence(conn, char)
            } else {
                insertionHistory.invalidateImmediateBackspaceRevert()
                lastRevertOriginalWord = null
                if (commitTextSafely(conn, char, 1)) {
                    shadowEditor.onCommitText(char)
                }
                updateInsertionHistoryButtons()
                if (char == "'") {
                    refreshTypingSuggestionsAsync()
                } else {
                    clearTypingSuggestionsView()
                }
                syncAutoCapitalization(
                    preserveManualShift = false,
                    predictedBeforeText = shadowEditor.beforeText(48)
                )
            }
        }
    }

    private fun handleSpacePress() {
        dismissPasteUndoCapsule()
        shiftStateMachine.onNonLetterInput()
        if (isEmojiPickerOpen && isMediaSearchTyping) {
            mediaSearchQuery += " "
            updateMediaSearchUI()
            renderMediaSearchStripResults()
        } else if (isFilterMode) {
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
        if (isEmojiPickerOpen && isMediaSearchTyping) {
            if (mediaSearchQuery.isNotEmpty()) {
                mediaSearchQuery = EmojiInterfaceSpec.dropLastGrapheme(mediaSearchQuery)
                updateMediaSearchUI()
                renderMediaSearchStripResults()
            }
        } else if (isFilterMode) {
            if (keyboardSearchQuery.isNotEmpty()) {
                keyboardSearchQuery = EmojiInterfaceSpec.dropLastGrapheme(keyboardSearchQuery)
                updateSearchUI()
                runKeyboardSearch()
            }
        } else {
            val conn = currentInputConnection ?: return
            val gateway = buildShadowGateway(conn)
            if (gateway != null && !isPasswordTarget) {
                val reverted = insertionHistory.tryImmediateBackspaceRevertAutocorrect(gateway)
                if (reverted != null) {
                    if (fieldCapabilities.allowSessionLearning) {
                        suppressedAutocorrectWords.add(reverted.lowercase(Locale.ROOT))
                    }
                    lastRevertOriginalWord = null
                    updateInsertionHistoryButtons()
                    refreshTypingSuggestionsAsync()
                    syncAutoCapitalization(
                        preserveManualShift = false,
                        predictedBeforeText = shadowEditor.beforeText(48)
                    )
                    return
                }
            }

            insertionHistory.invalidateImmediateBackspaceRevert()
            lastRevertOriginalWord = null
            if (shadowEditor.hasSelection) {
                if (commitTextSafely(conn, "", 1)) {
                    shadowEditor.onDeleteSelection()
                }
            } else {
                val preDelete = shadowEditor.beforeText(64)
                val deleteCount = if (!preDelete.isNullOrEmpty()) {
                    KeyboardLayoutSpec.backspaceUtf16Count(preDelete, keyboardLanguage)
                } else {
                    1
                }
                if (deleteSurroundingTextSafely(conn, deleteCount, 0)) {
                    shadowEditor.onDeleteBeforeCursor(deleteCount)
                }
            }
            updateInsertionHistoryButtons()
            refreshTypingSuggestionsAsync()
            syncAutoCapitalization(
                preserveManualShift = false,
                predictedBeforeText = shadowEditor.beforeText(48)
            )
        }
    }

    private fun handleEnterPress() {
        dismissPasteUndoCapsule()
        val resolution = resolveCurrentActionKey()
        shiftStateMachine.onNonLetterInput()
        if (isEmojiPickerOpen && isMediaSearchTyping) {
            isMediaSearchTyping = false
            renderFullImeView()
        } else if (resolution.isFilterDismiss || isFilterMode) {
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
                performEditorActionSafely(conn, actionId)
                shadowEditor.invalidateHostSync()
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
        searchInputText?.text = if (keyboardSearchQuery.isEmpty()) AppUiStrings.searchPlaceholder(keyboardLanguage) else keyboardSearchQuery
        searchInputText?.setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
        searchClearBtn?.visibility = if (keyboardSearchQuery.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setupBackspaceRepeat(view: View) {
        var repeatGen = 0
        var activeRepeat: Runnable? = null

        fun stopRepeat() {
            repeatGen++
            activeRepeat?.let(main::removeCallbacks)
            activeRepeat = null
        }

        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                stopRepeat()
                v.isPressed = false
            }
        })

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    stopRepeat()
                    val gen = repeatGen
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    handleBackspace()
                    val runnable = object : Runnable {
                        override fun run() {
                            if (gen != repeatGen || serviceDestroyed || !v.isAttachedToWindow || !v.isPressed) return
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            handleBackspace()
                            main.postDelayed(this, 50)
                        }
                    }
                    activeRepeat = runnable
                    main.postDelayed(runnable, 350)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    stopRepeat()
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    // =========================================================================
    // PERSISTENCE & CHUNKED INSERTION
    // =========================================================================
    private fun reloadItemsIfNeeded() {
        if (lastLoadedStoreVersion >= 0L && lastLoadedStoreVersion == ClipStore.currentChangeVersion()) {
            return
        }
        reloadItems()
    }

    fun reloadItems() {
        val request = ++listRequest
        submitSafely(io) {
            CapturePreferences.cleanupExpired(store, capturePreferences)
            val storeVersionAtRead = ClipStore.currentChangeVersion()
            val found = runCatching { store.listItems() }.getOrElse { emptyList() }
            val prebuiltSnippets = HashMap<String, String>(minOf(found.size, 64))
            for (item in found.take(64)) {
                val snip = getRawSnippetPrefix(item, 180)
                if (snip.isNotEmpty()) {
                    prebuiltSnippets[item.id] = snip
                }
            }
            main.post {
                if (serviceDestroyed || request != listRequest || imeRootFrame == null) return@post
                lastLoadedStoreVersion = storeVersionAtRead
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
        submitSafely(io) {
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
        beginInsertion(item.title, AppUiStrings.insertingClipStatus(keyboardLanguage, item.title)) {
            InsertionSource(store.openTextReader(item.id), item.characterCount)
        }
    }

    private fun beginInsertion(title: String, initialStatus: String, openSource: () -> InsertionSource) {
        if (isPasswordTarget) {
            setStatus(AppUiStrings.passwordClipsUnavailable(keyboardLanguage))
            return
        }
        val connection = currentInputConnection
        if (connection == null) {
            setStatus(AppUiStrings.noActiveTextFieldStatus(keyboardLanguage))
            return
        }
        if (isInserting) cancelInsertion(userInitiated = false)
        generation++
        val token = generation
        val startTarget = currentEditorTarget

        dismissPasteUndoCapsule()
        isInserting = true
        insertedCodePoints = 0
        lastReportedCodePoints = 0
        activeItemCodePoints = 0
        setStatus(initialStatus)

        insertTask = submitFutureSafely(inserter) {
            try {
                val hasSelectionBefore = readHasSelectionFromIcOffMainThread(connection)
                val beforeAnchor = if (!hasSelectionBefore) {
                    readTextBeforeFromIcOffMainThread(connection, InsertionHistoryManager.ANCHOR_LENGTH).orEmpty()
                } else ""
                val afterAnchor = if (!hasSelectionBefore) {
                    readTextAfterFromIcOffMainThread(connection, InsertionHistoryManager.ANCHOR_LENGTH).orEmpty()
                } else ""
                val source = openSource()
                activeItemCodePoints = source.characterCount
                val trackedBuffer = if (!hasSelectionBefore && source.characterCount <= InsertionHistoryManager.MAX_TRACKED_INSERTION_CHARS) {
                    StringBuilder(minOf(source.characterCount + 16, InsertionHistoryManager.MAX_TRACKED_INSERTION_CHARS + 16))
                } else {
                    null
                }
                main.post {
                    if (generation == token) setStatus(AppUiStrings.insertingClipWithCountStatus(keyboardLanguage, title, activeItemCodePoints.grouped()))
                }
                val complete = source.reader.use { reader ->
                    TextChunker.forEachChunk(
                        reader = reader,
                        chunkChars = 16_384,
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
                                target = currentEditorTarget,
                                insertedText = insertedText,
                                beforeAnchor = beforeAnchor,
                                afterAnchor = afterAnchor
                            )
                            if (recorded) {
                                val isLargeOperation = insertedText.length >= PasteUndoCapsuleSpec.LARGE_PASTE_CHAR_THRESHOLD
                                pasteCapsuleController.onPasteSucceeded(
                                    nowMs = SystemClock.elapsedRealtime(),
                                    isLargeOperation = isLargeOperation
                                )
                                if (toolbarState != 0) {
                                    switchToolbarState(0, dismissCapsule = false)
                                }
                                clearTypingSuggestionsView()
                                updatePasteUndoCapsuleUI()
                                // Compose / WebView hosts often apply commitText asynchronously on the next frame
                                // or trigger restartInput during large-text reflow; re-sync capsule UI once host settles.
                                for (delayMs in longArrayOf(80L, 250L, 650L)) {
                                    main.postDelayed({
                                        if (!serviceDestroyed && inputViewActive && currentEditorTarget.isCompatibleWith(startTarget)) {
                                            updatePasteUndoCapsuleUI()
                                        }
                                    }, delayMs)
                                }
                            } else {
                                pasteCapsuleController.onPasteFailed()
                                updatePasteUndoCapsuleUI()
                            }
                        } else {
                            pasteCapsuleController.onPasteFailed()
                            updatePasteUndoCapsuleUI()
                        }
                        if (complete) {
                            if (insertedText != null && insertedText.length <= ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_BEFORE_CHARS && !hasSelectionBefore) {
                                shadowEditor.onCommitText(insertedText)
                            } else {
                                shadowEditor.invalidateHostSync()
                            }
                            shiftStateMachine.onNonLetterInput()
                            syncAutoCapitalization(
                                preserveManualShift = false,
                                predictedBeforeText = shadowEditor.beforeText(48)
                            )
                        } else {
                            shadowEditor.invalidateHostSync()
                        }
                        setStatus(AppUiStrings.insertionCompletedOrPausedStatus(keyboardLanguage, complete, insertedCodePoints.grouped()))
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
                        setStatus(AppUiStrings.insertionEndedStatus(keyboardLanguage, failure.message))
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
                if (!serviceDestroyed && generation == token && isInserting) {
                    accepted = runCatching { connection.commitText(chunk, 1) }.getOrDefault(false)
                    if (!accepted) {
                        failure = IOException("Destination app rejected chunk")
                    } else {
                        insertedCodePoints += points
                        if (insertedCodePoints - lastReportedCodePoints >= 16_384 || insertedCodePoints >= activeItemCodePoints) {
                            lastReportedCodePoints = insertedCodePoints
                            setStatus(AppUiStrings.insertedProgressStatus(keyboardLanguage, insertedCodePoints.grouped(), activeItemCodePoints.grouped()))
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
        setStatus(AppUiStrings.insertionCancelledStatus(keyboardLanguage, userInitiated))
    }

    private fun setStatus(msg: String) {
        statusLabel?.text = msg
        val targetVis = if (isInserting || isPasswordTarget) View.VISIBLE else View.GONE
        val banner = statusBannerView
        val visChanged = banner != null && banner.visibility != targetVis
        banner?.visibility = targetVis
        statusCancelBtn?.visibility = if (isInserting) View.VISIBLE else View.GONE
        if (visChanged && !isSettingsOpen && !isClipboardPanelOpen && keyboardRowsContainer != null) {
            renderKeyboardRowsIfNeeded()
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
        val prevCapabilities = fieldCapabilities
        val wasPasswordTarget = isPasswordTarget
        fieldCapabilities = InputFieldPolicy.evaluate(attribute.inputType, attribute.imeOptions)
        isPasswordTarget = fieldCapabilities.isPasswordOrPin || isSensitiveField(attribute.inputType)

        val pkgChanged = attribute.packageName != lastEditorPackage
        val prevInputClass = lastEditorInputType and InputFieldPolicy.TYPE_MASK_CLASS
        val nextInputClass = attribute.inputType and InputFieldPolicy.TYPE_MASK_CLASS
        val effectivePrevFieldId = if (lastEditorFieldId != 0) lastEditorFieldId else lastNonZeroEditorFieldId
        val effectivePrevInputClass = if (prevInputClass != 0) prevInputClass else lastNonZeroEditorInputClass
        val sameHostNonPassword = !pkgChanged && lastEditorPackage != null && !isPasswordTarget
        val fieldChanged = when {
            attribute.fieldId == effectivePrevFieldId -> false
            sameHostNonPassword && (attribute.fieldId == 0 || effectivePrevFieldId == 0) -> false
            else -> (attribute.fieldId != 0 && effectivePrevFieldId != 0 && attribute.fieldId != effectivePrevFieldId)
        }
        val inputClassChanged = lastEditorPackage != null &&
            nextInputClass != effectivePrevInputClass &&
            !(sameHostNonPassword && (nextInputClass == 0 || effectivePrevInputClass == 0))
        val hardFieldChanged = pkgChanged || fieldChanged || inputClassChanged || isPasswordTarget

        val initialBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { attribute.getInitialTextBeforeCursor(ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_BEFORE_CHARS, 0)?.toString() }.getOrNull()
        } else null
        val initialAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { attribute.getInitialTextAfterCursor(ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_AFTER_CHARS, 0)?.toString() }.getOrNull()
        } else null
        val initialSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { attribute.getInitialSelectedText(0)?.toString() }.getOrNull()
        } else null
        val initialHasSelection = !initialSelected.isNullOrEmpty() ||
            (attribute.initialSelStart >= 0 && attribute.initialSelEnd >= 0 && attribute.initialSelStart != attribute.initialSelEnd)

        val nowMs = SystemClock.elapsedRealtime()
        val snapshotGatewayForPreserve = if (!hardFieldChanged && pasteCapsuleController.isActive) {
            val conn = currentInputConnection
            if (conn != null && initialBefore != null) {
                ImeInputPipeline.SnapshotHostEditorGateway(
                    target = currentEditorTarget,
                    passwordField = isPasswordTarget,
                    preReadBefore = initialBefore,
                    preReadAfter = initialAfter,
                    preReadSelected = initialSelected,
                    sink = buildIcMutationSink(conn)
                )
            } else if (conn != null && shadowEditor.synchronizedWithHost) {
                buildShadowGateway(conn)
            } else {
                null
            }
        } else {
            null
        }
        val canPreserveViaGateway = !hardFieldChanged && pasteCapsuleController.isActive && snapshotGatewayForPreserve != null && (
            if (pasteCapsuleController.isRedoMode) insertionHistory.canSafelyRedo(snapshotGatewayForPreserve).allowed
            else insertionHistory.canSafelyUndo(snapshotGatewayForPreserve).allowed
        )
        val preserveSession = PasteUndoCapsuleSpec.shouldPreserveSessionOnStartInput(
            restarting = restarting,
            hardFieldChanged = hardFieldChanged,
            isInserting = isInserting,
            isCapsuleActive = pasteCapsuleController.isActive,
            lastActionElapsedMs = pasteCapsuleController.lastActionElapsedMs,
            nowMs = nowMs,
            canPreserveViaGateway = canPreserveViaGateway
        )
        shadowEditor.resetForInputSession(
            initialBefore = initialBefore,
            initialAfter = initialAfter,
            initialHasSelection = initialHasSelection,
            selStart = attribute.initialSelStart,
            selEnd = attribute.initialSelEnd
        )

        suggestionGeneration.incrementAndGet()
        if (!preserveSession || !isInserting) {
            generation++
            insertTask?.cancel(true)
            insertTask = null
            isInserting = false
        }

        if (!restarting && !preserveSession) {
            isEmojiPickerOpen = false
            isMediaSearchTyping = false
            isStickerPackManagerOpen = false
            mediaSearchQuery = ""
        }
        if (isPasswordTarget) {
            isFilterMode = false
            isClipboardPanelOpen = false
            isEmojiPickerOpen = false
            isMediaSearchTyping = false
            isStickerPackManagerOpen = false
            mediaSearchQuery = ""
            pinnedOnly = false
        }
        val numericRequirementChanged =
            InputFieldPolicy.shouldAutoActivateNumpad(prevCapabilities, isFilterMode) !=
                InputFieldPolicy.shouldAutoActivateNumpad(fieldCapabilities, isFilterMode) ||
            prevCapabilities.numericFieldKind != fieldCapabilities.numericFieldKind
        val treatAsNewField = !restarting || hardFieldChanged || numericRequirementChanged || (wasPasswordTarget != isPasswordTarget)
        val prevMode = modeController.currentMode
        modeController.onFieldFocused(
            capabilities = fieldCapabilities,
            restarting = !treatAsNewField,
            isFilterMode = isFilterMode
        )
        autoCapMode = AutoCapitalizationPolicy.resolveCapMode(attribute.inputType, isFilterMode)
        shiftStateMachine.onInputSessionReset()

        if (!preserveSession && (!restarting || hardFieldChanged)) {
            editorSessionId++
            dismissPasteUndoCapsule()
        } else if (preserveSession && !isInserting && pasteCapsuleController.isActive && !canPreserveViaGateway) {
            // Host triggered restartInput during large-text layout reflow; verify off main thread once settled.
            val verifyRestartToken = ++pasteRestartVerifyToken
            val expectedSessionId = editorSessionId
            schedulePreserveSessionVerification(verifyRestartToken, expectedSessionId, 700L, allowRetry = true)
        }
        lastEditorPackage = attribute.packageName
        lastEditorFieldId = attribute.fieldId
        if (pkgChanged || hardFieldChanged) {
            lastNonZeroEditorFieldId = attribute.fieldId
            lastNonZeroEditorInputClass = nextInputClass
        } else {
            if (attribute.fieldId != 0) lastNonZeroEditorFieldId = attribute.fieldId
            if (nextInputClass != 0) lastNonZeroEditorInputClass = nextInputClass
        }
        lastEditorInputType = attribute.inputType
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
            setStatus(AppUiStrings.passwordClipsWithheldDot(keyboardLanguage))
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
        window?.window?.let { applySystemNavBarTheme(it) }
        if (imeRootFrame != null && computeThemeStateKey() != lastRenderedThemeKey) {
            renderFullImeView()
        } else if (keyboardRowsContainer != null && !isClipboardPanelOpen && !isSettingsOpen && !isEmojiPickerOpen &&
            (modeController.currentMode != prevMode || isPasswordTarget != wasPasswordTarget)
        ) {
            renderKeyboardRowsIfNeeded()
        }
        if (pasteCapsuleController.isActive && isPasswordTarget) {
            dismissPasteUndoCapsule()
        }
        updateToolbarCenterVisibility()
        updateInsertionHistoryButtons()
        syncAutoCapitalization(
            preserveManualShift = false,
            predictedBeforeText = shadowEditor.beforeText(48)
        )
        if (!shadowEditor.synchronizedWithHost) {
            scheduleEditorContextSync(
                conn = currentInputConnection,
                triggerSuggestions = false,
                preserveManualShift = false
            )
        }
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
        val prevCapabilities = fieldCapabilities
        fieldCapabilities = InputFieldPolicy.evaluate(info.inputType, info.imeOptions)
        isPasswordTarget = fieldCapabilities.isPasswordOrPin || isSensitiveField(info.inputType)
        if (isPasswordTarget && isFilterMode) {
            isFilterMode = false
        }
        val numChanged =
            InputFieldPolicy.shouldAutoActivateNumpad(prevCapabilities, isFilterMode) !=
                InputFieldPolicy.shouldAutoActivateNumpad(fieldCapabilities, isFilterMode) ||
            prevCapabilities.numericFieldKind != fieldCapabilities.numericFieldKind
        if (!restarting || numChanged) {
            modeController.onFieldFocused(fieldCapabilities, restarting = false, isFilterMode = isFilterMode)
        }
        updateToolbarCenterVisibility()
        updateInsertionHistoryButtons()
        renderKeyboardRowsIfNeeded()
        syncAutoCapitalization(
            preserveManualShift = manualShiftOverride,
            predictedBeforeText = shadowEditor.beforeText(48)
        )
        if (!shadowEditor.synchronizedWithHost && shadowEditor.awaitingHostSync) {
            scheduleEditorContextSync(
                conn = currentInputConnection,
                triggerSuggestions = false,
                preserveManualShift = manualShiftOverride
            )
        }
        updateCaptureMonitoring()
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) {
            capturePrimaryClip(CaptureSource.CLIPBOARD_POLL)
        }
        reloadItemsIfNeeded()
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
        val classification = shadowEditor.onSelectionReported(oldSelStart, oldSelEnd, newSelStart, newSelEnd)

        if (pasteCapsuleController.isActive && !isInserting) {
            val validNewBounds = newSelStart >= 0 && newSelEnd >= 0
            val cursorMoved = validNewBounds && ((oldSelStart != newSelStart) || (oldSelEnd != newSelEnd))
            val shadowGateway = if (shadowEditor.synchronizedWithHost) buildShadowGateway() else null
            if (shadowGateway != null) {
                val hasSelection = (validNewBounds && newSelStart != newSelEnd) || shadowGateway.hasActiveSelection()
                val canUndoNow = insertionHistory.canSafelyUndo(shadowGateway).allowed
                val canRedoNow = insertionHistory.canSafelyRedo(shadowGateway).allowed
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
            } else if (cursorMoved || classification != ImeInputPipeline.SelectionClassification.EXPECTED_STEP) {
                val conn = currentInputConnection
                val targetSession = editorSessionId
                if (conn != null) {
                    submitSafely(icQueryExecutor) {
                        val snapshotGateway = buildSnapshotGatewayOffMainThread(conn, 2048, InsertionHistoryManager.ANCHOR_LENGTH)
                        main.post {
                            if (serviceDestroyed || !inputViewActive || targetSession != editorSessionId || !pasteCapsuleController.isActive || isInserting) {
                                return@post
                            }
                            val hasSelection = (validNewBounds && newSelStart != newSelEnd) || snapshotGateway.hasActiveSelection()
                            val canUndoNow = insertionHistory.canSafelyUndo(snapshotGateway).allowed
                            val canRedoNow = insertionHistory.canSafelyRedo(snapshotGateway).allowed
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
                    }
                }
            }
        }
        updateInsertionHistoryButtons()

        when (classification) {
            ImeInputPipeline.SelectionClassification.RANGE_SELECTED -> {
                clearTypingSuggestionsView()
                syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                scheduleEditorContextSync(
                    conn = currentInputConnection,
                    triggerSuggestions = false,
                    preserveManualShift = false
                )
            }
            ImeInputPipeline.SelectionClassification.CURSOR_JUMP -> {
                shiftStateMachine.onNonLetterInput()
                insertionHistory.invalidateImmediateBackspaceRevert()
                lastRevertOriginalWord = null
                shadowEditor.markHostSyncNeeded()
                syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                scheduleEditorContextSync(
                    conn = currentInputConnection,
                    triggerSuggestions = !isFilterMode && !isPasswordTarget && fieldCapabilities.allowSuggestions && isSuggestionsEnabled,
                    preserveManualShift = false
                )
            }
            ImeInputPipeline.SelectionClassification.EXPECTED_STEP -> {
                if (!shadowEditor.synchronizedWithHost && shadowEditor.awaitingHostSync) {
                    scheduleEditorContextSync(
                        conn = currentInputConnection,
                        triggerSuggestions = !isFilterMode && !isPasswordTarget && fieldCapabilities.allowSuggestions && isSuggestionsEnabled,
                        preserveManualShift = true
                    )
                }
                syncAutoCapitalization(
                    preserveManualShift = true,
                    predictedBeforeText = shadowEditor.beforeText(48)
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (languagePopupHost != null || variantPopupHost != null ||
                contextMenuHost != null || isMergeMode || undoRedoHost != null ||
                isMediaSearchTyping || isStickerPackManagerOpen ||
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
            if (languagePopupHost != null) {
                if (!event.isCanceled) {
                    dismissLanguagePopup()
                }
                return true
            }
            if (variantPopupHost != null) {
                if (!event.isCanceled) {
                    dismissVariantPopup()
                }
                return true
            }
            if (contextMenuHost != null) {
                if (!event.isCanceled) {
                    dismissContextMenu(animate = true)
                }
                return true
            }
            if (isMergeMode) {
                if (!event.isCanceled) {
                    exitMergeMode()
                    setStatus(AppUiStrings.mergeCancelledStatus(keyboardLanguage))
                }
                return true
            }
            if (undoRedoHost != null) {
                if (!event.isCanceled) {
                    dismissUndoRedoControl()
                }
                return true
            }
            if (isEmojiPickerOpen && isMediaSearchTyping) {
                if (!event.isCanceled) {
                    isMediaSearchTyping = false
                    renderFullImeView()
                }
                return true
            }
            if (isEmojiPickerOpen && isStickerPackManagerOpen) {
                if (!event.isCanceled) {
                    isStickerPackManagerOpen = false
                    renderFullImeView()
                }
                return true
            }
            if (isEmojiPickerOpen) {
                if (!event.isCanceled) {
                    isEmojiPickerOpen = false
                    isStickerPackManagerOpen = false
                    mediaSearchQuery = ""
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
        hideKeyPreview()
        dismissLanguagePopup()
        dismissVariantPopup()
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode(refresh = false)
        shiftStateMachine.resetDoubleTapTimer()
        val needsReset = isEmojiPickerOpen || isMediaSearchTyping || isStickerPackManagerOpen || isSettingsOpen || isClipboardPanelOpen || isFilterMode || pinnedOnly || toolbarState != 0
        isEmojiPickerOpen = false
        isMediaSearchTyping = false
        isStickerPackManagerOpen = false
        mediaSearchQuery = ""
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
        hideKeyPreview()
        dismissLanguagePopup()
        dismissVariantPopup()
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode(refresh = false)
        super.onConfigurationChanged(newConfig)
        refreshCachedThemePalette()
        window?.window?.let { applySystemNavBarTheme(it) }
        if (imeRootFrame != null) {
            renderFullImeView()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        hideKeyPreview()
        dismissLanguagePopup()
        dismissVariantPopup()
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode(refresh = false)
        inputViewActive = false
        updateCaptureMonitoring()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        dismissLanguagePopup()
        dismissVariantPopup()
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode(refresh = false)
        generation++
        inputSessionActive = false
        inputViewActive = false
        isPasswordTarget = false
        fieldCapabilities = InputFieldPolicy.evaluate(InputType.TYPE_CLASS_TEXT, 0)
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
        dismissLanguagePopup()
        dismissVariantPopup()
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
        mediaStatusBannerRunnable?.let(main::removeCallbacks)
        mediaStatusBannerRunnable = null
        io.shutdownNow()
        inserter.shutdownNow()
        suggestExecutor.shutdownNow()
        icQueryExecutor.shutdownNow()
        mediaIoExecutor.shutdownNow()
        super.onDestroy()
    }

    // =========================================================================
    // HOST INSERTION UNDO/REDO & TYPING INTELLIGENCE HELPERS (NON-BLOCKING)
    // =========================================================================
    private fun commitTextSafely(conn: InputConnection, text: String, newCursorPosition: Int = 1): Boolean =
        runCatching { conn.commitText(text, newCursorPosition) }.getOrDefault(false)

    private fun deleteSurroundingTextSafely(conn: InputConnection, beforeLength: Int, afterLength: Int): Boolean =
        runCatching { conn.deleteSurroundingText(beforeLength, afterLength) }.getOrDefault(false)

    private fun performEditorActionSafely(conn: InputConnection, actionId: Int): Boolean =
        runCatching { conn.performEditorAction(actionId) }.getOrDefault(false)

    private fun buildIcMutationSink(ic: InputConnection): ImeInputPipeline.MutationSink =
        object : ImeInputPipeline.MutationSink {
            override fun deleteBeforeCursor(length: Int): Boolean {
                if (length < 0) return false
                if (length == 0) return true
                var remaining = length
                var maxStep = 4096
                while (remaining > 0) {
                    val step = minOf(remaining, maxStep)
                    val deleted = runCatching { ic.deleteSurroundingText(step, 0) }.getOrDefault(false)
                    if (!deleted) {
                        maxStep = when {
                            maxStep > 1024 -> 1024
                            maxStep > 256 -> 256
                            maxStep > 64 -> 64
                            else -> return false
                        }
                        continue
                    }
                    shadowEditor.onDeleteBeforeCursor(step)
                    remaining -= step
                }
                return true
            }

            override fun commitText(text: String): Boolean {
                if (text.isEmpty()) return true
                if (text.length <= 16_384) {
                    val ok = runCatching { ic.commitText(text, 1) }.getOrDefault(false)
                    if (ok) shadowEditor.onCommitText(text)
                    return ok
                }
                var offset = 0
                while (offset < text.length) {
                    var end = minOf(offset + 16_384, text.length)
                    if (end < text.length && Character.isHighSurrogate(text[end - 1])) {
                        end--
                    }
                    val chunk = text.substring(offset, end)
                    val ok = runCatching { ic.commitText(chunk, 1) }.getOrDefault(false)
                    if (!ok) {
                        shadowEditor.invalidateHostSync()
                        return false
                    }
                    offset = end
                }
                shadowEditor.onCommitText(text)
                return true
            }

            override fun runBatchEdit(block: () -> Boolean): Boolean {
                return try {
                    runCatching { ic.beginBatchEdit() }
                    runCatching { ic.finishComposingText() }
                    block()
                } finally {
                    runCatching { ic.endBatchEdit() }
                }
            }
        }

    private fun buildShadowGateway(conn: InputConnection? = currentInputConnection): InsertionHistoryManager.HostEditorGateway? {
        val ic = conn ?: return null
        val before = shadowEditor.beforeText(ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_BEFORE_CHARS)
        val after = shadowEditor.afterText(ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_AFTER_CHARS)
        val selected = if (shadowEditor.hasSelection) "\u200B" else null
        return ImeInputPipeline.SnapshotHostEditorGateway(
            target = currentEditorTarget,
            passwordField = isPasswordTarget,
            preReadBefore = before,
            preReadAfter = after,
            preReadSelected = selected,
            sink = buildIcMutationSink(ic)
        )
    }

    // Must ONLY be invoked from background executors (icQueryExecutor / inserter) — NEVER on MainLooper.
    private fun readHasSelectionFromIcOffMainThread(ic: InputConnection): Boolean {
        val sel = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!sel.isNullOrEmpty()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val surrounding = runCatching { ic.getSurroundingText(0, 0, 0) }.getOrNull()
            if (surrounding != null && surrounding.selectionStart >= 0 && surrounding.selectionEnd >= 0) {
                return surrounding.selectionStart != surrounding.selectionEnd
            }
        }
        return false
    }

    // Must ONLY be invoked from background executors (icQueryExecutor / inserter) — NEVER on MainLooper.
    private fun readSelectedFromIcOffMainThread(ic: InputConnection): String? =
        runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()

    // Must ONLY be invoked from background executors (icQueryExecutor / inserter) — NEVER on MainLooper.
    private fun readTextBeforeFromIcOffMainThread(ic: InputConnection, maxChars: Int): String? {
        if (maxChars <= 0) return ""
        val direct = runCatching { ic.getTextBeforeCursor(maxChars, 0)?.toString() }.getOrNull()
        if (direct != null && (direct.length >= maxChars || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
            return direct
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fetchLen = minOf(maxChars, 2048)
            val surrounding = runCatching { ic.getSurroundingText(fetchLen, 0, 0) }.getOrNull()
            if (surrounding != null && surrounding.selectionStart >= 0 && surrounding.selectionEnd >= 0) {
                val selStart = surrounding.selectionStart.coerceIn(0, surrounding.text.length)
                val fromSurrounding = surrounding.text.subSequence(0, selStart).toString()
                if (direct == null || fromSurrounding.length > direct.length) {
                    return fromSurrounding
                }
            }
        }
        if (direct != null && (direct.isNotEmpty() || maxChars <= 2048)) return direct
        if (maxChars > 2048) {
            val fallbackDirect = runCatching { ic.getTextBeforeCursor(2048, 0)?.toString() }.getOrNull()
            if (!fallbackDirect.isNullOrEmpty()) return fallbackDirect
        }
        if (maxChars > 512) {
            for (limit in intArrayOf(1024, 512)) {
                val fallbackSmall = runCatching { ic.getTextBeforeCursor(limit, 0)?.toString() }.getOrNull()
                if (!fallbackSmall.isNullOrEmpty()) return fallbackSmall
            }
        }
        if (direct != null) return direct
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

    // Must ONLY be invoked from background executors (icQueryExecutor / inserter) — NEVER on MainLooper.
    private fun readTextAfterFromIcOffMainThread(ic: InputConnection, maxChars: Int): String? {
        if (maxChars <= 0) return ""
        val direct = runCatching { ic.getTextAfterCursor(maxChars, 0)?.toString() }.getOrNull()
        if (direct != null && (direct.length >= maxChars || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
            return direct
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fetchLen = minOf(maxChars, 2048)
            val surrounding = runCatching { ic.getSurroundingText(0, fetchLen, 0) }.getOrNull()
            if (surrounding != null && surrounding.selectionStart >= 0 && surrounding.selectionEnd >= 0) {
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

    // Must ONLY be invoked from icQueryExecutor — NEVER on MainLooper.
    private fun buildSnapshotGatewayOffMainThread(
        ic: InputConnection,
        maxBeforeChars: Int,
        maxAfterChars: Int,
        targetSnapshot: InsertionHistoryManager.EditorTarget = currentEditorTarget,
        passwordSnapshot: Boolean = isPasswordTarget
    ): ImeInputPipeline.SnapshotHostEditorGateway {
        val selected = readSelectedFromIcOffMainThread(ic)
        val hasSel = !selected.isNullOrEmpty() || readHasSelectionFromIcOffMainThread(ic)
        val before = readTextBeforeFromIcOffMainThread(ic, maxBeforeChars)
        val after = readTextAfterFromIcOffMainThread(ic, maxAfterChars)
        return ImeInputPipeline.SnapshotHostEditorGateway(
            target = targetSnapshot,
            passwordField = passwordSnapshot,
            preReadBefore = before,
            preReadAfter = after,
            preReadSelected = if (hasSel) (selected ?: "\u200B") else null,
            sink = buildIcMutationSink(ic)
        )
    }

    private fun scheduleEditorContextSync(
        conn: InputConnection? = currentInputConnection,
        triggerSuggestions: Boolean = true,
        preserveManualShift: Boolean = true
    ) {
        val ic = conn ?: return
        if (serviceDestroyed) return
        val expectedRevision = shadowEditor.revision
        val expectedSession = editorSessionId
        submitSafely(icQueryExecutor) {
            val before = readTextBeforeFromIcOffMainThread(
                ic,
                ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_BEFORE_CHARS
            )
            val after = readTextAfterFromIcOffMainThread(
                ic,
                ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_AFTER_CHARS
            )
            val hasSelection = readHasSelectionFromIcOffMainThread(ic)
            main.post {
                if (serviceDestroyed || expectedSession != editorSessionId) return@post
                val applied = shadowEditor.applyHostSnapshot(
                    expectedRevision = expectedRevision,
                    before = before,
                    after = after,
                    hasSelection = hasSelection
                )
                if (applied) {
                    syncAutoCapitalization(
                        preserveManualShift = preserveManualShift,
                        predictedBeforeText = shadowEditor.beforeText(48)
                    )
                    if (triggerSuggestions && inputViewActive && !isFilterMode && !isPasswordTarget) {
                        refreshTypingSuggestionsAsync()
                    }
                }
            }
        }
    }

    private fun schedulePreserveSessionVerification(
        verifyRestartToken: Int,
        expectedSessionId: Long,
        delayMs: Long,
        allowRetry: Boolean
    ) {
        main.postDelayed({
            if (serviceDestroyed || pasteRestartVerifyToken != verifyRestartToken || editorSessionId != expectedSessionId) {
                return@postDelayed
            }
            if (isInserting || !pasteCapsuleController.isActive || pasteCapsuleController.isPendingConfirmation) {
                return@postDelayed
            }
            val conn = currentInputConnection ?: return@postDelayed
            val targetSnap = currentEditorTarget
            val pwSnap = isPasswordTarget
            submitSafely(icQueryExecutor) {
                val g = buildSnapshotGatewayOffMainThread(conn, 2048, InsertionHistoryManager.ANCHOR_LENGTH, targetSnap, pwSnap)
                val before32 = g.textBeforeCursor(32)
                main.post {
                    if (serviceDestroyed || pasteRestartVerifyToken != verifyRestartToken || editorSessionId != expectedSessionId) {
                        return@post
                    }
                    if (isInserting || !pasteCapsuleController.isActive || pasteCapsuleController.isPendingConfirmation) {
                        return@post
                    }
                    val valid = if (pasteCapsuleController.isRedoMode) {
                        insertionHistory.canSafelyRedo(g).allowed
                    } else {
                        insertionHistory.canSafelyUndo(g).allowed
                    }
                    if (!valid && before32 != null) {
                        if (allowRetry) {
                            schedulePreserveSessionVerification(verifyRestartToken, expectedSessionId, 600L, allowRetry = false)
                        } else {
                            editorSessionId++
                            dismissPasteUndoCapsule()
                        }
                    }
                }
            }
        }, delayMs)
    }

    private fun updateInsertionHistoryButtons() {
        updatePasteUndoCapsuleUI()
    }

    private fun applyConfirmedUndoSideEffects(entry: InsertionHistoryManager.HistoryEntry, message: String) {
        if (entry.kind == InsertionHistoryManager.ActionKind.AUTOCORRECT) {
            if (fieldCapabilities.allowSessionLearning) {
                suppressedAutocorrectWords.add(entry.rawOriginalWord.lowercase(Locale.ROOT))
            }
            lastRevertOriginalWord = null
            refreshTypingSuggestionsAsync()
        } else {
            clearTypingSuggestionsView()
        }
        shiftStateMachine.onNonLetterInput()
        syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
        setStatus(AppUiStrings.localizeHistoryMessage(keyboardLanguage, message))
    }

    private fun performInsertionUndo() {
        val conn = currentInputConnection
        if (conn == null) {
            setStatus(AppUiStrings.undoWaitingForFieldStatus(keyboardLanguage))
            if (!insertionHistory.hasAnyUndoEntries() || !inputSessionActive) {
                dismissPasteUndoCapsule()
            }
            return
        }
        val verifyToken = ++pasteHistoryVerifyToken
        val pendingEntry = insertionHistory.peekUndo()
        val checkLen = minOf(2048, maxOf(128, (pendingEntry?.insertedText?.length ?: 128) + (pendingEntry?.beforeAnchor?.length ?: 0) + 64))
        val targetSnap = currentEditorTarget
        val pwSnap = isPasswordTarget
        val sessionSnap = editorSessionId

        submitSafely(icQueryExecutor) {
            val gateway = buildSnapshotGatewayOffMainThread(conn, checkLen, InsertionHistoryManager.ANCHOR_LENGTH, targetSnap, pwSnap)
            val preBefore = gateway.textBeforeCursor(checkLen)
            main.post {
                if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken || editorSessionId != sessionSnap) {
                    return@post
                }
                val result = insertionHistory.undo(gateway)
                val entry = result.entry
                if (!result.success || entry == null) {
                    shiftStateMachine.onNonLetterInput()
                    setStatus(AppUiStrings.localizeHistoryMessage(keyboardLanguage, result.message))
                    val shouldPreserveRetry = insertionHistory.hasAnyUndoEntries() &&
                        !gateway.isPasswordField() &&
                        (pendingEntry == null || pendingEntry.target.isCompatibleWith(gateway.currentTarget()))
                    if (shouldPreserveRetry) {
                        pasteCapsuleController.onUndoConfirmationRejected(
                            preserveUndoForRetry = true,
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        updatePasteUndoCapsuleUI()
                    } else {
                        dismissPasteUndoCapsule()
                    }
                    syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                    return@post
                }

                shadowEditor.invalidateHostSync()
                val isLargeOperation = entry.insertedText.length >= PasteUndoCapsuleSpec.LARGE_PASTE_CHAR_THRESHOLD
                pasteCapsuleController.onUndoPendingConfirmation(
                    nowMs = SystemClock.elapsedRealtime(),
                    isLargeOperation = isLargeOperation
                )
                updatePasteUndoCapsuleUI()
                setStatus(AppUiStrings.confirmingUndoStatus(keyboardLanguage))

                val verifySchedule = if (isLargeOperation) {
                    longArrayOf(35L, 120L, 260L, 450L, 700L)
                } else {
                    longArrayOf(25L, 100L, 240L, 450L)
                }
                scheduleUndoMutationVerification(entry, result.message, verifyToken, checkLen, preBefore, verifySchedule, 0)
            }
        }
    }

    private fun scheduleUndoMutationVerification(
        entry: InsertionHistoryManager.HistoryEntry,
        successMessage: String,
        verifyToken: Int,
        checkLen: Int,
        preBefore: String?,
        scheduleMs: LongArray,
        stepIndex: Int
    ) {
        if (stepIndex >= scheduleMs.size) return
        main.postDelayed({
            if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@postDelayed
            val conn = currentInputConnection
            if (conn == null) {
                insertionHistory.rollbackLastUndo(entry)
                pasteCapsuleController.onUndoConfirmationRejected(
                    preserveUndoForRetry = false,
                    nowMs = SystemClock.elapsedRealtime()
                )
                updatePasteUndoCapsuleUI()
                syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                setStatus(AppUiStrings.undoRejectedStatus(keyboardLanguage, false))
                return@postDelayed
            }
            val targetSnap = currentEditorTarget
            val pwSnap = isPasswordTarget
            submitSafely(icQueryExecutor) {
                val g = buildSnapshotGatewayOffMainThread(conn, checkLen, InsertionHistoryManager.ANCHOR_LENGTH, targetSnap, pwSnap)
                val after = g.textBeforeCursor(checkLen)
                val afterTrailing = g.textAfterCursor(InsertionHistoryManager.ANCHOR_LENGTH)
                main.post {
                    if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@post
                    if (insertionHistory.confirmUndoMutation(entry, preBefore, after, afterTrailing, g)) {
                        val isLargeOperation = entry.insertedText.length >= PasteUndoCapsuleSpec.LARGE_PASTE_CHAR_THRESHOLD
                        pasteCapsuleController.onUndoConfirmed(
                            nowMs = SystemClock.elapsedRealtime(),
                            isLargeOperation = isLargeOperation
                        )
                        updatePasteUndoCapsuleUI()
                        applyConfirmedUndoSideEffects(entry, successMessage)
                        return@post
                    }
                    if (stepIndex + 1 < scheduleMs.size) {
                        scheduleUndoMutationVerification(entry, successMessage, verifyToken, checkLen, preBefore, scheduleMs, stepIndex + 1)
                    } else {
                        insertionHistory.rollbackLastUndo(entry)
                        val canPreserve = !g.isPasswordField() && entry.target.isCompatibleWith(g.currentTarget())
                        pasteCapsuleController.onUndoConfirmationRejected(
                            preserveUndoForRetry = canPreserve,
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        updatePasteUndoCapsuleUI()
                        syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                        setStatus(AppUiStrings.undoRejectedStatus(keyboardLanguage, canPreserve))
                    }
                }
            }
        }, scheduleMs[stepIndex])
    }

    private fun performInsertionRedo() {
        val conn = currentInputConnection
        if (conn == null) {
            setStatus(AppUiStrings.redoWaitingForFieldStatus(keyboardLanguage))
            if (!insertionHistory.hasAnyRedoEntries() || !inputSessionActive) {
                dismissPasteUndoCapsule()
            }
            return
        }
        val verifyToken = ++pasteHistoryVerifyToken
        val pendingEntry = insertionHistory.peekRedo()
        val checkLen = minOf(2048, maxOf(128, (pendingEntry?.insertedText?.length ?: 128) + (pendingEntry?.beforeAnchor?.length ?: 0) + 64))
        val targetSnap = currentEditorTarget
        val pwSnap = isPasswordTarget
        val sessionSnap = editorSessionId

        submitSafely(icQueryExecutor) {
            val gateway = buildSnapshotGatewayOffMainThread(conn, checkLen, InsertionHistoryManager.ANCHOR_LENGTH, targetSnap, pwSnap)
            val preBefore = gateway.textBeforeCursor(checkLen)
            main.post {
                if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken || editorSessionId != sessionSnap) {
                    return@post
                }
                val result = insertionHistory.redo(gateway)
                val entry = result.entry
                clearTypingSuggestionsView()
                shiftStateMachine.onNonLetterInput()

                if (!result.success || entry == null) {
                    setStatus(AppUiStrings.localizeHistoryMessage(keyboardLanguage, result.message))
                    val shouldPreserveRetry = insertionHistory.hasAnyRedoEntries() &&
                        !gateway.isPasswordField() &&
                        (pendingEntry == null || pendingEntry.target.isCompatibleWith(gateway.currentTarget()))
                    if (shouldPreserveRetry) {
                        pasteCapsuleController.onRedoConfirmationRejected(
                            preserveRedoForRetry = true,
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        updatePasteUndoCapsuleUI()
                    } else {
                        dismissPasteUndoCapsule()
                    }
                    syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                    return@post
                }

                shadowEditor.invalidateHostSync()
                val isLargeOperation = entry.insertedText.length >= PasteUndoCapsuleSpec.LARGE_PASTE_CHAR_THRESHOLD
                pasteCapsuleController.onRedoPendingConfirmation(
                    nowMs = SystemClock.elapsedRealtime(),
                    isLargeOperation = isLargeOperation
                )
                updatePasteUndoCapsuleUI()
                setStatus(AppUiStrings.confirmingRedoStatus(keyboardLanguage))

                val verifySchedule = if (isLargeOperation) {
                    longArrayOf(35L, 120L, 260L, 450L, 700L)
                } else {
                    longArrayOf(25L, 100L, 240L, 450L)
                }
                scheduleRedoMutationVerification(entry, result.message, verifyToken, checkLen, preBefore, verifySchedule, 0)
            }
        }
    }

    private fun scheduleRedoMutationVerification(
        entry: InsertionHistoryManager.HistoryEntry,
        successMessage: String,
        verifyToken: Int,
        checkLen: Int,
        preBefore: String?,
        scheduleMs: LongArray,
        stepIndex: Int
    ) {
        if (stepIndex >= scheduleMs.size) return
        main.postDelayed({
            if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@postDelayed
            val conn = currentInputConnection
            if (conn == null) {
                insertionHistory.rollbackLastRedo(entry)
                pasteCapsuleController.onRedoConfirmationRejected(
                    preserveRedoForRetry = false,
                    nowMs = SystemClock.elapsedRealtime()
                )
                updatePasteUndoCapsuleUI()
                syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                setStatus(AppUiStrings.redoRejectedStatus(keyboardLanguage, false))
                return@postDelayed
            }
            val targetSnap = currentEditorTarget
            val pwSnap = isPasswordTarget
            submitSafely(icQueryExecutor) {
                val g = buildSnapshotGatewayOffMainThread(conn, checkLen, InsertionHistoryManager.ANCHOR_LENGTH, targetSnap, pwSnap)
                val after = g.textBeforeCursor(checkLen)
                val afterTrailing = g.textAfterCursor(InsertionHistoryManager.ANCHOR_LENGTH)
                main.post {
                    if (serviceDestroyed || pasteHistoryVerifyToken != verifyToken) return@post
                    if (insertionHistory.confirmRedoMutation(entry, preBefore, after, afterTrailing, g)) {
                        val isLargeOperation = entry.insertedText.length >= PasteUndoCapsuleSpec.LARGE_PASTE_CHAR_THRESHOLD
                        pasteCapsuleController.onRedoConfirmed(
                            canUndoRemaining = insertionHistory.hasAnyUndoEntries(),
                            nowMs = SystemClock.elapsedRealtime(),
                            isLargeOperation = isLargeOperation
                        )
                        updatePasteUndoCapsuleUI()
                        syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                        setStatus(AppUiStrings.localizeHistoryMessage(keyboardLanguage, successMessage))
                        return@post
                    }
                    if (stepIndex + 1 < scheduleMs.size) {
                        scheduleRedoMutationVerification(entry, successMessage, verifyToken, checkLen, preBefore, scheduleMs, stepIndex + 1)
                    } else {
                        insertionHistory.rollbackLastRedo(entry)
                        val canPreserve = !g.isPasswordField() && entry.target.isCompatibleWith(g.currentTarget())
                        pasteCapsuleController.onRedoConfirmationRejected(
                            preserveRedoForRetry = canPreserve,
                            nowMs = SystemClock.elapsedRealtime()
                        )
                        updatePasteUndoCapsuleUI()
                        syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                        setStatus(AppUiStrings.redoRejectedStatus(keyboardLanguage, canPreserve))
                    }
                }
            }
        }, scheduleMs[stepIndex])
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

    private fun refreshTypingSuggestionsAsync() {
        if (isPasswordTarget || isFilterMode || keyboardLanguage == SettingsPanelSpec.LANG_ARABIC) {
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
        val ctx = shadowEditor.currentComposingContext()
        if (ctx == null) {
            clearTypingSuggestionsView()
            if (!shadowEditor.synchronizedWithHost && shadowEditor.awaitingHostSync) {
                scheduleEditorContextSync(conn, triggerSuggestions = true, preserveManualShift = true)
            }
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
        submitSafely(suggestExecutor) {
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
                if (reqGen != suggestionGeneration.get() || serviceDestroyed || isPasswordTarget || isFilterMode || keyboardLanguage == SettingsPanelSpec.LANG_ARABIC) {
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
        return ch == " " || ch == "." || ch == "," || ch == "!" || ch == "?" || ch == ";" || ch == ":" ||
            ch == "\u060C" || ch == "\u061B" || ch == "\u061F"
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
            val before = predictedBeforeText ?: shadowEditor.beforeText(48)
            val after = shadowEditor.afterText(16)
            val hasSel = shadowEditor.hasSelection

            AutoCapitalizationPolicy.shouldAutoShift(
                capMode = resolved,
                textBeforeCursor = before,
                textAfterCursor = after,
                hasSelection = hasSel,
                androidCursorCapsMode = 0
            )
        }
        if (changed) {
            updateShiftUI()
        }
    }

    private fun commitWordBoundaryWithIntelligence(conn: InputConnection, delimiter: String) {
        suggestionGeneration.incrementAndGet()
        if (isPasswordTarget || keyboardLanguage == SettingsPanelSpec.LANG_ARABIC || !isAutoCorrectEnabled || !fieldCapabilities.allowAutocorrect) {
            lastRevertOriginalWord = null
            insertionHistory.invalidateImmediateBackspaceRevert()
            if (commitTextSafely(conn, delimiter, 1)) {
                shadowEditor.onCommitText(delimiter)
            }
            updateInsertionHistoryButtons()
            refreshTypingSuggestionsAsync()
            syncAutoCapitalization(
                preserveManualShift = false,
                predictedBeforeText = shadowEditor.beforeText(48)
            )
            return
        }

        val ctx = shadowEditor.currentComposingContext()
        val word = ctx?.composingWord.orEmpty()
        if (ctx == null || ctx.midWord || word.length < 2) {
            lastRevertOriginalWord = null
            insertionHistory.invalidateImmediateBackspaceRevert()
            if (commitTextSafely(conn, delimiter, 1)) {
                shadowEditor.onCommitText(delimiter)
            }
            updateInsertionHistoryButtons()
            refreshTypingSuggestionsAsync()
            syncAutoCapitalization(
                preserveManualShift = false,
                predictedBeforeText = shadowEditor.beforeText(48)
            )
            return
        }

        val suppressedSnapshot = suppressedAutocorrectWords.toSet()
        val cached = currentWordAnalysis?.takeIf { it.originalWord == word }
        val correction = if (cached != null) {
            cached.autoCorrection
        } else {
            typingEngine.quickHighConfidenceCorrection(
                composingWord = word,
                fullTokenBeforeCursor = ctx.fullToken,
                isSentenceStart = ctx.isSentenceStart,
                suppressedWordsLower = suppressedSnapshot
            )
        }

        if (correction != null && correction != word && word.lowercase(Locale.ROOT) !in suppressedSnapshot) {
            val committed = try {
                runCatching { conn.beginBatchEdit() }
                deleteSurroundingTextSafely(conn, word.length, 0) &&
                    commitTextSafely(conn, correction + delimiter, 1)
            } finally {
                runCatching { conn.endBatchEdit() }
            }
            if (committed) {
                shadowEditor.onReplaceBeforeCursor(word.length, correction + delimiter)
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
                    predictedBeforeText = shadowEditor.beforeText(48)
                )
                return
            }
        }

        lastRevertOriginalWord = null
        insertionHistory.invalidateImmediateBackspaceRevert()
        if (commitTextSafely(conn, delimiter, 1)) {
            shadowEditor.onCommitText(delimiter)
        }
        updateInsertionHistoryButtons()
        refreshTypingSuggestionsAsync()
        syncAutoCapitalization(
            preserveManualShift = false,
            predictedBeforeText = shadowEditor.beforeText(48)
        )
    }

    private fun renderSuggestionStrip() {
        val container = suggestionsContainer ?: return
        container.removeAllViews()
        if (isPasswordTarget || !isSuggestionsEnabled || !fieldCapabilities.allowSuggestions) return

        val revertOrig = lastRevertOriginalWord
        if (revertOrig != null) {
            container.addView(buildSuggestionChip(AppUiStrings.undoSuggestionChipLabel(keyboardLanguage, revertOrig), isHighlight = false, isRevert = true) {
                val gateway = buildShadowGateway()
                if (gateway != null) {
                    val res = insertionHistory.undo(gateway)
                    if (res.success && fieldCapabilities.allowSessionLearning) {
                        suppressedAutocorrectWords.add(revertOrig.lowercase(Locale.ROOT))
                    }
                    lastRevertOriginalWord = null
                    updateInsertionHistoryButtons()
                    refreshTypingSuggestionsAsync()
                    syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
                    setStatus(AppUiStrings.localizeHistoryMessage(keyboardLanguage, res.message))
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
        val ctx = shadowEditor.currentComposingContext() ?: return
        if (ctx.composingWord != word || ctx.midWord) return
        if (fieldCapabilities.allowSessionLearning) {
            suppressedAutocorrectWords.add(word.lowercase(Locale.ROOT))
        }
        lastRevertOriginalWord = null
        insertionHistory.invalidateImmediateBackspaceRevert()
        val after = shadowEditor.afterText(1)
        if (after.isEmpty() || !after[0].isWhitespace()) {
            if (commitTextSafely(conn, " ", 1)) {
                shadowEditor.onCommitText(" ")
            }
        }
        shiftStateMachine.onNonLetterInput()
        updateInsertionHistoryButtons()
        refreshTypingSuggestionsAsync()
        syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
    }

    private fun applyManualSuggestion(chosenWord: String) {
        val conn = currentInputConnection ?: return
        val ctx = shadowEditor.currentComposingContext() ?: return
        val word = ctx.composingWord
        if (word.isEmpty() || ctx.midWord) return
        val after = shadowEditor.afterText(1)
        val trailingSpace = if (after.isEmpty() || !after[0].isWhitespace()) " " else ""
        val committed = try {
            runCatching { conn.beginBatchEdit() }
            deleteSurroundingTextSafely(conn, word.length, 0) &&
                commitTextSafely(conn, chosenWord + trailingSpace, 1)
        } finally {
            runCatching { conn.endBatchEdit() }
        }
        if (committed) {
            shadowEditor.onReplaceBeforeCursor(word.length, chosenWord + trailingSpace)
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
            syncAutoCapitalization(preserveManualShift = false, predictedBeforeText = shadowEditor.beforeText(48))
        }
    }

    private fun insertNextWordSuggestion(nextWord: String) {
        val conn = currentInputConnection ?: return
        val after = shadowEditor.afterText(1)
        val trailingSpace = if (after.isEmpty() || !after[0].isWhitespace()) " " else ""
        val committedText = nextWord + trailingSpace
        if (commitTextSafely(conn, committedText, 1)) {
            shadowEditor.onCommitText(committedText)
        }
        lastRevertOriginalWord = null
        shiftStateMachine.onNonLetterInput()
        insertionHistory.invalidateImmediateBackspaceRevert()
        updateInsertionHistoryButtons()
        refreshTypingSuggestionsAsync()
        syncAutoCapitalization(
            preserveManualShift = false,
            predictedBeforeText = shadowEditor.beforeText(48)
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
        val ic = currentInputConnection ?: return
        submitSafely(icQueryExecutor) {
            val selected = readSelectedFromIcOffMainThread(ic)
            if (selected != null && selected.isNotEmpty()) {
                val mode = captureMode
                main.post {
                    if (serviceDestroyed || !inputSessionActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode)) return@post
                    ClipboardCaptureCoordinator.captureSelection(
                        context = this,
                        selectedText = selected,
                        isSensitiveField = isPasswordTarget
                    ) { outcome ->
                        if (!serviceDestroyed && outcome != null && outcome.savedCount > 0) {
                            setStatus(AppUiStrings.capturedCardsStatus(keyboardLanguage, outcome.savedCount, mode))
                            reloadItems()
                            scheduleExpiryCleanup()
                        }
                    }
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
        setStatus(AppUiStrings.autoCaptureStatus(keyboardLanguage, CapturePreferences.isEnabled(captureMode), captureMode))
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
                setStatus(AppUiStrings.capturedCardsStatus(keyboardLanguage, outcome.savedCount, mode))
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
            submitSafely(io) {
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
        rippleColor: Int = when {
            isClassicTheme && isNightMode -> 0x332DD4BF
            isClassicTheme -> 0x330D9488
            isNightMode -> 0x24FFFFFF
            else -> 0x1F202124
        }
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
        CHEVRON_DOWN, CHEVRON_UP, CHEVRON_LEFT, CHEVRON_RIGHT, ARROW_LEFT, ARROW_RIGHT, CLOSE, LOCK, SHIFT, SHIFT_ACTIVE, CAPS_LOCK, BACKSPACE, ENTER,
        SEND, GO, NEXT, DONE, SPACE,
        EMOJI_RECENT, EMOJI, EMOJI_PEOPLE, EMOJI_NATURE, EMOJI_FOOD, EMOJI_ACTIVITIES, EMOJI_TRAVEL, EMOJI_OBJECTS, EMOJI_SYMBOLS,
        MEDIA_STICKER, STAR, PLUS,
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
                IconType.ARROW_RIGHT -> {
                    val p = Path().apply {
                        moveTo(3.5f, 8f)
                        lineTo(12.5f, 8f)
                        moveTo(8.5f, 4f)
                        lineTo(12.5f, 8f)
                        lineTo(8.5f, 12f)
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
                IconType.MEDIA_STICKER -> {
                    val body = Path().apply {
                        moveTo(4.0f, 2.5f)
                        lineTo(12.0f, 2.5f)
                        quadTo(13.5f, 2.5f, 13.5f, 4.0f)
                        lineTo(13.5f, 9.5f)
                        lineTo(9.5f, 13.5f)
                        lineTo(4.0f, 13.5f)
                        quadTo(2.5f, 13.5f, 2.5f, 12.0f)
                        lineTo(2.5f, 4.0f)
                        quadTo(2.5f, 2.5f, 4.0f, 2.5f)
                        close()
                    }
                    canvas.drawPath(body, strokePaint)
                    val peel = Path().apply {
                        moveTo(9.5f, 13.5f)
                        lineTo(9.5f, 10.2f)
                        quadTo(9.5f, 9.5f, 10.2f, 9.5f)
                        lineTo(13.5f, 9.5f)
                    }
                    canvas.drawPath(peel, strokePaint)
                    canvas.drawCircle(6.2f, 6.6f, 0.7f, fillPaint)
                    canvas.drawCircle(9.8f, 6.6f, 0.7f, fillPaint)
                    val grin = Path().apply {
                        moveTo(6.0f, 8.8f)
                        quadTo(8.0f, 10.2f, 10.0f, 8.8f)
                    }
                    canvas.drawPath(grin, strokePaint)
                }
                IconType.STAR -> {
                    val star = Path().apply {
                        val cx = 8f
                        val cy = 8f
                        val rOut = 6.0f
                        val rIn = 2.65f
                        for (i in 0 until 10) {
                            val angle = -Math.PI / 2.0 + i * (Math.PI / 5.0)
                            val r = if (i % 2 == 0) rOut else rIn
                            val x = cx + (r * Math.cos(angle)).toFloat()
                            val y = cy + (r * Math.sin(angle)).toFloat()
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    canvas.drawPath(star, fillPaint)
                }
                IconType.PLUS -> {
                    canvas.drawLine(8f, 3.2f, 8f, 12.8f, strokePaint)
                    canvas.drawLine(3.2f, 8f, 12.8f, 8f, strokePaint)
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
