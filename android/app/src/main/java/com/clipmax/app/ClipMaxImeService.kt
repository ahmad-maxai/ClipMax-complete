package com.clipmax.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * ClipMax Redesigned Native IME Service.
 * Provides a high-fidelity, responsive native keyboard experience
 * matching the approved dark charcoal (#111916) and mint (#2DD4BF) visual identity.
 * Features 50k-character chunked insertion, live search keypad,
 * pin filtering, and direct app editor integration.
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
    private var searchMode = false
    private var numericSearchKeys = false
    private var keyboardSearchQuery = ""
    private var keyboardSearchResults: List<ClipItem> = emptyList()
    private var keyboardSearchRequest = 0
    private var insertedCodePoints = 0
    private var lastReportedCodePoints = 0
    private var activeItemCodePoints = 0
    private var insertTask: Future<*>? = null
    private var items: List<ClipItem> = emptyList()

    private var rootView: LinearLayout? = null
    private var itemContainer: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var searchInputLabel: TextView? = null
    private var cancelButton: Button? = null
    private var pinnedButton: Button? = null
    private var searchButton: Button? = null
    private var pendingSelectionCapture: Runnable? = null
    private var expiryCleanupRunnable: Runnable? = null
    private var lastCaptureFingerprint: Int? = null
    private var lastCaptureAtElapsed = 0L

    // Approved Redesign Palette (Dark Charcoal & Vibrant Mint)
    private val bg = 0xFF111916.toInt()
    private val surface = 0xFF18241F.toInt()
    private val surfaceBorder = 0xFF273931.toInt()
    private val accent = 0xFF2DD4BF.toInt()
    private val accentInk = 0xFF0A1F18.toInt()
    private val accentSoft = 0xFF15332A.toInt()
    private val ink = 0xFFEAF1ED.toInt()
    private val muted = 0xFF8A9E96.toInt()
    private val danger = 0xFFF87171.toInt()
    private val keyBg = 0xFF202E28.toInt()
    private val keySpecialBg = 0xFF18231E.toInt()

    private val capturePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CapturePreferences.MODE_KEY) {
            captureMode = CapturePreferences.mode(capturePreferences)
            updateCaptureMonitoring()
            main.post { updateCaptureStatus(); renderItems() }
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

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(315)
            )
        }
        rootView = root
        renderKeyboardLayout()
        return root
    }

    private fun renderKeyboardLayout() {
        val root = rootView ?: return
        root.removeAllViews()
        itemContainer = null
        statusLabel = null
        searchInputLabel = null
        cancelButton = null
        pinnedButton = null
        searchButton = null

        // 1. Top Header Bar (ClipMax branding, Search, Pinned, Switch IME, Close)
        root.addView(buildHeaderBar())

        // 2. Status / Insertion Progress Banner
        root.addView(buildStatusBar())

        // 3. Main Area (Search keypad + results, or Full Card List)
        if (searchMode) {
            buildSearchLayout(root)
        } else {
            buildCardListLayout(root)
        }

        renderItems()
    }

    private fun buildHeaderBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setBackgroundColor(bg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))

        // ClipMax Mint Logo Mark
        val logoBox = TextView(this@ClipMaxImeService).apply {
            text = "C"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(accentInk)
            background = roundedDrawable(accent, dp(6f))
        }
        addView(logoBox, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) })

        // App Title
        val titleText = TextView(this@ClipMaxImeService).apply {
            text = "ClipMax"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        }
        addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Pinned Toggle Button
        pinnedButton = makePillButton(if (pinnedOnly) "Pinned ★" else "Pinned", pinnedOnly) {
            pinnedOnly = !pinnedOnly
            pinnedButton?.text = if (pinnedOnly) "Pinned ★" else "Pinned"
            pinnedButton?.background = roundedDrawable(if (pinnedOnly) accent else surface, dp(6f), if (pinnedOnly) 0 else surfaceBorder)
            pinnedButton?.setTextColor(if (pinnedOnly) accentInk else muted)
            renderItems()
        }
        addView(pinnedButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })

        // Search Toggle Button
        searchButton = makePillButton("Search 🔍", searchMode) {
            searchMode = !searchMode
            if (!searchMode) {
                keyboardSearchQuery = ""
                keyboardSearchResults = emptyList()
            }
            renderKeyboardLayout()
        }
        addView(searchButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { rightMargin = dp(6) })

        // Switch Keyboard (IME Picker) Button
        val switchImeBtn = makeIconButton("⌨") {
            showSystemImePicker()
        }
        addView(switchImeBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(4) })

        // Close / Hide Keyboard Button
        val hideBtn = makeIconButton("⌄") {
            requestHideSelf(0)
        }
        addView(hideBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
    }

    private fun buildStatusBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(2), dp(10), dp(2))
        setBackgroundColor(0xFF141F1A.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26))

        val label = TextView(this@ClipMaxImeService).apply {
            textSize = 11f
            setTextColor(muted)
            text = "Tap a card to insert • Auto-capture on"
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        statusLabel = label
        addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val cancel = Button(this@ClipMaxImeService).apply {
            text = "Cancel"
            textSize = 11f
            setTextColor(danger)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), 0, dp(6), 0)
            background = roundedDrawable(0x22F87171, dp(4f))
            visibility = View.GONE
            setOnClickListener { cancelInsertion(userInitiated = true) }
        }
        cancelButton = cancel
        addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)))
    }

    private fun buildCardListLayout(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        itemContainer = container
        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildSearchLayout(root: LinearLayout) {
        // Query Input Display
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(bg)
        }
        val queryBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(8), dp(4))
            background = roundedDrawable(surface, dp(8f), surfaceBorder)
        }
        val queryText = TextView(this).apply {
            text = if (keyboardSearchQuery.isEmpty()) "Type to filter clips…" else keyboardSearchQuery
            setTextColor(if (keyboardSearchQuery.isEmpty()) muted else ink)
            textSize = 13f
            typeface = if (keyboardSearchQuery.isEmpty()) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }
        searchInputLabel = queryText
        queryBox.addView(queryText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (keyboardSearchQuery.isNotEmpty()) {
            val clearBtn = TextView(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(6), 0, dp(4), 0)
                setOnClickListener {
                    keyboardSearchQuery = ""
                    runKeyboardSearch()
                }
            }
            queryBox.addView(clearBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        searchBar.addView(queryBox, LinearLayout.LayoutParams(0, dp(34), 1f))

        val closeSearch = makePillButton("Done", true) {
            searchMode = false
            keyboardSearchQuery = ""
            keyboardSearchResults = emptyList()
            renderKeyboardLayout()
        }
        searchBar.addView(closeSearch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { leftMargin = dp(8) })
        root.addView(searchBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Results Scroll Area (compact height)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        itemContainer = container
        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // On-Screen Keypad
        val keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1513.toInt())
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }
        if (!numericSearchKeys) {
            addKeypadRow(keypad, listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"))
            addKeypadRow(keypad, listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"))
            addKeypadRow(keypad, listOf("123", "Z", "X", "C", "V", "B", "N", "M", "⌫"))
        } else {
            addKeypadRow(keypad, listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
            addKeypadRow(keypad, listOf("-", "/", ":", ";", "(", ")", "\$", "&", "@", "\""))
            addKeypadRow(keypad, listOf("ABC", "#", ".", ",", "?", "!", "'", "⌫"))
        }
        addKeypadRow(keypad, listOf("Clear", "Space", "Done"))
        root.addView(keypad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addKeypadRow(parent: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(35))
        }
        keys.forEach { key ->
            val weight = when (key) {
                "Space" -> 3.5f
                "Done", "Clear" -> 1.4f
                "123", "ABC", "⌫" -> 1.3f
                else -> 1.0f
            }
            val btn = Button(this).apply {
                text = key
                isAllCaps = false
                textSize = if (key.length > 1) 11f else 14f
                typeface = Typeface.DEFAULT_BOLD
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                val isSpecial = key in listOf("123", "ABC", "Done", "Clear", "⌫")
                setTextColor(if (key == "Done") accentInk else ink)
                background = if (key == "Done") roundedRipple(accent, dp(6f)) else roundedRipple(if (isSpecial) keySpecialBg else keyBg, dp(6f))
                setOnClickListener { pressKey(key) }
            }
            row.addView(btn, LinearLayout.LayoutParams(0, dp(31), weight).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
                bottomMargin = dp(3)
            })
        }
        parent.addView(row)
    }

    private fun pressKey(key: String) {
        when (key) {
            "123" -> { numericSearchKeys = true; renderKeyboardLayout(); return }
            "ABC" -> { numericSearchKeys = false; renderKeyboardLayout(); return }
            "⌫" -> if (keyboardSearchQuery.isNotEmpty()) keyboardSearchQuery = keyboardSearchQuery.dropLast(1)
            "Clear" -> keyboardSearchQuery = ""
            "Space" -> keyboardSearchQuery += " "
            "Done" -> {
                searchMode = false
                keyboardSearchQuery = ""
                keyboardSearchResults = emptyList()
                renderKeyboardLayout()
                return
            }
            else -> keyboardSearchQuery += key.lowercase(Locale.ROOT)
        }
        searchInputLabel?.text = if (keyboardSearchQuery.isEmpty()) "Type to filter clips…" else keyboardSearchQuery
        searchInputLabel?.setTextColor(if (keyboardSearchQuery.isEmpty()) muted else ink)
        runKeyboardSearch()
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
                renderItems()
                setStatus(if (query.isEmpty()) "Showing all ${found.size} clips" else "${found.size} clips matching \"$query\"")
            }
        }
    }

    fun showSystemImePicker() {
        main.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    fun reloadItems() {
        val request = ++listRequest
        io.execute {
            CapturePreferences.cleanupExpired(store, capturePreferences)
            val found = runCatching { store.listItems() }.getOrElse { emptyList() }
            main.post {
                if (request != listRequest || rootView == null) return@post
                items = found.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.modifiedAt })
                renderItems()
                if (searchMode && keyboardSearchQuery.isNotEmpty()) runKeyboardSearch()
            }
        }
    }

    private fun renderItems() {
        val container = itemContainer ?: return
        container.removeAllViews()

        if (isPasswordTarget) {
            container.addView(TextView(this).apply {
                text = "🔒 Clipboard capture and clips are hidden in password fields for your privacy."
                textSize = 13f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(30), dp(20), dp(30))
            })
            return
        }

        val visible = when {
            searchMode && keyboardSearchQuery.isNotEmpty() -> keyboardSearchResults
            pinnedOnly -> items.filter { it.pinned }
            else -> items
        }

        if (visible.isEmpty()) {
            val emptyMsg = when {
                searchMode && keyboardSearchQuery.isNotEmpty() -> "No clips matching \"$keyboardSearchQuery\""
                pinnedOnly -> "No pinned clips yet. Pin important clips for fast access."
                else -> "No saved clips yet. Copy text anywhere on Android to capture cards here."
            }
            container.addView(TextView(this).apply {
                text = emptyMsg
                textSize = 13f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(24), dp(20), dp(24))
            })
            return
        }

        visible.forEach { item ->
            container.addView(buildCardRow(item))
        }
    }

    /**
     * Builds a modern redesigned clip card matching the dark charcoal/mint styling.
     */
    private fun buildCardRow(item: ClipItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedDrawable(surface, dp(10f), surfaceBorder)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        layoutParams = params

        // Top Row: Title + Pin Star + Chars Badge
        val topRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this@ClipMaxImeService).apply {
            text = (if (item.pinned) "★ " else "") + item.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (item.pinned) accent else ink)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        topRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val badge = TextView(this@ClipMaxImeService).apply {
            text = "${item.characterCount.grouped()} chars"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            background = roundedDrawable(accentSoft, dp(4f))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        topRow.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(topRow)

        // Middle Row: Text preview snippet
        val snippetText = getSnippet(item).ifEmpty { "Card content ready to insert" }
        val snippet = TextView(this@ClipMaxImeService).apply {
            text = snippetText
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFA7BDB6.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(8))
        }
        addView(snippet)

        // Bottom Row: Action buttons (Insert, Pin, Edit, Delete)
        val actionRow = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Primary INSERT Button
        val insertBtn = Button(this@ClipMaxImeService).apply {
            text = "Insert ↵"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentInk)
            background = roundedRipple(accent, dp(6f))
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { insertItem(item) }
        }
        actionRow.addView(insertBtn, LinearLayout.LayoutParams(0, dp(34), 1.6f).apply { rightMargin = dp(6) })

        // Pin Toggle Button
        val pinBtn = Button(this@ClipMaxImeService).apply {
            text = if (item.pinned) "Unpin" else "Pin"
            textSize = 12f
            setTextColor(if (item.pinned) accent else ink)
            background = roundedRipple(surfaceBorder, dp(6f))
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { togglePin(item) }
        }
        actionRow.addView(pinBtn, LinearLayout.LayoutParams(0, dp(34), 1.0f).apply { rightMargin = dp(6) })

        // Edit Button (opens in full ClipMax app)
        val editBtn = Button(this@ClipMaxImeService).apply {
            text = "Edit"
            textSize = 12f
            setTextColor(ink)
            background = roundedRipple(surfaceBorder, dp(6f))
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { editCard(item) }
        }
        actionRow.addView(editBtn, LinearLayout.LayoutParams(0, dp(34), 1.0f).apply { rightMargin = dp(6) })

        // Delete Button
        val delBtn = Button(this@ClipMaxImeService).apply {
            text = "Delete"
            textSize = 12f
            setTextColor(danger)
            background = roundedRipple(0x22F87171, dp(6f))
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { deleteCard(item) }
        }
        actionRow.addView(delBtn, LinearLayout.LayoutParams(0, dp(34), 1.0f))

        addView(actionRow)
    }

    private fun getSnippet(item: ClipItem, maxChars: Int = 120): String {
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

    private fun togglePin(item: ClipItem) {
        io.execute {
            val result = runCatching { store.setPinned(item.id, !item.pinned) }
            main.post {
                if (result.isSuccess) {
                    setStatus(if (item.pinned) "Unpinned ${item.title}" else "Pinned ${item.title}")
                    reloadItems()
                }
            }
        }
    }

    private fun editCard(item: ClipItem) {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_EDIT_ITEM_ID, item.id)
            }
            startActivity(intent)
        }
    }

    private fun deleteCard(item: ClipItem) {
        io.execute {
            val result = runCatching {
                store.delete(item.id)
                CapturePreferences.removeExpiry(capturePreferences, item.id)
            }
            main.post {
                if (result.isSuccess) {
                    setStatus("Deleted ${item.title}")
                    reloadItems()
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
            setStatus("🔒 Sensitive field: Clipboard capture is paused.")
        } else {
            updateCaptureStatus()
        }
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) {
            main.postDelayed({ captureCurrentSelection() }, 250)
        }
        renderItems()
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

    private fun setStatus(value: String) {
        statusLabel?.text = value
        cancelButton?.visibility = if (isInserting) View.VISIBLE else View.GONE
    }

    private fun makePillButton(text: String, active: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (active) accentInk else ink)
        background = if (active) roundedRipple(accent, dp(6f)) else roundedRipple(surface, dp(6f), surfaceBorder)
        setOnClickListener { onClick() }
    }

    private fun makeIconButton(icon: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = icon
        textSize = 14f
        setTextColor(ink)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        background = roundedRipple(surface, dp(6f), surfaceBorder)
        setOnClickListener { onClick() }
    }

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
            ColorStateList.valueOf(0x33FFFFFF),
            content,
            mask
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun Int.grouped(): String = String.format(Locale.ROOT, "%,d", this)
}
