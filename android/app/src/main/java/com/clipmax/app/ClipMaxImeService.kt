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
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import java.text.SimpleDateFormat
import java.util.Date
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
    private var contentContainer: LinearLayout? = null
    private var chipsContainer: LinearLayout? = null
    private var keyboardRowsContainer: LinearLayout? = null
    private var statusBannerView: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var statusCancelBtn: Button? = null
    private var searchInputText: TextView? = null
    private var searchPillView: View? = null
    private var modeButton: Button? = null
    private var shiftButton: View? = null
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()

    // Clipboard & Touch Feedback States
    private var isClipboardPanelOpen = false
    private var isFilterMode = false // false = Typing: Host, true = Typing: Filter
    private var expandedCardId: String? = null
    private var deletingItemId: String? = null
    private var keyboardWrapper: FrameLayout? = null
    private var keyPopupView: FrameLayout? = null
    private var keyPopupText: TextView? = null

    private val themePreferences: SharedPreferences by lazy {
        getSharedPreferences("clipmax_theme_prefs", Context.MODE_PRIVATE)
    }
    private var themePreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var pendingSelectionCapture: Runnable? = null
    private var expiryCleanupRunnable: Runnable? = null
    private var lastCaptureFingerprint: Int? = null
    private var lastCaptureAtElapsed = 0L

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
        store = ClipStore(filesDir)
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
            main.post { capturePrimaryClip(initialCheck = false) }
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
        root.addView(content)

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
        imeRootFrame?.setBackgroundColor(colorBg)
        content.removeAllViews()
        content.setBackgroundColor(colorBg)
        letterKeyViews.clear()
        shiftButton = null

        // 1. ClipMax Compact Toolbar (Simplified: Brand, Clips, Pinned, Search, Typing: Host/Filter)
        content.addView(buildToolbar())

        // 2. Status / In-Flight Banner (Hidden unless inserting or in password field)
        content.addView(buildStatusBanner())

        if (isClipboardPanelOpen) {
            // 3. Compact Expandable Saved-Clipboard Panel
            content.addView(buildClipboardPanel())
        } else {
            // 3B. Compact Saved-Clips Horizontal Strip (~32dp)
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
                setPadding(dp(2), dp(2), dp(2), dp(2))
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
    // 1. TOOLBAR
    // =========================================================================
    private fun buildToolbar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(2))
        setBackgroundColor(colorToolbarBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))

        // 1. Brand Label
        val brand = TextView(this@ClipMaxImeService).apply {
            text = "ClipMax"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
        }
        addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })

        // 2. Dedicated Clipboard Toggle Button (Vector icon, no emojis)
        val clipIconColor = if (isClipboardPanelOpen) colorAccentText else colorModText
        val clipBtn = Button(this@ClipMaxImeService).apply {
            text = "Clips"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), dp(1), dp(6), dp(1))
            setTextColor(clipIconColor)
            background = roundedRipple(if (isClipboardPanelOpen) colorAccent else colorKeyBg, dp(4f), colorKeyBorder)
            setIcon(IconDrawable(IconType.CLIPBOARD, clipIconColor, 11f), 3f)
            setOnClickListener {
                isClipboardPanelOpen = !isClipboardPanelOpen
                deletingItemId = null
                renderFullImeView()
            }
        }
        addView(clipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply { rightMargin = dp(4) })

        // 3. Pinned Toggle Button (Vector icon, no emojis)
        val pinIconColor = if (pinnedOnly) colorAccentText else colorModText
        val pinBtn = Button(this@ClipMaxImeService).apply {
            text = "Pinned"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), dp(1), dp(6), dp(1))
            setTextColor(pinIconColor)
            background = roundedRipple(if (pinnedOnly) colorAccent else colorKeyBg, dp(4f), colorKeyBorder)
            setIcon(IconDrawable(if (pinnedOnly) IconType.PIN_FILLED else IconType.PIN, pinIconColor, 11f), 3f)
            setOnClickListener {
                pinnedOnly = !pinnedOnly
                renderFullImeView()
            }
        }
        addView(pinBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply { rightMargin = dp(4) })

        // 4. Search Filter Pill Button (Vector search icon & close icon, no emojis)
        val searchPill = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(4), 0)
            background = roundedRipple(
                colorKeyBg,
                dp(4f),
                if (isFilterMode) colorAccent else colorKeyBorder
            )
            val st = TextView(this@ClipMaxImeService).apply {
                text = if (keyboardSearchQuery.isEmpty()) "Search…" else keyboardSearchQuery
                textSize = 9.5f
                setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setIcon(IconDrawable(IconType.SEARCH, colorHintText, 10f), 3f)
            }
            searchInputText = st
            addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (keyboardSearchQuery.isNotEmpty()) {
                val clearX = createIconView(IconDrawable(IconType.CLOSE, colorHintText, 10f)).apply {
                    setPadding(dp(4), dp(2), dp(2), dp(2))
                    setOnClickListener {
                        keyboardSearchQuery = ""
                        searchInputText?.text = "Search…"
                        searchInputText?.setTextColor(colorHintText)
                        runKeyboardSearch()
                    }
                }
                addView(clearX, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            setOnClickListener {
                isFilterMode = true
                updateModeUI()
            }
        }
        searchPillView = searchPill
        addView(searchPill, LinearLayout.LayoutParams(0, dp(26), 1f).apply { rightMargin = dp(4) })

        // 5. Typing: Host / Filter Mode Button (Vector icon, no emojis)
        val modeColor = if (isFilterMode) colorAccentText else colorModText
        val modeBtn = Button(this@ClipMaxImeService).apply {
            text = if (isFilterMode) "Filter" else "Host"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), dp(1), dp(6), dp(1))
            setTextColor(modeColor)
            background = roundedRipple(
                if (isFilterMode) colorAccent else colorKeyBg,
                dp(4f),
                if (isFilterMode) colorAccent else colorKeyBorder
            )
            setIcon(IconDrawable(if (isFilterMode) IconType.SEARCH else IconType.KEYBOARD, modeColor, 10f), 3f)
            setOnClickListener {
                isFilterMode = !isFilterMode
                updateModeUI()
            }
        }
        modeButton = modeBtn
        addView(modeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)))
    }

    private fun updateModeUI() {
        val btn = modeButton ?: return
        val modeColor = if (isFilterMode) colorAccentText else colorModText
        btn.text = if (isFilterMode) "Filter" else "Host"
        btn.setTextColor(modeColor)
        btn.background = roundedRipple(
            if (isFilterMode) colorAccent else colorKeyBg,
            dp(4f),
            if (isFilterMode) colorAccent else colorKeyBorder
        )
        btn.setIcon(IconDrawable(if (isFilterMode) IconType.SEARCH else IconType.KEYBOARD, modeColor, 10f), 3f)

        searchPillView?.background = roundedRipple(
            colorKeyBg,
            dp(4f),
            if (isFilterMode) colorAccent else colorKeyBorder
        )
    }

    // =========================================================================
    // 1B. COMPACT EXPANDABLE SAVED-CLIPBOARD PANEL
    // =========================================================================
    private fun buildClipboardPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colorBg)
        val screenH = resources.displayMetrics.heightPixels
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val targetHeight = if (isLandscape) {
            dp(160)
        } else {
            minOf(dp(250), (screenH * 0.35f).toInt()).coerceAtLeast(dp(220))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight)

        val visible = when {
            keyboardSearchQuery.isNotEmpty() -> keyboardSearchResults
            pinnedOnly -> items.filter { it.pinned }
            else -> items
        }

        // Compact Subheader bar (height 24dp)
        val subHeader = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(1), dp(8), dp(1))
            setBackgroundColor(if (isNightMode) 0xFF18231E.toInt() else 0xFFF0F4F2.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24))

            val countTv = TextView(this@ClipMaxImeService).apply {
                text = if (keyboardSearchQuery.isNotEmpty()) {
                    "Clips found (${visible.size})"
                } else if (pinnedOnly) {
                    "Pinned Clips (${visible.size})"
                } else {
                    "Saved Clips (${visible.size})"
                }
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
            }
            addView(countTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val backToKbBtn = Button(this@ClipMaxImeService).apply {
                text = "Keyboard"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(6), 0, dp(6), 0)
                setTextColor(colorAccent)
                background = roundedRipple(colorKeyBg, dp(4f), colorKeyBorder)
                setIcon(IconDrawable(IconType.KEYBOARD, colorAccent, 10f), 2.5f)
                setOnClickListener {
                    isClipboardPanelOpen = false
                    deletingItemId = null
                    renderFullImeView()
                }
            }
            addView(backToKbBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)))
        }
        addView(subHeader)

        // Scrollable cards list
        val scrollView = android.widget.ScrollView(this@ClipMaxImeService).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
        }

        val cardsList = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }

        if (isPasswordTarget) {
            cardsList.addView(TextView(this@ClipMaxImeService).apply {
                text = "Password field active: Saved clips withheld for privacy"
                textSize = 10.5f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                gravity = Gravity.CENTER
                setIcon(IconDrawable(IconType.LOCK, colorHintText, 12f), 3f)
            })
        } else if (visible.isEmpty()) {
            cardsList.addView(TextView(this@ClipMaxImeService).apply {
                text = if (keyboardSearchQuery.isNotEmpty()) {
                    "No clips match \"$keyboardSearchQuery\""
                } else {
                    "No saved clips yet."
                }
                textSize = 10.5f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                gravity = Gravity.CENTER
            })
        } else {
            visible.forEach { item ->
                cardsList.addView(buildExpandedCard(item))
            }
        }

        scrollView.addView(cardsList)
        addView(scrollView)
    }

    private fun buildExpandedCard(item: ClipItem): View = LinearLayout(this).apply {
        val isExpanded = expandedCardId == item.id
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = roundedRipple(colorKeyBg, dp(6f), if (isExpanded) colorAccent else colorKeyBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(3)
        }

        // Header: Pin icon, Title, Chevron arrow (Zero Emojis)
        val headerRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            if (item.pinned) {
                val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 11f)).apply {
                    setPadding(0, 0, dp(4), 0)
                }
                addView(pinIcon)
            }

            val titleTv = TextView(this@ClipMaxImeService).apply {
                text = item.title
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorKeyText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val chevronIcon = createIconView(
                IconDrawable(
                    if (isExpanded) IconType.CHEVRON_UP else IconType.CHEVRON_DOWN,
                    colorModText,
                    11f
                )
            ).apply {
                setPadding(dp(4), 0, dp(2), 0)
            }
            addView(chevronIcon)
        }
        addView(headerRow)

        // Snippet preview (1 line collapsed, 2 lines expanded)
        val snippet = getSnippet(item, 80)
        if (snippet.isNotEmpty()) {
            val snipTv = TextView(this@ClipMaxImeService).apply {
                text = snippet
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(colorHintText)
                maxLines = if (isExpanded) 2 else 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, dp(1))
            }
            addView(snipTv)
        }

        // Metadata: chars and size
        val metaTv = TextView(this@ClipMaxImeService).apply {
            text = "${item.characterCount.grouped()} chars · ${humanSize(item.sizeBytes)}"
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setTextColor(colorHintText)
        }
        addView(metaTv)

        setOnClickListener {
            expandedCardId = if (isExpanded) null else item.id
            deletingItemId = null
            renderFullImeView()
        }

        // Expanded actions area (Compact, balanced equal-width buttons)
        if (isExpanded) {
            val divider = View(this@ClipMaxImeService).apply {
                setBackgroundColor(colorKeyBorder)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                }
            }
            addView(divider)

            if (deletingItemId == item.id) {
                val confirmRow = LinearLayout(this@ClipMaxImeService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    val prompt = TextView(this@ClipMaxImeService).apply {
                        text = "Delete clip?"
                        textSize = 9.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(0xFFF87171.toInt())
                    }
                    addView(prompt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    val cancelBtn = Button(this@ClipMaxImeService).apply {
                        text = "Cancel"
                        textSize = 9f
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(dp(8), 0, dp(8), 0)
                        setTextColor(colorModText)
                        background = roundedRipple(colorModBg, dp(4f), colorKeyBorder)
                        setOnClickListener {
                            deletingItemId = null
                            renderFullImeView()
                        }
                    }
                    addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply { rightMargin = dp(4) })

                    val deleteBtn = Button(this@ClipMaxImeService).apply {
                        text = "Delete"
                        textSize = 9f
                        typeface = Typeface.DEFAULT_BOLD
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(dp(8), 0, dp(8), 0)
                        setTextColor(0xFFFFFFFF.toInt())
                        background = roundedRipple(0xFFEF4444.toInt(), dp(4f), 0xFFEF4444.toInt())
                        setOnClickListener {
                            deletingItemId = null
                            expandedCardId = null
                            io.execute {
                                store.delete(item.id)
                                main.post { reloadItems() }
                            }
                        }
                    }
                    addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)))
                }
                addView(confirmRow)
            } else {
                val actionRow = LinearLayout(this@ClipMaxImeService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    weightSum = 4f

                    val insertBtn = Button(this@ClipMaxImeService).apply {
                        text = "Insert"
                        textSize = 9f
                        typeface = Typeface.DEFAULT_BOLD
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        gravity = Gravity.CENTER
                        setTextColor(colorAccentText)
                        background = roundedRipple(colorAccent, dp(4f), colorAccent)
                        setIcon(IconDrawable(IconType.INSERT, colorAccentText, 10f), 2f)
                        setOnClickListener {
                            insertItem(item)
                        }
                    }
                    addView(insertBtn, LinearLayout.LayoutParams(0, dp(22), 1f).apply {
                        rightMargin = dp(2)
                    })

                    val editBtn = Button(this@ClipMaxImeService).apply {
                        text = "Edit"
                        textSize = 9f
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        gravity = Gravity.CENTER
                        setTextColor(colorModText)
                        background = roundedRipple(colorModBg, dp(4f), colorKeyBorder)
                        setIcon(IconDrawable(IconType.EDIT, colorModText, 10f), 2f)
                        setOnClickListener {
                            try {
                                val intent = Intent(this@ClipMaxImeService, MainActivity::class.java).apply {
                                    action = Intent.ACTION_VIEW
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    putExtra(MainActivity.EXTRA_EDIT_ITEM_ID, item.id)
                                }
                                startActivity(intent)
                            } catch (_: Throwable) {}
                        }
                    }
                    addView(editBtn, LinearLayout.LayoutParams(0, dp(22), 1f).apply {
                        rightMargin = dp(2)
                    })

                    val pinColor = if (item.pinned) colorAccent else colorModText
                    val pinBtn = Button(this@ClipMaxImeService).apply {
                        text = if (item.pinned) "Unpin" else "Pin"
                        textSize = 9f
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        gravity = Gravity.CENTER
                        setTextColor(pinColor)
                        background = roundedRipple(colorModBg, dp(4f), colorKeyBorder)
                        setIcon(IconDrawable(if (item.pinned) IconType.PIN_FILLED else IconType.PIN, pinColor, 10f), 2f)
                        setOnClickListener {
                            io.execute {
                                store.setPinned(item.id, !item.pinned)
                                main.post { reloadItems() }
                            }
                        }
                    }
                    addView(pinBtn, LinearLayout.LayoutParams(0, dp(22), 1f).apply {
                        rightMargin = dp(2)
                    })

                    val delColor = 0xFFF87171.toInt()
                    val delBtn = Button(this@ClipMaxImeService).apply {
                        text = "Delete"
                        textSize = 9f
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        gravity = Gravity.CENTER
                        setTextColor(delColor)
                        background = roundedRipple(colorModBg, dp(4f), 0x40F87171.toInt())
                        setIcon(IconDrawable(IconType.DELETE, delColor, 10f), 2f)
                        setOnClickListener {
                            deletingItemId = item.id
                            renderFullImeView()
                        }
                    }
                    addView(delBtn, LinearLayout.LayoutParams(0, dp(22), 1f))
                }
                addView(actionRow)
            }
        }
    }

    // =========================================================================
    // 2. SAVED-CLIPS HORIZONTAL STRIP
    // =========================================================================
    private fun buildSavedClipsStrip(): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(if (isNightMode) 0xFF131B17.toInt() else 0xFFE9EDE9.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))

        val container = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
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
                setIcon(IconDrawable(IconType.LOCK, colorHintText, 11f), 3f)
            })
            return
        }

        val visible = when {
            keyboardSearchQuery.isNotEmpty() -> keyboardSearchResults
            pinnedOnly -> items.filter { it.pinned }
            else -> items
        }

        if (visible.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (keyboardSearchQuery.isNotEmpty()) "No clips match \"$keyboardSearchQuery\"" else "No saved clips. Copy text to create cards."
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
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = roundedRipple(colorChipBg, dp(5f), colorChipBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
            rightMargin = dp(6)
        }

        // Pinned icon (vector, no emoji)
        if (item.pinned) {
            val pinIcon = createIconView(IconDrawable(IconType.PIN_FILLED, colorAccent, 10f)).apply {
                setPadding(0, 0, dp(3), 0)
            }
            addView(pinIcon)
        }

        // Title
        val title = TextView(this@ClipMaxImeService).apply {
            text = item.title
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorKeyText)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Snippet preview
        val snippet = getSnippet(item, 40)
        if (snippet.isNotEmpty()) {
            val snipTv = TextView(this@ClipMaxImeService).apply {
                text = " · $snippet"
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(colorHintText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(snipTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Size badge
        val badge = TextView(this@ClipMaxImeService).apply {
            text = "  ${humanSize(item.sizeBytes)}"
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(colorHintText)
        }
        addView(badge)

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
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), 0, dp(6), 0)
            setTextColor(0xFFF87171.toInt())
            background = roundedDrawable(0x22F87171, dp(4f))
            setOnClickListener { cancelInsertion(userInitiated = true) }
        }
        statusCancelBtn = cancel
        addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)))
    }

    // =========================================================================
    // 4. APPROVED QWERTY / NUMERIC / SYMBOL KEYBOARD
    // =========================================================================
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
            row1.addView(createAlphaKeyWithHint(char, alphaHints[idx]), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }
        parent.addView(row1)

        // Row 2: 9 keys (A S D F G H J K L) centered horizontally with 0.5f weight spacers
        val row2 = createKeyRow()
        row2.addView(View(this), LinearLayout.LayoutParams(0, dp(42), 0.5f))
        alphaRow2.forEach { char ->
            row2.addView(createStandardKey(char), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }
        row2.addView(View(this), LinearLayout.LayoutParams(0, dp(42), 0.5f))
        parent.addView(row2)

        // Row 3: Shift (1.35f), 7 keys (Z X C V B N M), Backspace (1.35f)
        val row3 = createKeyRow()
        val shiftBtn = createShiftKey()
        shiftButton = shiftBtn
        row3.addView(shiftBtn, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })
        alphaRow3.forEach { char ->
            row3.addView(createStandardKey(char), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }
        row3.addView(createBackspaceKey(), LinearLayout.LayoutParams(0, dp(42), 1.35f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })
        parent.addView(row3)

        // Row 4: ?123 (1.35f), comma (1f), Space (4.6f), period (1f), Enter (1.35f)
        val row4 = createKeyRow()
        row4.addView(createModifierKey("?123") {
            keyboardMode = 1
            renderKeyboardRows()
        }, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply { leftMargin = dp(1); rightMargin = dp(1) })

        row4.addView(createStandardKey(","), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createSpaceKey(), LinearLayout.LayoutParams(0, dp(42), 4.6f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createStandardKey("."), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createEnterKey(), LinearLayout.LayoutParams(0, dp(42), 1.35f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })
        parent.addView(row4)
    }

    // 4B. NUMERIC (?123)
    private fun renderNumericRows(parent: LinearLayout) {
        // Row 1: 1 2 3 4 5 6 7 8 9 0 (all 10 digits visible without overflow)
        val row1 = createKeyRow()
        numRow1.forEach { digit ->
            row1.addView(createStandardKey(digit), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }
        parent.addView(row1)

        // Row 2: @ # $ % & - + ( ) / (10 symbols)
        val row2 = createKeyRow()
        numRow2.forEach { sym ->
            row2.addView(createStandardKey(sym), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }
        parent.addView(row2)

        // Row 3: =\< toggle (1.35f), 7 symbols (* " ' : ; ! ?), Backspace (1.35f)
        val row3 = createKeyRow()
        row3.addView(createModifierKey("=\\<") {
            keyboardMode = 2
            renderKeyboardRows()
        }, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply { leftMargin = dp(1); rightMargin = dp(1) })

        numRow3.forEach { sym ->
            row3.addView(createStandardKey(sym), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }

        row3.addView(createBackspaceKey(), LinearLayout.LayoutParams(0, dp(42), 1.35f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })
        parent.addView(row3)

        // Row 4: ABC (1.35f), comma (1f), Space (4.6f), period (1f), Enter (1.35f)
        val row4 = createKeyRow()
        row4.addView(createModifierKey("ABC") {
            keyboardMode = 0
            renderKeyboardRows()
        }, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply { leftMargin = dp(1); rightMargin = dp(1) })

        row4.addView(createStandardKey(","), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createSpaceKey(), LinearLayout.LayoutParams(0, dp(42), 4.6f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createStandardKey("."), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createEnterKey(), LinearLayout.LayoutParams(0, dp(42), 1.35f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })
        parent.addView(row4)
    }

    // 4C. SECONDARY SYMBOLS (=\<)
    private fun renderSymbolRows(parent: LinearLayout) {
        // Row 1: ~ ` | • √ π ÷ × § Δ (10 keys)
        val row1 = createKeyRow()
        symRow1.forEach { sym ->
            row1.addView(createStandardKey(sym), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }
        parent.addView(row1)

        // Row 2: £ € ¥ ¢ ^ ° = { } \ (10 keys)
        val row2 = createKeyRow()
        symRow2.forEach { sym ->
            row2.addView(createStandardKey(sym), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }
        parent.addView(row2)

        // Row 3: ?123 toggle (1.35f), 8 symbols (< > % © ® ™ [ ]), Backspace (1.35f)
        val row3 = createKeyRow()
        row3.addView(createModifierKey("?123") {
            keyboardMode = 1
            renderKeyboardRows()
        }, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply { leftMargin = dp(1); rightMargin = dp(1) })

        symRow3.forEach { sym ->
            row3.addView(createStandardKey(sym), LinearLayout.LayoutParams(0, dp(42), 0.875f).apply {
                leftMargin = dp(1); rightMargin = dp(1)
            })
        }

        row3.addView(createBackspaceKey(), LinearLayout.LayoutParams(0, dp(42), 1.35f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })
        parent.addView(row3)

        // Row 4: ABC (1.35f), comma (1f), Space (4.6f), period (1f), Enter (1.35f)
        val row4 = createKeyRow()
        row4.addView(createModifierKey("ABC") {
            keyboardMode = 0
            renderKeyboardRows()
        }, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply { leftMargin = dp(1); rightMargin = dp(1) })

        row4.addView(createStandardKey(","), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createSpaceKey(), LinearLayout.LayoutParams(0, dp(42), 4.6f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createStandardKey("."), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })

        row4.addView(createEnterKey(), LinearLayout.LayoutParams(0, dp(42), 1.35f).apply {
            leftMargin = dp(1); rightMargin = dp(1)
        })
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
        val (bgCol, txtCol) = when (shiftState) {
            2 -> colorAccent to colorAccentText
            1 -> (if (isNightMode) 0xFF263A31.toInt() else 0xFFCCFBF1.toInt()) to (if (isNightMode) colorAccent else 0xFF0F766E.toInt())
            else -> colorModBg to colorModText
        }
        background = roundedRipple(bgCol, dp(6f), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = if (shiftState == 2) "⇪" else "⇧"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(txtCol)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(this, { handleShiftPress() })
    }

    private fun createBackspaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorModBg, dp(6f), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = "⌫"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorModText)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setupBackspaceRepeat(this)
    }

    private fun createSpaceKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, dp(6f), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = "Space"
            textSize = 12f
            setTextColor(colorHintText)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        attachKeyTouchFeedback(this, { handleSpacePress() })
    }

    private fun createEnterKey(): View = FrameLayout(this).apply {
        background = roundedRipple(colorAccent, dp(6f), colorAccent)

        val tv = TextView(this@ClipMaxImeService).apply {
            text = "↵"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentText)
            gravity = Gravity.CENTER
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

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
        val (bgCol, txtCol) = when (shiftState) {
            2 -> colorAccent to colorAccentText
            1 -> (if (isNightMode) 0xFF263A31.toInt() else 0xFFCCFBF1.toInt()) to (if (isNightMode) colorAccent else 0xFF0F766E.toInt())
            else -> colorModBg to colorModText
        }
        btn.background = roundedRipple(bgCol, dp(6f), colorKeyBorder)
        (btn.findViewById<TextView>(android.R.id.text1) ?: (btn as? ViewGroup)?.getChildAt(0) as? TextView)?.apply {
            text = if (shiftState == 2) "⇪" else "⇧"
            setTextColor(txtCol)
        }
    }

    private fun updateSearchUI() {
        searchInputText?.text = if (keyboardSearchQuery.isEmpty()) "Search clips…" else keyboardSearchQuery
        searchInputText?.setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
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
                if (isClipboardPanelOpen) renderFullImeView()
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
                if (isClipboardPanelOpen) renderFullImeView()
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
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) {
            main.postDelayed({ captureCurrentSelection() }, 250)
        }
        renderClipChips()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
        updateCaptureMonitoring()
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) capturePrimaryClip(initialCheck = true)
        reloadItems()
        scheduleExpiryCleanup()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputViewActive = false
        updateCaptureMonitoring()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        generation++
        inputSessionActive = false
        inputViewActive = false
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
        generation++
        inputSessionActive = false
        inputViewActive = false
        updateCaptureMonitoring()
        capturePreferences.unregisterOnSharedPreferenceChangeListener(capturePreferenceListener)
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
        if (!inputSessionActive || !inputViewActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode)) return
        val selected = runCatching { currentInputConnection?.getSelectedText(0) }.getOrNull()
        if (selected != null && selected.isNotEmpty()) queueAutomaticCapture(selected, "selection")
    }

    private fun updateCaptureMonitoring() {
        val manager = clipboardManager ?: return
        val listener = clipboardListener ?: return
        val shouldListen = inputSessionActive && inputViewActive && !isPasswordTarget && CapturePreferences.isEnabled(captureMode)
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

    private fun capturePrimaryClip(initialCheck: Boolean) {
        if (!inputSessionActive || !inputViewActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode)) return
        if (!initialCheck && !clipboardRegistered) return
        val clipResult = runCatching { clipboardManager?.primaryClip }
        val clip = clipResult.getOrNull() ?: return
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val text = item.text ?: item.htmlText?.let { runCatching { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }.getOrNull() }
            if (!text.isNullOrEmpty()) {
                val signature = contentSignature(text)
                if (initialCheck && CapturePreferences.lastClipboardSignature(capturePreferences) == signature) continue
                queueAutomaticCapture(text, "copy", signature)
            }
        }
    }

    private fun queueAutomaticCapture(text: CharSequence, source: String, clipboardSignature: String? = null) {
        if (!inputSessionActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode) || text.isEmpty()) return
        val mode = captureMode
        io.execute {
            val fingerprint = contentFingerprint(text)
            val nowElapsed = SystemClock.elapsedRealtime()
            if (fingerprint == lastCaptureFingerprint && nowElapsed - lastCaptureAtElapsed < 2_000L) {
                clipboardSignature?.let { CapturePreferences.markClipboardSignature(capturePreferences, it) }
                return@execute
            }
            lastCaptureFingerprint = fingerprint
            lastCaptureAtElapsed = nowElapsed
            val startedAt = System.currentTimeMillis()
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(startedAt))
            var chunkIndex = 0
            val result = runCatching {
                ClipCaptureWriter.saveToCards(store, text, if (source == "selection") "Select" else "Copy", stamp, startedAt) { saved ->
                    chunkIndex++
                    CapturePreferences.markCaptured(capturePreferences, saved.id, mode, startedAt)
                }
                clipboardSignature?.let { CapturePreferences.markClipboardSignature(capturePreferences, it) }
            }
            main.post {
                if (result.isSuccess && chunkIndex > 0) {
                    CaptureExpiryReceiver.schedule(this@ClipMaxImeService)
                    setStatus("Captured $chunkIndex ${if (chunkIndex == 1) "card" else "cards"} • ${CapturePreferences.modeLabel(mode)}")
                    reloadItems()
                    scheduleExpiryCleanup()
                }
            }
        }
    }

    private fun contentFingerprint(text: CharSequence): Int {
        var hash = 1
        for (index in 0 until text.length) hash = 31 * hash + text[index].code
        return 31 * hash + text.length
    }

    private fun contentSignature(text: CharSequence): String {
        var hash = -0x340d631b7bdddcdbL
        for (index in 0 until text.length) {
            hash = hash xor text[index].code.toLong()
            hash *= 0x100000001b3L
        }
        return "${text.length}:$hash"
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
        CLIPBOARD, PIN, PIN_FILLED, KEYBOARD, SEARCH, INSERT, EDIT, DELETE, CHEVRON_DOWN, CHEVRON_UP, CLOSE, LOCK
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
