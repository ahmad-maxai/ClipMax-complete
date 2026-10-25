package com.clipmax.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private var searchActive = false
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
    private var rootView: LinearLayout? = null
    private var chipsContainer: LinearLayout? = null
    private var keyboardRowsContainer: LinearLayout? = null
    private var statusBannerView: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var statusCancelBtn: Button? = null
    private var searchInputText: TextView? = null
    private var shiftButton: View? = null
    private val letterKeyViews = mutableListOf<Pair<TextView, String>>()

    private var pendingSelectionCapture: Runnable? = null
    private var expiryCleanupRunnable: Runnable? = null
    private var lastCaptureFingerprint: Int? = null
    private var lastCaptureAtElapsed = 0L

    // Theme Color Tokens (Evaluated dynamically)
    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            main.post { capturePrimaryClip(initialCheck = false) }
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        win.navigationBarColor = colorBg
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootView = root

        // Navigation Bar Insets Handling:
        // When running in apps like ChatGPT that render edge-to-edge, the IME window
        // overlaps the Android system gesture navigation bar unless bottom padding is applied.
        // We listen to WindowInsets and dynamically add the exact navigationBar bottom inset.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val navBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, navBottom)
            insets
        }

        renderFullImeView()
        return root
    }

    private fun renderFullImeView() {
        val root = rootView ?: return
        root.removeAllViews()
        letterKeyViews.clear()
        shiftButton = null

        // 1. ClipMax Compact Toolbar (Clipboard, Pinned, Search, Switch IME, Hide)
        root.addView(buildToolbar())

        // 2. Compact Saved-Clips Horizontal Strip (~32dp)
        root.addView(buildSavedClipsStrip())

        // 3. Status / In-Flight Banner (Hidden unless inserting or in password field)
        root.addView(buildStatusBanner())

        // 4. Approved QWERTY / Numeric Keyboard Container
        val kbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(2), dp(2), dp(2), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        keyboardRowsContainer = kbContainer
        renderKeyboardRows()
        root.addView(kbContainer)

        renderClipChips()
    }

    // =========================================================================
    // 1. TOOLBAR
    // =========================================================================
    private fun buildToolbar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setBackgroundColor(colorToolbarBg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))

        // Brand Label
        val brand = TextView(this@ClipMaxImeService).apply {
            text = "📋 ClipMax"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
        }
        addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })

        // Pinned Toggle Button
        val pinBtn = Button(this@ClipMaxImeService).apply {
            text = if (pinnedOnly) "★ Pinned" else "Pinned"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setTextColor(if (pinnedOnly) colorAccentText else colorModText)
            background = roundedRipple(if (pinnedOnly) colorAccent else colorKeyBg, dp(4f), colorKeyBorder)
            setOnClickListener {
                pinnedOnly = !pinnedOnly
                renderFullImeView()
            }
        }
        addView(pinBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply { rightMargin = dp(6) })

        // Search Filter Pill Button
        val searchPill = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
            background = roundedRipple(colorKeyBg, dp(4f), colorKeyBorder)
            val st = TextView(this@ClipMaxImeService).apply {
                text = if (keyboardSearchQuery.isEmpty()) "Search clips…" else keyboardSearchQuery
                textSize = 11f
                setTextColor(if (keyboardSearchQuery.isEmpty()) colorHintText else colorKeyText)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            searchInputText = st
            addView(st, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (keyboardSearchQuery.isNotEmpty()) {
                val clearX = TextView(this@ClipMaxImeService).apply {
                    text = "✕"
                    textSize = 11f
                    setTextColor(colorHintText)
                    setPadding(dp(4), 0, dp(2), 0)
                    setOnClickListener {
                        keyboardSearchQuery = ""
                        searchInputText?.text = "Search clips…"
                        searchInputText?.setTextColor(colorHintText)
                        runKeyboardSearch()
                    }
                }
                addView(clearX, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            setOnClickListener {
                searchActive = !searchActive
                renderFullImeView()
            }
        }
        addView(searchPill, LinearLayout.LayoutParams(0, dp(26), 1f).apply { rightMargin = dp(6) })

        // Switch IME (InputMethodPicker) Button
        val switchIme = Button(this@ClipMaxImeService).apply {
            text = "⌨"
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            setTextColor(colorModText)
            background = roundedRipple(colorKeyBg, dp(4f), colorKeyBorder)
            setOnClickListener { showSystemImePicker() }
        }
        addView(switchIme, LinearLayout.LayoutParams(dp(28), dp(26)).apply { rightMargin = dp(4) })

        // Dismiss Keyboard Button
        val hideBtn = Button(this@ClipMaxImeService).apply {
            text = "⌄"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            setTextColor(colorModText)
            background = roundedRipple(colorKeyBg, dp(4f), colorKeyBorder)
            setOnClickListener { requestHideSelf(0) }
        }
        addView(hideBtn, LinearLayout.LayoutParams(dp(28), dp(26)))
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
                text = "🔒 Password field: Saved clips withheld for privacy"
                textSize = 11f
                setTextColor(colorHintText)
                setPadding(dp(8), dp(2), dp(8), dp(2))
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
                textSize = 11f
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

        // Pinned star
        if (item.pinned) {
            val star = TextView(this@ClipMaxImeService).apply {
                text = "★ "
                textSize = 10f
                setTextColor(colorAccent)
            }
            addView(star)
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
    // KEY VIEW BUILDERS & ACTIONS
    // =========================================================================
    private fun createKeyRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45))
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

        setOnClickListener {
            handleLetterPress(char)
        }
    }

    private fun createStandardKey(char: String): View = FrameLayout(this).apply {
        background = roundedRipple(colorKeyBg, dp(6f), colorKeyBorder)

        val tv = TextView(this@ClipMaxImeService).apply {
            val isLetter = char.length == 1 && char[0].isLetter()
            text = if (isLetter && shiftState == 0) char.lowercase(Locale.ROOT) else char
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorKeyText)
            gravity = Gravity.CENTER
            if (isLetter) letterKeyViews.add(this to char)
        }
        addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setOnClickListener {
            if (char.length == 1 && char[0].isLetter()) {
                handleLetterPress(char)
            } else {
                handleGenericCharPress(char)
            }
        }
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

        setOnClickListener { onClick() }
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

        setOnClickListener {
            handleShiftPress()
        }
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

        setOnClickListener {
            handleSpacePress()
        }
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

        setOnClickListener {
            handleEnterPress()
        }
    }

    // =========================================================================
    // INPUT CONNECTION HANDLING
    // =========================================================================
    private fun handleLetterPress(char: String) {
        val letter = if (shiftState == 0) char.lowercase(Locale.ROOT) else char.uppercase(Locale.ROOT)
        if (searchActive) {
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
        if (searchActive) {
            keyboardSearchQuery += char
            updateSearchUI()
            runKeyboardSearch()
        } else {
            currentInputConnection?.commitText(char, 1)
        }
    }

    private fun handleSpacePress() {
        if (searchActive) {
            keyboardSearchQuery += " "
            updateSearchUI()
            runKeyboardSearch()
        } else {
            currentInputConnection?.commitText(" ", 1)
        }
    }

    private fun handleBackspace() {
        if (searchActive) {
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
        if (searchActive) {
            searchActive = false
            renderFullImeView()
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
                handleBackspace()
                main.postDelayed(this, 50)
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
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
                if (request != listRequest || rootView == null) return@post
                items = found.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.modifiedAt })
                renderClipChips()
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
            }.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.modifiedAt })
            main.post {
                if (request != keyboardSearchRequest || rootView == null) return@post
                keyboardSearchResults = found
                renderClipChips()
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
            setStatus("🔒 Password field: Saved clips withheld for privacy.")
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
}
