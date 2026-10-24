package com.clipmax.app

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import android.widget.Button
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript to Kotlin bridge specifically for the IME keyboard view.
 */
class ImeBridge(
    private val service: ClipMaxImeService,
    private val store: ClipStore
) {
    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun insertClip(idOrText: String): Boolean {
        service.insertFromBridge(idOrText)
        return true
    }

    @JavascriptInterface
    fun deleteItem(id: String): Boolean {
        return try {
            store.delete(id)
            service.reloadItems()
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun setPinned(id: String, pinned: Boolean): Boolean {
        return try {
            store.setPinned(id, pinned)
            service.reloadItems()
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun switchKeyboard() {
        service.showSystemImePicker()
    }

    @JavascriptInterface
    fun hideKeyboard() {
        service.requestHideSelf(0)
    }

    @JavascriptInterface
    fun openClipMaxApp(itemId: String?) {
        try {
            val intent = Intent(service, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!itemId.isNullOrEmpty()) {
                    putExtra(MainActivity.EXTRA_EDIT_ITEM_ID, itemId)
                }
            }
            service.startActivity(intent)
        } catch (e: Throwable) {
            // ignore
        }
    }
}

/**
 * A privacy-focused keyboard displaying the redesigned ClipMax UI.
 * Captures clipboard text locally and inserts only on user command.
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
    private var searchShowingResults = false
    private var numericSearchKeys = false
    private var keyboardSearchQuery = ""
    private var keyboardSearchResults: List<ClipItem> = emptyList()
    private var keyboardSearchRequest = 0
    private var actionMenuItemId: String? = null
    private var insertedCodePoints = 0
    private var lastReportedCodePoints = 0
    private var activeItemCodePoints = 0
    private var insertTask: Future<*>? = null
    private var items: List<ClipItem> = emptyList()

    private var itemContainer: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var cancelButton: Button? = null
    private var pinnedButton: Button? = null
    private var rootView: LinearLayout? = null
    private var pendingSelectionCapture: Runnable? = null
    private var expiryCleanupRunnable: Runnable? = null
    private var lastCaptureFingerprint: Int? = null
    private var lastCaptureAtElapsed = 0L

    private var imeWebView: WebView? = null

    private val capturePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CapturePreferences.MODE_KEY) {
            captureMode = CapturePreferences.mode(capturePreferences)
            updateCaptureMonitoring()
            main.post { updateCaptureStatus(); renderItems() }
        }
    }

    private val darkMode get() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    // Use modern redesign palette
    private val bg = 0xFF111916.toInt()
    private val card = 0xFF1A2621.toInt()
    private val ink = 0xFFEAF1ED.toInt()
    private val muted = 0xFF8A9E96.toInt()
    private val accent = 0xFF2DD4BF.toInt()

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
        initImeWebView()
    }

    private fun initImeWebView() {
        try {
            val wv = WebView(this).apply {
                setBackgroundColor(0xFF111916.toInt())
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(310)
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
            }
            wv.addJavascriptInterface(ImeBridge(this, store), "ClipMaxIme")
            wv.addJavascriptInterface(AndroidBridge(this, store), "ClipMaxNative")

            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/web/", WebViewAssetLoader.AssetsPathHandler(this, "web"))
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this, "web"))
                .build()

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }
            wv.loadUrl("https://appassets.androidplatform.net/assets/web/index.html?mode=ime")
            imeWebView = wv
        } catch (e: Throwable) {
            imeWebView = null
        }
    }

    fun showSystemImePicker() {
        main.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    fun insertFromBridge(idOrText: String) {
        main.post {
            val item = store.getItem(idOrText)
            if (item != null) {
                beginInsertion(item.title, "Inserting ${item.title}…") { store.openSource(item) }
            } else {
                beginInsertion("Clip", "Inserting…") {
                    InsertionSource(java.io.StringReader(idOrText), idOrText.length)
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        if (imeWebView == null) {
            initImeWebView()
        }
        val wv = imeWebView
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF111916.toInt())
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(310)
                )
            }
            container.addView(wv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            return container
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310))
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
        cancelButton = null
        pinnedButton = null
        if (searchMode) buildSearchLayout(root) else buildCardLayout(root)
    }

    private fun buildCardLayout(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(label("ClipMax · clipboard", 17f, true), LinearLayout.LayoutParams(0, dp(42), 1f))
        val pinned = actionButton("Pinned") { pinnedOnly = !pinnedOnly; renderItems() }
        pinnedButton = pinned
        toolbar.addView(actionButton("Search") { beginKeyboardSearch() }, LinearLayout.LayoutParams(-2, dp(40)))
        toolbar.addView(pinned, LinearLayout.LayoutParams(-2, dp(40)))
        root.addView(toolbar)

        val help = label("Copies are saved as serially numbered 50,000-character cards. Search cards here. Tap More or long-press a card for Edit, Pin, or Delete.", 11f)
        help.setPadding(dp(3), dp(2), dp(3), dp(5))
        root.addView(help, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        itemContainer = container
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLabel = label("Choose a saved item.", 12f, color = muted)
        bottom.addView(statusLabel, LinearLayout.LayoutParams(0, dp(42), 1f))
        cancelButton = actionButton("Cancel") { cancelInsertion(userInitiated = true) }.apply { visibility = View.GONE }
        bottom.addView(cancelButton, LinearLayout.LayoutParams(-2, dp(40)))
        root.addView(bottom)
        updateCaptureStatus()
        renderItems()
    }

    private fun beginKeyboardSearch() {
        if (isPasswordTarget) return
        searchMode = true
        searchShowingResults = false
        numericSearchKeys = false
        keyboardSearchQuery = ""
        keyboardSearchResults = emptyList()
        renderKeyboardLayout()
    }

    private fun buildSearchLayout(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(label(if (searchShowingResults) "Search results" else "Search cards", 16f, true), LinearLayout.LayoutParams(0, dp(40), 1f))
        toolbar.addView(actionButton(if (searchShowingResults) "Edit" else "Close") {
            if (searchShowingResults) {
                searchShowingResults = false
                renderKeyboardLayout()
            } else {
                searchMode = false
                keyboardSearchRequest++
                renderKeyboardLayout()
            }
        }, LinearLayout.LayoutParams(-2, dp(38)))
        root.addView(toolbar)

        val queryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queryRow.addView(label(if (keyboardSearchQuery.isBlank()) "Type a word or phrase" else keyboardSearchQuery, 14f, color = if (keyboardSearchQuery.isBlank()) muted else ink), LinearLayout.LayoutParams(0, dp(34), 1f))
        queryRow.addView(actionButton("Clear") { keyboardSearchQuery = ""; searchShowingResults = false; renderKeyboardLayout() }, LinearLayout.LayoutParams(-2, dp(32)))
        root.addView(queryRow)

        if (searchShowingResults) {
            val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            itemContainer = container
            scroll.addView(container)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            statusLabel = label("${keyboardSearchResults.size} matching cards", 12f, color = muted)
            bottom.addView(statusLabel, LinearLayout.LayoutParams(0, dp(34), 1f))
            bottom.addView(actionButton("Done") { searchMode = false; keyboardSearchRequest++; renderKeyboardLayout() }, LinearLayout.LayoutParams(-2, dp(34)))
            root.addView(bottom)
            renderItems()
            return
        }

        val keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        if (!numericSearchKeys) {
            addSearchKeyRow(keypad, listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
            addSearchKeyRow(keypad, listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))
            addSearchKeyRow(keypad, listOf("123", "z", "x", "c", "v", "b", "n", "m", "⌫"))
        } else {
            addSearchKeyRow(keypad, listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
            addSearchKeyRow(keypad, listOf("-", "/", ":", ";", "(", ")", "\$", "&", "@", "\""))
            addSearchKeyRow(keypad, listOf("ABC", "#", ".", ",", "?", "!", "'", "⌫"))
        }
        addSearchKeyRow(keypad, listOf("Clear", "Space", "Search"))
        root.addView(keypad, LinearLayout.LayoutParams(-1, 0, 1f))
        statusLabel = label("Searches titles and card text", 11f, color = muted)
        root.addView(statusLabel, LinearLayout.LayoutParams(-1, dp(26)))
    }

    private fun addSearchKeyRow(parent: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        keys.forEach { key ->
            val weight = when (key) { "Space" -> 3f; "Search" -> 1.5f; "Clear", "123", "ABC", "⌫" -> 1.2f; else -> 1f }
            row.addView(searchKeyButton(key) { pressSearchKey(key) }, LinearLayout.LayoutParams(0, dp(31), weight).apply {
                leftMargin = dp(1); rightMargin = dp(1); bottomMargin = dp(2)
            })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(33)))
    }

    private fun searchKeyButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = if (title.length > 1) 11f else 14f
        minHeight = 0
        minimumHeight = dp(29)
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setTextColor(if (darkMode) 0xFF13231D.toInt() else 0xFFFFFFFF.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        setOnClickListener { action() }
    }

    private fun pressSearchKey(key: String) {
        when (key) {
            "123" -> numericSearchKeys = true
            "ABC" -> numericSearchKeys = false
            "⌫" -> if (keyboardSearchQuery.isNotEmpty()) keyboardSearchQuery = keyboardSearchQuery.dropLast(1)
            "Clear" -> keyboardSearchQuery = ""
            "Space" -> keyboardSearchQuery += " "
            "Search" -> { runKeyboardSearch(); return }
            else -> keyboardSearchQuery += key
        }
        renderKeyboardLayout()
    }

    private fun runKeyboardSearch() {
        val query = keyboardSearchQuery.trim()
        if (query.isEmpty()) { setStatus("Enter a word or phrase to search."); return }
        searchShowingResults = true
        keyboardSearchResults = emptyList()
        renderKeyboardLayout()
        setStatus("Searching cards…")
        val request = ++keyboardSearchRequest
        io.execute {
            val found = runCatching { store.search(query) }.getOrElse { emptyList() }
                .sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.modifiedAt }.thenBy { it.title })
            main.post {
                if (!searchMode || !searchShowingResults || request != keyboardSearchRequest) return@post
                keyboardSearchResults = found
                renderKeyboardLayout()
                setStatus(if (found.isEmpty()) "No matching cards." else "${found.size} matching cards.")
            }
        }
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
        if (isPasswordTarget) setStatus("Clipboard capture and saved items are disabled in password fields.")
        else updateCaptureStatus()
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) {
            main.postDelayed({ captureCurrentSelection() }, 250)
        }
        renderItems()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!inputSessionActive || !inputViewActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode) || newSelStart < 0 || newSelEnd < 0 || newSelStart == newSelEnd) return
        val selected = runCatching { currentInputConnection?.getSelectedText(0) }.getOrNull() ?: return
        if (selected.isEmpty()) return
        pendingSelectionCapture?.let(main::removeCallbacks)
        val snapshot = selected
        pendingSelectionCapture = Runnable { queueAutomaticCapture(snapshot, "selection") }
        main.postDelayed(pendingSelectionCapture!!, 350)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
        updateCaptureMonitoring()
        if (!isPasswordTarget && CapturePreferences.isEnabled(captureMode)) capturePrimaryClip(initialCheck = true)
        reloadItems()
        scheduleExpiryCleanup()
        imeWebView?.evaluateJavascript("""
            window.dispatchEvent(new CustomEvent('clipmax:password', { detail: { isPassword: $isPasswordTarget } }));
            window.dispatchEvent(new CustomEvent('clipmax:refresh'));
        """.trimIndent(), null)
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
        imeWebView?.destroy()
        imeWebView = null
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

    fun reloadItems() {
        val request = ++listRequest
        io.execute {
            CapturePreferences.cleanupExpired(store, capturePreferences)
            val found = runCatching { store.listItems() }.getOrElse { emptyList() }
            main.post {
                imeWebView?.evaluateJavascript("window.dispatchEvent(new CustomEvent('clipmax:refresh'))", null)
                if (request != listRequest || rootView == null) return@post
                items = found.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.modifiedAt }.thenBy { it.title })
                renderItems()
                if (searchMode && searchShowingResults) runKeyboardSearch()
            }
        }
    }

    private fun renderItems() {
        val container = itemContainer ?: return
        container.removeAllViews()
        pinnedButton?.text = if (pinnedOnly) "All items" else "Pinned"
        if (isPasswordTarget) {
            container.addView(label("ClipMax does not capture or show saved text in password fields.", 14f, true).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) })
            cancelButton?.visibility = if (isInserting) View.VISIBLE else View.GONE
            return
        }
        val visible = when {
            searchMode && searchShowingResults -> keyboardSearchResults
            pinnedOnly -> items.filter { it.pinned }
            else -> items
        }
        if (visible.isEmpty()) {
            val emptyMessage = when {
                searchMode && searchShowingResults -> "No matching cards. Edit the search and try again."
                pinnedOnly -> "No pinned items yet. Pin items in the ClipMax library."
                else -> "No captured cards yet. Copy text while ClipMax is active, or switch to ClipMax after copying. New cards appear here automatically."
            }
            container.addView(label(emptyMessage, 14f, color = muted).apply {
                setPadding(dp(12), dp(16), dp(12), dp(16))
            })
        } else visible.forEach { item ->
            container.addView(itemRow(item))
            if (actionMenuItemId == item.id) container.addView(cardActionRow(item))
        }
        cancelButton?.visibility = if (isInserting) View.VISIBLE else View.GONE
    }

    private fun itemRow(item: ClipItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(5), dp(6), dp(5))
        setBackgroundColor(card)
        contentDescription = "${item.title}. Tap More or long-press for Edit, Pin, and Delete actions."
        val details = LinearLayout(this@ClipMaxImeService).apply {
            orientation = LinearLayout.VERTICAL
            addView(label((if (item.pinned) "★  " else "") + item.title, 14f, true, ink), LinearLayout.LayoutParams(-1, -2))
            addView(label("${item.characterCount.grouped()} chars · ${humanSize(item.sizeBytes)}", 11f, color = muted), LinearLayout.LayoutParams(-1, -2))
            setOnLongClickListener { toggleCardActions(item); true }
        }
        addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        addView(actionButton("Insert") { insertItem(item) }, LinearLayout.LayoutParams(-2, dp(42)))
        addView(actionButton("More") { toggleCardActions(item) }, LinearLayout.LayoutParams(-2, dp(42)))
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) }
        layoutParams = params
        alpha = if (isInserting) 0.68f else 1f
        setOnLongClickListener {
            toggleCardActions(item)
            true
        }
    }

    private fun cardActionRow(item: ClipItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), dp(6))
        addView(actionButton("Edit") {
            actionMenuItemId = null
            renderItems()
            editCard(item)
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        addView(actionButton(if (item.pinned) "Unpin" else "Pin") {
            actionMenuItemId = null
            io.execute {
                val result = runCatching { store.setPinned(item.id, !item.pinned) }
                main.post {
                    if (result.isSuccess) setStatus(if (item.pinned) "Card unpinned." else "Card pinned.")
                    else setStatus("Could not update pin: ${result.exceptionOrNull()?.message ?: "storage error"}")
                    reloadItems()
                }
            }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        addView(actionButton("Delete") {
            actionMenuItemId = null
            deleteCard(item)
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
    }

    private fun toggleCardActions(item: ClipItem) {
        actionMenuItemId = if (actionMenuItemId == item.id) null else item.id
        setStatus(if (actionMenuItemId == null) "Card actions closed." else "Choose Edit, Pin, or Delete for ${item.title}.")
        renderItems()
    }

    private data class InsertionSource(val reader: Reader, val characterCount: Int)

    private fun insertItem(item: ClipItem) {
        beginInsertion("${item.title}", "Inserting ${item.title}…") {
            InsertionSource(store.openTextReader(item.id), item.characterCount)
        }
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
        if (!inputSessionActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode)) {
            pendingSelectionCapture?.let(main::removeCallbacks)
            pendingSelectionCapture = null
        }
    }

    private fun updateCaptureStatus() {
        setStatus(if (CapturePreferences.isEnabled(captureMode)) "Auto-capture on · ${CapturePreferences.modeLabel(captureMode)}" else "Automatic capture is off. Choose a saved card to insert.")
    }

    private fun capturePrimaryClip(initialCheck: Boolean) {
        if (!inputSessionActive || !inputViewActive || isPasswordTarget || !CapturePreferences.isEnabled(captureMode)) return
        if (!initialCheck && !clipboardRegistered) return
        val clipResult = runCatching { clipboardManager?.primaryClip }
        if (clipResult.isFailure) {
            setStatus("Android did not provide clipboard text. Use the source app's Copy action, then reopen ClipMax.")
            return
        }
        val clip = clipResult.getOrNull()
        if (clip == null) {
            if (initialCheck) setStatus("No clipboard text found. In read-only content, use the app's Copy action.")
            return
        }
        var foundText = false
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val text = item.text ?: item.htmlText?.let { runCatching { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }.getOrNull() }
            if (!text.isNullOrEmpty()) {
                foundText = true
                val signature = contentSignature(text)
                if (initialCheck && CapturePreferences.lastClipboardSignature(capturePreferences) == signature) continue
                queueAutomaticCapture(text, "copy", signature)
            }
        }
        if (initialCheck && !foundText) setStatus("Clipboard has no text. In read-only content, use the app's Copy action.")
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
                    setStatus("Captured $chunkIndex ${if (chunkIndex == 1) "card" else "cards"} · ${CapturePreferences.modeLabel(mode)}")
                    reloadItems()
                    scheduleExpiryCleanup()
                } else if (result.isFailure) {
                    setStatus("Auto-capture failed: ${result.exceptionOrNull()?.message ?: "storage unavailable"}")
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

    private fun deleteCard(item: ClipItem) {
        io.execute {
            val result = runCatching {
                store.delete(item.id)
                CapturePreferences.removeExpiry(capturePreferences, item.id)
            }
            main.post {
                if (result.isSuccess) { CaptureExpiryReceiver.schedule(this@ClipMaxImeService); setStatus("Card deleted."); reloadItems() }
                else setStatus("Could not delete card: ${result.exceptionOrNull()?.message ?: "storage error"}")
            }
        }
    }

    private fun editCard(item: ClipItem) {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_EDIT_ITEM_ID, item.id))
        }.onFailure { setStatus("Could not open editor: ${it.message ?: "activity unavailable"}") }
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

    private fun beginInsertion(title: String, initialStatus: String, openSource: () -> InsertionSource) {
        if (isPasswordTarget) { setStatus("Selection capture and saved items are unavailable in password fields."); return }
        val connection = currentInputConnection
        if (connection == null) { setStatus("No active text field. Tap a text box in the destination app first."); return }
        if (isInserting) cancelInsertion(userInitiated = false)
        generation++
        val token = generation
        isInserting = true
        insertedCodePoints = 0
        lastReportedCodePoints = 0
        activeItemCodePoints = 0
        setStatus(initialStatus)
        renderItems()
        insertTask = inserter.submit {
            try {
                val source = openSource()
                activeItemCodePoints = source.characterCount
                main.post {
                    if (generation == token) setStatus("Inserting $title in 50,000-character chunks…")
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
                        setStatus(if (complete) "Inserted ${insertedCodePoints.grouped()} of ${activeItemCodePoints.grouped()} characters." else "Insertion stopped; the inserted part remains in the field.")
                        renderItems()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Exception) {
                main.post {
                    if (generation == token) {
                        isInserting = false
                        setStatus("Insertion stopped: ${failure.message ?: "destination did not accept text"}. The inserted part remains.")
                        renderItems()
                    }
                }
            }
        }
    }

    /** Dispatch each chunk on the IME/main thread and wait before reading the next disk chunk. */
    private fun deliverChunk(connection: android.view.inputmethod.InputConnection, token: Int, chunk: String): Boolean {
        val latch = CountDownLatch(1)
        var accepted = false
        var failure: Throwable? = null
        val points = chunk.codePointCount(0, chunk.length)
        main.post {
            try {
                if (generation == token && isInserting) {
                    accepted = connection.commitText(chunk, 1)
                    if (!accepted) failure = IOException("The destination field rejected a text chunk")
                    else {
                        insertedCodePoints += points
                        if (insertedCodePoints - lastReportedCodePoints >= 16_384 || insertedCodePoints >= activeItemCodePoints) {
                            lastReportedCodePoints = insertedCodePoints
                            setStatus("Inserted ${insertedCodePoints.grouped()} of ${activeItemCodePoints.grouped()} characters…")
                        }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(60, TimeUnit.SECONDS)) throw IOException("Timed out waiting for the destination app")
        if (generation != token) return false
        failure?.let { throw IOException(it.message ?: "The destination rejected text", it) }
        return accepted
    }

    private fun cancelInsertion(userInitiated: Boolean) {
        if (!isInserting) return
        generation++
        insertTask?.cancel(true)
        insertTask = null
        isInserting = false
        setStatus(if (userInitiated) "Cancelled after ${insertedCodePoints.grouped()} characters; inserted text remains." else "Insertion stopped because the text field changed.")
        renderItems()
    }

    private fun label(value: String, size: Float, bold: Boolean = false, color: Int = ink): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 2
    }

    private fun actionButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        minHeight = dp(40)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(if (darkMode) 0xFF13231D.toInt() else 0xFFFFFFFF.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        setOnClickListener { action() }
    }

    private fun setStatus(value: String) {
        statusLabel?.text = value
        cancelButton?.visibility = if (isInserting) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0))
    }

    private fun Int.grouped(): String = String.format(Locale.ROOT, "%,d", this)
}
