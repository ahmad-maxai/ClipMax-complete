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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
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

/**
 * ClipMax Production Native Android InputMethodService (IME).
 * Implements the approved Gboard-inspired QWERTY and numeric layout with
 * 50,000-character chunked insertion, persistent local storage access,
 * live clip filtering, pin support, and theme synchronization.
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
    @Volatile private var generation = 0
    private var listRequest = 0
    private var isPasswordTarget = false
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

    // Keyboard Layout Mode: 0 = alpha (QWERTY), 1 = numeric (?123), 2 = symbols (=\<)
    private var keyboardMode = 0
    // Shift State: 0 = off (lowercase), 1 = shifted (1-shot uppercase), 2 = caps lock
    private var shiftState = 0
    private var lastShiftTapTime = 0L

    // UI View References
    private var imeRootFrame: FrameLayout? = null
    private var imeSurfaceFrame: FrameLayout? = null
    private var contentContainer: LinearLayout? = null
    private var clipboardScrollView: android.widget.ScrollView? = null
    private var chipsContainer: LinearLayout? = null
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
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()

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
    private var isFilterMode = false // false = Typing: Host, true = Typing: Filter
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

    // Theme Color Tokens (Evaluated dynamically, reads app preference before system night mode)
    private val isNightMode: Boolean
        get() {
            val saved = runCatching { themePreferences.getString("app_theme", null) }.getOrNull()
            return when (saved) {
                "dark" -> true
                "light" -> false
                else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        }

    private val colorBg: Int get() = if (isNightMode) 0xFF151C18.toInt() else 0xFFECEFF1.toInt()
    private val colorKeyBg: Int get() = if (isNightMode) 0xFF223029.toInt() else 0xFFFFFFFF.toInt()
    private val colorKeyBorder: Int get() = if (isNightMode) 0xFF2C3D35.toInt() else 0xFFD1D9D4.toInt()
    private val colorKeyText: Int get() = if (isNightMode) 0xFFEAF1ED.toInt() else 0xFF111827.toInt()
    private val colorModBg: Int get() = if (isNightMode) 0xFF1A2520.toInt() else 0xFFDFE5E1.toInt()
    private val colorModText: Int get() = if (isNightMode) 0xFF9CB3A9.toInt() else 0xFF374151.toInt()
    private val colorAccent: Int get() = if (isNightMode) 0xFF2DD4BF.toInt() else 0xFF0D9488.toInt()
    private val colorAccentText: Int get() = if (isNightMode) 0xFF0A1F18.toInt() else 0xFFFFFFFF.toInt()
    private val colorHintText: Int get() = if (isNightMode) 0xFF7A8E85.toInt() else 0xFF8A9992.toInt()
    private val colorToolbarBg: Int get() = if (isNightMode) 0xFF18231E.toInt() else 0xFFF4F7F5.toInt()
    private val colorChipBg: Int get() = if (isNightMode) 0xFF1E2C26.toInt() else 0xFFFFFFFF.toInt()
    private val colorChipBorder: Int get() = if (isNightMode) 0xFF2C3D35.toInt() else 0xFFD1D9D4.toInt()
    private val colorSnippetBg: Int get() = if (isNightMode) 0xFF141C18.toInt() else 0xFFF3F6F4.toInt()
    private val colorSnippetBorder: Int get() = if (isNightMode) 0xFF23322B.toInt() else 0xFFE2E8E4.toInt()
    private val colorActiveTabBg: Int get() = if (isNightMode) 0xFF1A3A32.toInt() else 0xFFCCFBF1.toInt()
    private val colorActiveTabText: Int get() = if (isNightMode) 0xFF2DD4BF.toInt() else 0xFF0F766E.toInt()
    private val colorDangerText: Int get() = if (isNightMode) 0xFFF87171.toInt() else 0xFFDC2626.toInt()

    // QWERTY Letter Rows (All 26 letters: 10 + 9 + 7)
    private val alphaRow1 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
    private val alphaHints = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
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
            main.post { updateCaptureStatus(); renderClipChips() }
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

        // Observe in-app theme preference changes in real-time
        themePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "app_theme") {
                main.post { renderFullImeView() }
            }
        }
        themePreferences.registerOnSharedPreferenceChangeListener(themePreferenceListener)

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

    private fun renderFullImeView() {
        val content = contentContainer ?: return
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        pendingRenderAfterContextMenu = false
        clipboardScrollView = null
        imeRootFrame?.setBackgroundColor(colorBg)
        content.removeAllViews()
        content.setBackgroundColor(colorBg)
        letterKeyViews.clear()
        shiftButton = null
        shiftImageView = null
        searchClearBtn = null

        // 1. ClipMax Compact Toolbar
        content.addView(buildToolbar())

        // 2. Status / In-Flight Banner (Hidden unless inserting or in password field)
        content.addView(buildStatusBanner())

        if (isClipboardPanelOpen) {
            // 3. Compact Expandable Saved-Clipboard Panel
            content.addView(buildClipboardPanel())
        } else {
            // 3B. Compact Saved-Clips Horizontal Strip
            content.addView(buildSavedClipsStrip())

            // 4. Approved QWERTY / Numeric Keyboard Container
            val wrapper = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            keyboardWrapper = wrapper

            val kbContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(colorBg)
                setPadding(dp(3), dp(3), dp(3), dp(4))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            keyboardRowsContainer = kbContainer
            renderKeyboardRows()
            wrapper.addView(kbContainer)

            content.addView(wrapper)
            renderClipChips()
        }
    }

    // =========================================================================
    // 1. TOOLBAR (Uniform 28dp Control Height, Balanced Spacing, Clear States)
    // =========================================================================
    private fun buildToolbar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setBackgroundColor(colorToolbarBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))

        val controlHeight = dp(28)
        val gap = dp(4)

        // 1. Clipboard / Clips Tab Button
        val clipFg = if (isClipboardPanelOpen) colorActiveTabText else colorModText
        val clipBg = if (isClipboardPanelOpen) colorActiveTabBg else colorKeyBg
        val clipStroke = if (isClipboardPanelOpen) colorAccent else colorKeyBorder
        val clipBtn = Button(this@ClipMaxImeService).apply {
            text = "Clips"
            isAllCaps = false
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(7), 0, dp(7), 0)
            setTextColor(clipFg)
            background = roundedRipple(clipBg, dp(6f), clipStroke)
            setIcon(IconDrawable(IconType.CLIPBOARD, clipFg, 11.5f), 3.5f)
            setOnClickListener {
                isClipboardPanelOpen = !isClipboardPanelOpen
                renderFullImeView()
            }
        }
        addView(clipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, controlHeight).apply { rightMargin = gap })

        // 2. Pinned Tab Button
        val pinFg = if (pinnedOnly) colorActiveTabText else colorModText
        val pinBg = if (pinnedOnly) colorActiveTabBg else colorKeyBg
        val pinStroke = if (pinnedOnly) colorAccent else colorKeyBorder
        val pinBtn = Button(this@ClipMaxImeService).apply {
            text = "Pinned"
            isAllCaps = false
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(7), 0, dp(7), 0)
            setTextColor(pinFg)
            background = roundedRipple(pinBg, dp(6f), pinStroke)
            setIcon(IconDrawable(if (pinnedOnly) IconType.PIN_FILLED else IconType.PIN, pinFg, 11.5f), 3.5f)
            setOnClickListener {
                pinnedOnly = !pinnedOnly
                renderFullImeView()
            }
        }
        addView(pinBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, controlHeight).apply { rightMargin = gap })

        // 3. Search Filter Pill (Flexible width, integrated clear 'X')
        val searchPill = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, dp(4), 0)
            background = roundedRipple(
                colorKeyBg,
                dp(6f),
                if (isFilterMode) colorAccent else colorKeyBorder
            )
            val st = TextView(this@ClipMaxImeService).apply {
                text = if (keyboardSearchQuery.isEmpty()) "Search…" else keyboardSearchQuery
                textSize = 10f
                setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setIcon(IconDrawable(IconType.SEARCH, if (isFilterMode) colorAccent else colorHintText, 11f), 3.5f)
            }
            searchInputText = st
            addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val clearX = createIconView(IconDrawable(IconType.CLOSE, colorHintText, 11f)).apply {
                setPadding(dp(4), dp(4), dp(3), dp(4))
                visibility = if (keyboardSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                setOnClickListener {
                    keyboardSearchQuery = ""
                    updateSearchUI()
                    runKeyboardSearch()
                }
            }
            searchClearBtn = clearX
            addView(clearX, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            setOnClickListener {
                isFilterMode = true
                updateModeUI()
            }
        }
        searchPillView = searchPill
        addView(searchPill, LinearLayout.LayoutParams(0, controlHeight, 1f).apply { rightMargin = gap })

        // 4. Typing: Host / Filter Toggle
        val modeFg = if (isFilterMode) colorActiveTabText else colorModText
        val modeBg = if (isFilterMode) colorActiveTabBg else colorKeyBg
        val modeStroke = if (isFilterMode) colorAccent else colorKeyBorder
        val modeBtn = Button(this@ClipMaxImeService).apply {
            text = if (isFilterMode) "Filter" else "Host"
            isAllCaps = false
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(7), 0, dp(7), 0)
            setTextColor(modeFg)
            background = roundedRipple(modeBg, dp(6f), modeStroke)
            setIcon(IconDrawable(if (isFilterMode) IconType.SEARCH else IconType.KEYBOARD, modeFg, 11f), 3.5f)
            setOnClickListener {
                isFilterMode = !isFilterMode
                updateModeUI()
            }
        }
        modeButton = modeBtn
        addView(modeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, controlHeight).apply { rightMargin = gap })

        // 5. System Keyboard Switcher
        val imeSwitchBtn = FrameLayout(this@ClipMaxImeService).apply {
            background = roundedRipple(colorKeyBg, dp(6f), colorKeyBorder)
            contentDescription = "Switch keyboard"
            val iv = createIconView(IconDrawable(IconType.IME_SWITCH, colorModText, 13f))
            addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                showSystemImePicker()
            }
        }
        addView(imeSwitchBtn, LinearLayout.LayoutParams(controlHeight, controlHeight))
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
            dp(6f),
            if (isFilterMode) colorAccent else colorKeyBorder
        )
        searchInputText?.setIcon(IconDrawable(IconType.SEARCH, if (isFilterMode) colorAccent else colorHintText, 11f), 3.5f)
    }

    // =========================================================================
    // 1B. EXPANDABLE SAVED-CLIPBOARD PANEL
    // =========================================================================
    private fun buildClipboardPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colorBg)
        val screenH = resources.displayMetrics.heightPixels
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val targetHeight = if (isLandscape) {
            dp(170)
        } else {
            minOf(dp(276), (screenH * 0.38f).toInt()).coerceAtLeast(dp(236))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight)

        val base = if (keyboardSearchQuery.isNotEmpty()) keyboardSearchResults else items
        val visible = if (pinnedOnly) base.filter { it.pinned } else base

        // Panel Header Bar (34dp)
        val subHeader = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(8), dp(3))
            setBackgroundColor(if (isNightMode) 0xFF141C18.toInt() else 0xFFE9EFEA.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))

            val headerIcon = createIconView(IconDrawable(IconType.CLIPBOARD, colorAccent, 13f)).apply {
                setPadding(0, 0, dp(6), 0)
            }
            addView(headerIcon)

            val countTv = TextView(this@ClipMaxImeService).apply {
                text = when {
                    keyboardSearchQuery.isNotEmpty() -> "Matches (${visible.size})"
                    pinnedOnly -> "Pinned Clips (${visible.size})"
                    else -> "Saved Clips (${visible.size})"
                }
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
            }
            addView(countTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val backToKbBtn = Button(this@ClipMaxImeService).apply {
                text = "Keyboard"
                isAllCaps = false
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setTextColor(colorActiveTabText)
                background = roundedRipple(colorActiveTabBg, dp(6f), colorAccent)
                setIcon(IconDrawable(IconType.KEYBOARD, colorActiveTabText, 11f), 4f)
                setOnClickListener {
                    isClipboardPanelOpen = false
                    renderFullImeView()
                }
            }
            addView(backToKbBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)))
        }
        addView(subHeader)

        val headerDivider = View(this@ClipMaxImeService).apply {
            setBackgroundColor(colorKeyBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        addView(headerDivider)

        if (isMergeMode) {
            val mergeBanner = TextView(this@ClipMaxImeService).apply {
                text = "Merge Mode: Tap another card to merge. Tap source card to cancel."
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccentText)
                setBackgroundColor(colorAccent)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    isMergeMode = false
                    mergeSourceId = null
                    setStatus("Merge cancelled.")
                    reloadItems()
                }
            }
            addView(mergeBanner)
        }

        // Scrollable cards list
        val scrollView = android.widget.ScrollView(this@ClipMaxImeService).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
        }
        clipboardScrollView = scrollView

        val cardsList = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }

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
            // Two-column responsive grid layout (chunk items into pairs)
            var i = 0
            while (i < visible.size) {
                val rowLayout = LinearLayout(this@ClipMaxImeService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 2f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    }
                }

                val item1 = visible[i]
                val card1 = buildCompactCard(item1)
                rowLayout.addView(card1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(3)
                })

                if (i + 1 < visible.size) {
                    val item2 = visible[i + 1]
                    val card2 = buildCompactCard(item2)
                    rowLayout.addView(card2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(3)
                    })
                } else {
                    // Empty spacer view for odd count so 1st card maintains exact 50% width
                    val spacer = View(this@ClipMaxImeService).apply {
                        visibility = View.INVISIBLE
                    }
                    rowLayout.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f).apply {
                        leftMargin = dp(3)
                    })
                }

                cardsList.addView(rowLayout)
                i += 2
            }
        }

        scrollView.addView(cardsList)
        addView(scrollView)
    }

    private var undoBtnRef: Button? = null
    private var redoBtnRef: Button? = null

    private fun dismissUndoRedoControl() {
        val host = undoRedoHost ?: return
        undoRedoHost = null
        undoBtnRef = null
        redoBtnRef = null
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
        undoBtn.setTextColor(if (hasUndo) colorKeyText else colorHintText)
        undoBtn.setIcon(IconDrawable(IconType.UNDO, if (hasUndo) colorKeyText else colorHintText, 12f), 4f)

        redoBtn.isEnabled = hasRedo
        redoBtn.setTextColor(if (hasRedo) colorKeyText else colorHintText)
        redoBtn.setIcon(IconDrawable(IconType.REDO, if (hasRedo) colorKeyText else colorHintText, 12f), 4f)
    }

    private fun showUndoRedoControl() {
        dismissUndoRedoControl()
        val surface = imeSurfaceFrame ?: return
        val hasUndo = undoStack.isNotEmpty()
        val hasRedo = redoStack.isNotEmpty()
        if (!hasUndo && !hasRedo) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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

    private fun exitMergeMode() {
        if (isMergeMode) {
            isMergeMode = false
            mergeSourceId = null
            reloadItems()
        }
    }

    private fun performMerge(sourceId: String, targetId: String) {
        if (sourceId == targetId) {
            setStatus("Cannot merge a card with itself.")
            return
        }
        exitMergeMode()
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
                undoStack.push(snapshot)
                redoStack.clear()

                main.post {
                    setStatus("Merged 2 clips into 1.")
                    reloadItems()
                    showUndoRedoControl()
                }
            } catch (e: Exception) {
                main.post {
                    setStatus("Merge failed: ${e.message}")
                }
            }
        }
    }

    private fun splitItem(targetId: String) {
        io.execute {
            try {
                val splitRes = store.split(targetId)
                if (splitRes == null) {
                    main.post { setStatus("Clip text is too short to split.") }
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
                undoStack.push(snapshot)
                redoStack.clear()

                main.post {
                    setStatus("Split clip into 2 cards.")
                    reloadItems()
                    showUndoRedoControl()
                }
            } catch (e: Exception) {
                main.post {
                    setStatus("Split failed: ${e.message}")
                }
            }
        }
    }

    private fun performUndo() {
        if (undoStack.isEmpty()) return
        val snapshot = undoStack.pop()
        io.execute {
            try {
                for (added in snapshot.addedItems) {
                    store.delete(added.id)
                }
                for (orig in snapshot.removedItems) {
                    val origContent = snapshot.removedContents[orig.id] ?: ""
                    store.save(
                        id = orig.id,
                        title = orig.title,
                        content = origContent,
                        pinned = orig.pinned,
                        savedAt = orig.createdAt
                    )
                }
                redoStack.push(snapshot)
                main.post {
                    setStatus("Undid ${snapshot.description}.")
                    reloadItems()
                    updateUndoRedoControl()
                }
            } catch (e: Exception) {
                main.post {
                    setStatus("Undo failed: ${e.message}")
                }
            }
        }
    }

    private fun performRedo() {
        if (redoStack.isEmpty()) return
        val snapshot = redoStack.pop()
        io.execute {
            try {
                for (orig in snapshot.removedItems) {
                    store.delete(orig.id)
                }
                for (added in snapshot.addedItems) {
                    val addedContent = snapshot.addedContents[added.id] ?: ""
                    store.save(
                        id = added.id,
                        title = added.title,
                        content = addedContent,
                        pinned = added.pinned,
                        savedAt = added.createdAt
                    )
                }
                undoStack.push(snapshot)
                main.post {
                    setStatus("Redid ${snapshot.description}.")
                    reloadItems()
                    updateUndoRedoControl()
                }
            } catch (e: Exception) {
                main.post {
                    setStatus("Redo failed: ${e.message}")
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
                renderFullImeView()
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
                    renderFullImeView()
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

    private fun showContextMenu(targetCard: View, item: ClipItem) {
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
        val spotlightCard = buildCompactCard(item, isSpotlight = true).apply {
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

        // Layer 3: Compact side-mounted contextual action panel (Pin/Unpin, Edit, Delete, Merge, Split)
        val panelWidth = minOf(dp(142), (surfaceW * 0.44f).toInt()).coerceAtLeast(dp(112))
        val rowHeight = when {
            surfaceH < dp(220) -> dp(26)
            surfaceH < dp(260) -> dp(29)
            else -> dp(32)
        }
        val iconSize = when {
            surfaceH < dp(220) -> 11f
            surfaceH < dp(260) -> 12.5f
            else -> 14f
        }
        val panelBg = roundedDrawable(colorKeyBg, dp(10f), colorKeyBorder)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            isClickable = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(12f)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))

            fun addActionRow(
                label: String,
                iconType: IconType,
                tint: Int,
                onClick: () -> Unit
            ) {
                val btn = Button(this@ClipMaxImeService).apply {
                    text = label
                    contentDescription = label
                    isAllCaps = false
                    textSize = if (surfaceH < dp(235)) 11.5f else 12f
                    typeface = Typeface.DEFAULT_BOLD
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setTextColor(tint)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(dp(10), 0, dp(12), 0)
                    background = roundedRipple(colorKeyBg, dp(7f), 0)
                    setIcon(IconDrawable(iconType, tint, iconSize), 8f)
                    setOnClickListener { onClick() }
                }
                addView(btn, LinearLayout.LayoutParams(panelWidth - dp(8), rowHeight))
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
                setStatus("Merge Mode: Tap another card to merge with \"${item.title}\"")
                reloadItems()
            }

            addActionRow(
                label = "Split",
                iconType = IconType.SPLIT,
                tint = colorKeyText
            ) {
                dismissContextMenu(animate = true)
                io.execute {
                    splitItem(targetId)
                }
            }
        }

        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(surfaceH, View.MeasureSpec.AT_MOST)
        )
        val panelHeight = panel.measuredHeight.coerceAtLeast(rowHeight * 5 + dp(8))

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
            FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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

    private fun buildCompactCard(item: ClipItem, isSpotlight: Boolean = false): View = LinearLayout(this).apply {
        val (baseTitle, chunkLabel) = parseTitleAndChunk(item.title)
        val snippet = getSnippet(item, 72)
        val resolvedTitle = displayTitleForCard(baseTitle, snippet)

        orientation = LinearLayout.VERTICAL
        isFocusable = true
        contentDescription = "$resolvedTitle. Tap to insert. Long press for options."
        setPadding(dp(8), dp(6), dp(8), dp(6))
        val isSource = isMergeMode && item.id == mergeSourceId
        val isTargetCandidate = isMergeMode && item.id != mergeSourceId

        background = when {
            isSpotlight -> roundedDrawable(colorKeyBg, dp(8f), colorAccent)
            isSource -> roundedDrawable(if (isNightMode) 0x332DD4BF else 0x330D9488, dp(8f), colorAccent)
            isTargetCandidate -> roundedRipple(colorKeyBg, dp(8f), colorKeyBorder)
            else -> roundedRipple(colorKeyBg, dp(8f), colorKeyBorder)
        }

        // A. Header: Pin Icon, Title, Optional Multi-Chunk Badge
        val headerRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            if (item.pinned) {
                val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 10f)).apply {
                    setPadding(0, 0, dp(4), 0)
                }
                addView(pinIcon)
            }

            val titleTv = TextView(this@ClipMaxImeService).apply {
                text = resolvedTitle
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4)
            })

            if (chunkLabel != null) {
                val chunkBadge = TextView(this@ClipMaxImeService).apply {
                    text = chunkLabel
                    textSize = 8f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorActiveTabText)
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                    background = roundedDrawable(colorActiveTabBg, dp(4f), colorAccent)
                    setSingleLine(true)
                }
                addView(chunkBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            if (isSource) {
                val srcBadge = TextView(this@ClipMaxImeService).apply {
                    text = "Source"
                    textSize = 8f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccentText)
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                    background = roundedDrawable(colorAccent, dp(3f))
                    setSingleLine(true)
                }
                addView(srcBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(4)
                })
            }
        }
        addView(headerRow)

        // B. Content Preview (2 lines max, crisp wrapping without excess expansion)
        if (snippet.isNotEmpty()) {
            val snippetBox = FrameLayout(this@ClipMaxImeService).apply {
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = roundedDrawable(colorSnippetBg, dp(5f), colorSnippetBorder)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3)
                    bottomMargin = if (item.sizeBytes > 1024L) dp(3) else 0
                }

                val snipTv = TextView(this@ClipMaxImeService).apply {
                    text = snippet
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(colorModText)
                    setLineSpacing(dp(1.5f), 1f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(snipTv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            addView(snippetBox)
        }

        // C. Minimal Metadata (Size only if > 1 KiB, no copy count or timestamps)
        if (item.sizeBytes > 1024L) {
            val metaTv = TextView(this@ClipMaxImeService).apply {
                text = humanSize(item.sizeBytes)
                textSize = 8.5f
                typeface = Typeface.MONOSPACE
                setTextColor(colorHintText)
                setSingleLine(true)
            }
            addView(metaTv)
        }

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
                            v.background = roundedDrawable(if (isNightMode) 0x552DD4BF else 0x550D9488, dp(8f), colorAccent)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                v.elevation = dp(6f)
                            }
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_EXITED -> {
                            v.background = roundedRipple(colorKeyBg, dp(8f), colorKeyBorder)
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
                            v.background = roundedRipple(colorKeyBg, dp(8f), colorKeyBorder)
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
                    showContextMenu(this, item)
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
        setBackgroundColor(if (isNightMode) 0xFF131B17.toInt() else 0xFFE9EDE9.toInt())
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
        setPadding(dp(8), 0, dp(7), 0)
        background = roundedRipple(colorChipBg, dp(6f), colorChipBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
            rightMargin = dp(5)
        }

        if (item.pinned) {
            val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 10f)).apply {
                setPadding(0, 0, dp(4), 0)
            }
            addView(pinIcon)
        }

        val title = TextView(this@ClipMaxImeService).apply {
            text = baseTitle.ifEmpty { "Clip" }
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorKeyText)
            maxWidth = dp(110)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (chunkLabel != null) {
            val chunkBadge = TextView(this@ClipMaxImeService).apply {
                text = chunkLabel
                textSize = 8f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorActiveTabText)
                setPadding(dp(4), dp(1), dp(4), dp(1))
                background = roundedDrawable(colorActiveTabBg, dp(3f), 0)
                setSingleLine(true)
            }
            addView(chunkBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4)
            })
        }

        val snippet = getSnippet(item, 32)
        if (snippet.isNotEmpty()) {
            val snipTv = TextView(this@ClipMaxImeService).apply {
                text = "· $snippet"
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
                setTextColor(colorHintText)
                maxWidth = dp(86)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(snipTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4)
            })
        }

        val badge = TextView(this@ClipMaxImeService).apply {
            text = humanSize(item.sizeBytes)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setTextColor(colorHintText)
            setPadding(dp(4), dp(1), dp(4), dp(1))
            background = roundedDrawable(colorSnippetBg, dp(3f), 0)
        }
        addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(5)
        })

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
        setBackgroundColor(if (isNightMode) 0xFF0E1512.toInt() else 0xFFE0E5E2.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24))
        visibility = if (isInserting) View.VISIBLE else View.GONE
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
    // 4. APPROVED QWERTY / NUMERIC / SYMBOL KEYBOARD
    // =========================================================================
    private fun keyLp(weight: Float): LinearLayout.LayoutParams {
        val m = dp(1.5f).toInt()
        return LinearLayout.LayoutParams(0, dp(41), weight).apply {
            leftMargin = m
            rightMargin = m
        }
    }

    private fun renderKeyboardRows() {
        val container = keyboardRowsContainer ?: return
        container.removeAllViews()
        letterKeyViews.clear()

        when (keyboardMode) {
            0 -> renderAlphaRows(container)
            1 -> renderNumericRows(container)
            2 -> renderSymbolRows(container)
        }
    }

    // 4A. ALPHA (QWERTY)
    private fun renderAlphaRows(parent: LinearLayout) {
        // Row 1: 10 keys (Q W E R T Y U I O P) with 1-0 hints
        val row1 = createKeyRow()
        alphaRow1.forEachIndexed { idx, char ->
            row1.addView(createAlphaKeyWithHint(char, alphaHints[idx]), keyLp(1f))
        }
        parent.addView(row1)

        // Row 2: 9 keys (A S D F G H J K L) centered horizontally with 0.5f weight spacers
        val row2 = createKeyRow()
        row2.addView(View(this), LinearLayout.LayoutParams(0, dp(41), 0.5f))
        alphaRow2.forEach { char ->
            row2.addView(createStandardKey(char), keyLp(1f))
        }
        row2.addView(View(this), LinearLayout.LayoutParams(0, dp(41), 0.5f))
        parent.addView(row2)

        // Row 3: Shift (1.35f), 7 keys (Z X C V B N M), Backspace (1.35f)
        val row3 = createKeyRow()
        val shiftBtn = createShiftKey()
        shiftButton = shiftBtn
        row3.addView(shiftBtn, keyLp(1.35f))
        alphaRow3.forEach { char ->
            row3.addView(createStandardKey(char), keyLp(1f))
        }
        row3.addView(createBackspaceKey(), keyLp(1.35f))
        parent.addView(row3)

        // Row 4: ?123 (1.35f), comma (1f), Space (4.6f), period (1f), Enter (1.35f)
        val row4 = createKeyRow()
        row4.addView(createModifierKey("?123") {
            keyboardMode = 1
            renderKeyboardRows()
        }, keyLp(1.35f))

        row4.addView(createStandardKey(","), keyLp(1f))
        row4.addView(createSpaceKey(), keyLp(4.6f))
        row4.addView(createStandardKey("."), keyLp(1f))
        row4.addView(createEnterKey(), keyLp(1.35f))
        parent.addView(row4)
    }

    // 4B. NUMERIC (?123)
    private fun renderNumericRows(parent: LinearLayout) {
        // Row 1: 1 2 3 4 5 6 7 8 9 0 (all 10 digits visible without overflow)
        val row1 = createKeyRow()
        numRow1.forEach { digit ->
            row1.addView(createStandardKey(digit), keyLp(1f))
        }
        parent.addView(row1)

        // Row 2: @ # $ % & - + ( ) / (10 symbols)
        val row2 = createKeyRow()
        numRow2.forEach { sym ->
            row2.addView(createStandardKey(sym), keyLp(1f))
        }
        parent.addView(row2)

        // Row 3: =\< toggle (1.35f), 7 symbols (* " ' : ; ! ?), Backspace (1.35f)
        val row3 = createKeyRow()
        row3.addView(createModifierKey("=\\<") {
            keyboardMode = 2
            renderKeyboardRows()
        }, keyLp(1.35f))

        numRow3.forEach { sym ->
            row3.addView(createStandardKey(sym), keyLp(1f))
        }

        row3.addView(createBackspaceKey(), keyLp(1.35f))
        parent.addView(row3)

        // Row 4: ABC (1.35f), comma (1f), Space (4.6f), period (1f), Enter (1.35f)
        val row4 = createKeyRow()
        row4.addView(createModifierKey("ABC") {
            keyboardMode = 0
            renderKeyboardRows()
        }, keyLp(1.35f))

        row4.addView(createStandardKey(","), keyLp(1f))
        row4.addView(createSpaceKey(), keyLp(4.6f))
        row4.addView(createStandardKey("."), keyLp(1f))
        row4.addView(createEnterKey(), keyLp(1.35f))
        parent.addView(row4)
    }

    // 4C. SECONDARY SYMBOLS (=\<)
    private fun renderSymbolRows(parent: LinearLayout) {
        // Row 1: ~ ` | • √ π ÷ × § Δ (10 keys)
        val row1 = createKeyRow()
        symRow1.forEach { sym ->
            row1.addView(createStandardKey(sym), keyLp(1f))
        }
        parent.addView(row1)

        // Row 2: £ € ¥ ¢ ^ ° = { } \ (10 keys)
        val row2 = createKeyRow()
        symRow2.forEach { sym ->
            row2.addView(createStandardKey(sym), keyLp(1f))
        }
        parent.addView(row2)

        // Row 3: ?123 toggle (1.35f), 8 symbols (< > % © ® ™ [ ]), Backspace (1.35f)
        val row3 = createKeyRow()
        row3.addView(createModifierKey("?123") {
            keyboardMode = 1
            renderKeyboardRows()
        }, keyLp(1.35f))

        symRow3.forEach { sym ->
            row3.addView(createStandardKey(sym), keyLp(0.875f))
        }

        row3.addView(createBackspaceKey(), keyLp(1.35f))
        parent.addView(row3)

        // Row 4: ABC (1.35f), comma (1f), Space (4.6f), period (1f), Enter (1.35f)
        val row4 = createKeyRow()
        row4.addView(createModifierKey("ABC") {
            keyboardMode = 0
            renderKeyboardRows()
        }, keyLp(1.35f))

        row4.addView(createStandardKey(","), keyLp(1f))
        row4.addView(createSpaceKey(), keyLp(4.6f))
        row4.addView(createStandardKey("."), keyLp(1f))
        row4.addView(createEnterKey(), keyLp(1.35f))
        parent.addView(row4)
    }

    // =========================================================================
    // KEY VIEW BUILDERS, ACTIONS & FEEDBACK
    // =========================================================================
    private fun createKeyRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45))
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

    private fun createAlphaKeyWithHint(char: String, hint: String): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, dp(6f), colorKeyBorder)

        // Main Letter
        val tv = TextView(this@ClipMaxImeService).apply {
            text = if (shiftState == 0) char.lowercase(Locale.ROOT) else char.uppercase(Locale.ROOT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
        }
        letterKeyViews.add(tv to char)
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Secondary hint digit in top-right
        val hintTv = TextView(this@ClipMaxImeService).apply {
            text = hint
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(colorHintText)
            gravity = Gravity.TOP or Gravity.END
            setPadding(0, dp(2), dp(4), 0)
        }
        addView(hintTv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(
            keyView = this,
            onCommit = { handleLetterPress(char) },
            previewChar = { if (shiftState == 0) char.lowercase(Locale.ROOT) else char.uppercase(Locale.ROOT) }
        )
    }

    private fun createStandardKey(char: String): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, dp(6f), colorKeyBorder)

        val isLetter = char.length == 1 && char[0].isLetter()
        val tv = TextView(this@ClipMaxImeService).apply {
            text = if (isLetter && shiftState == 0) char.lowercase(Locale.ROOT) else char
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
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
        background = roundedRipple(colorModBg, dp(6f), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = title
            textSize = 12f
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
        background = roundedRipple(bgCol, dp(6f), strokeCol)

        val iv = createIconView(IconDrawable(iconType, fgCol, 18f))
        shiftImageView = iv
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        attachKeyTouchFeedback(this, { handleShiftPress() })
    }

    private fun createBackspaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, dp(6f), colorKeyBorder)

        val iv = createIconView(IconDrawable(IconType.BACKSPACE, colorModText, 18f))
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setupBackspaceRepeat(this)
    }

    private fun createSpaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, dp(6f), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = "Space"
            textSize = 11.5f
            setTextColor(colorHintText)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(this, { handleSpacePress() })
    }

    private fun createEnterKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorAccent, dp(6f), colorAccent)

        val iv = createIconView(IconDrawable(IconType.ENTER, colorAccentText, 18f))
        addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        attachKeyTouchFeedback(this, { handleEnterPress() })
    }

    // =========================================================================
    // INPUT CONNECTION HANDLING
    // =========================================================================
    private fun handleLetterPress(char: String) {
        val letter = if (shiftState == 0) char.lowercase(Locale.ROOT) else char.uppercase(Locale.ROOT)
        if (isFilterMode) {
            keyboardSearchQuery += letter
            updateSearchUI()
            runKeyboardSearch()
        } else {
            currentInputConnection?.commitText(letter, 1)
        }
        if (shiftState == 1) { // 1-shot shifted -> auto revert to lowercase
            shiftState = 0
            updateShiftUI()
        }
    }

    private fun handleGenericCharPress(char: String) {
        if (isFilterMode) {
            keyboardSearchQuery += char
            updateSearchUI()
            runKeyboardSearch()
        } else {
            currentInputConnection?.commitText(char, 1)
        }
    }

    private fun handleSpacePress() {
        if (isFilterMode) {
            keyboardSearchQuery += " "
            updateSearchUI()
            runKeyboardSearch()
        } else {
            currentInputConnection?.commitText(" ", 1)
        }
    }

    private fun handleBackspace() {
        if (isFilterMode) {
            if (keyboardSearchQuery.isNotEmpty()) {
                keyboardSearchQuery = keyboardSearchQuery.dropLast(1)
                updateSearchUI()
                runKeyboardSearch()
            }
        } else {
            val conn = currentInputConnection ?: return
            val selected = conn.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                conn.commitText("", 1)
            } else {
                conn.deleteSurroundingText(1, 0)
            }
        }
    }

    private fun handleEnterPress() {
        if (isFilterMode) {
            isFilterMode = false
            updateModeUI()
        } else {
            val conn = currentInputConnection ?: return
            val info = currentInputEditorInfo
            val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                conn.performEditorAction(action)
            } else {
                conn.commitText("\n", 1)
            }
        }
    }

    private fun handleShiftPress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShiftTapTime < 300) {
            shiftState = if (shiftState == 2) 0 else 2 // Double tap -> Caps Lock
        } else {
            shiftState = if (shiftState == 0) 1 else 0 // Single tap -> Toggle Shifted
        }
        lastShiftTapTime = now
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
        btn.background = roundedRipple(bgCol, dp(6f), strokeCol)
        shiftImageView?.setImageDrawable(IconDrawable(iconType, fgCol, 18f))
    }

    private fun updateSearchUI() {
        searchInputText?.text = if (keyboardSearchQuery.isEmpty()) "Search…" else keyboardSearchQuery
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
            main.post {
                if (request != listRequest || imeRootFrame == null) return@post
                items = found.sortedWith(
                    compareByDescending<ClipItem> { it.pinned }
                        .thenByDescending { it.createdAt }
                        .thenBy { it.title }
                )
                renderClipChips()
                if (isClipboardPanelOpen) {
                    if (contextMenuHost != null) {
                        pendingRenderAfterContextMenu = true
                    } else {
                        renderFullImeView()
                    }
                }
                if (keyboardSearchQuery.isNotEmpty()) runKeyboardSearch()
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
                if (isClipboardPanelOpen) {
                    if (contextMenuHost != null) {
                        pendingRenderAfterContextMenu = true
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
        isInserting = true
        insertedCodePoints = 0
        lastReportedCodePoints = 0
        activeItemCodePoints = 0
        setStatus(initialStatus)

        insertTask = inserter.submit {
            try {
                val source = openSource()
                activeItemCodePoints = source.characterCount
                main.post {
                    if (generation == token) setStatus("Inserting $title (${activeItemCodePoints.grouped()} chars)…")
                }
                val complete = source.reader.use { reader ->
                    TextChunker.forEachChunk(
                        reader = reader,
                        isCancelled = { generation != token || Thread.currentThread().isInterrupted }
                    ) { chunk -> deliverChunk(connection, token, chunk) }
                }
                main.post {
                    if (generation == token) {
                        isInserting = false
                        setStatus(if (complete) "Inserted ${insertedCodePoints.grouped()} chars." else "Insertion paused.")
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                main.post {
                    if (generation == token) {
                        isInserting = false
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
        statusBannerView?.visibility = if (isInserting || isPasswordTarget) View.VISIBLE else View.GONE
        statusCancelBtn?.visibility = if (isInserting) View.VISIBLE else View.GONE
    }

    private fun getSnippet(item: ClipItem, maxChars: Int = 40): String {
        return try {
            store.openTextReader(item.id).use { reader ->
                val buf = CharArray(maxChars)
                val read = reader.read(buf, 0, maxChars)
                if (read > 0) String(buf, 0, read).replace("\n", " ").trim() else ""
            }
        } catch (_: Throwable) {
            ""
        }
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
        insertTask?.cancel(true)
        insertTask = null
        isInserting = false
        isPasswordTarget = isSensitiveField(attribute.inputType)
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
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
        updateCaptureMonitoring()
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) {
            capturePrimaryClip(CaptureSource.CLIPBOARD_POLL)
        }
        reloadItems()
        scheduleExpiryCleanup()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (contextMenuHost != null || isMergeMode || undoRedoHost != null) {
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
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowHidden() {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode()
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode()
        super.onConfigurationChanged(newConfig)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode()
        inputViewActive = false
        updateCaptureMonitoring()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        dismissContextMenu(animate = false)
        dismissUndoRedoControl()
        exitMergeMode()
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
        exitMergeMode()
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
        super.onDestroy()
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

    private fun roundedRipple(normalColor: Int, cornerRadiusPx: Float, strokeColor: Int = 0): RippleDrawable {
        val content = roundedDrawable(normalColor, cornerRadiusPx, strokeColor)
        val mask = roundedDrawable(0xFFFFFFFF.toInt(), cornerRadiusPx)
        return RippleDrawable(
            ColorStateList.valueOf(if (isNightMode) 0x332DD4BF else 0x330D9488),
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
        CHEVRON_DOWN, CHEVRON_UP, CLOSE, LOCK, SHIFT, SHIFT_ACTIVE, CAPS_LOCK, BACKSPACE, ENTER,
        MERGE, SPLIT, UNDO, REDO
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
                    canvas.drawLine(8f, 1.5f, 8f, 4f, strokePaint)
                    canvas.drawRoundRect(RectF(5f, 4f, 11f, 7.5f), 1f, 1f, strokePaint)
                    canvas.drawLine(8f, 7.5f, 8f, 14.5f, strokePaint)
                }
                IconType.PIN_FILLED -> {
                    canvas.drawLine(8f, 1.5f, 8f, 4f, strokePaint)
                    canvas.drawRoundRect(RectF(5f, 4f, 11f, 7.5f), 1f, 1f, fillPaint)
                    canvas.drawLine(8f, 7.5f, 8f, 14.5f, strokePaint)
                }
                IconType.KEYBOARD -> {
                    canvas.drawRoundRect(RectF(2f, 3.5f, 14f, 12.5f), 2f, 2f, strokePaint)
                    canvas.drawCircle(5f, 6.5f, 0.75f, fillPaint)
                    canvas.drawCircle(8f, 6.5f, 0.75f, fillPaint)
                    canvas.drawCircle(11f, 6.5f, 0.75f, fillPaint)
                    canvas.drawLine(5f, 9.5f, 11f, 9.5f, strokePaint)
                }
                IconType.IME_SWITCH -> {
                    canvas.drawCircle(8f, 8f, 5.75f, strokePaint)
                    canvas.drawOval(RectF(5.5f, 2.25f, 10.5f, 13.75f), strokePaint)
                    canvas.drawLine(2.5f, 8f, 13.5f, 8f, strokePaint)
                }
                IconType.SEARCH -> {
                    canvas.drawCircle(6.5f, 6.5f, 4f, strokePaint)
                    canvas.drawLine(9.5f, 9.5f, 13.5f, 13.5f, strokePaint)
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
                IconType.CLOSE -> {
                    canvas.drawLine(4f, 4f, 12f, 12f, strokePaint)
                    canvas.drawLine(12f, 4f, 4f, 12f, strokePaint)
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
