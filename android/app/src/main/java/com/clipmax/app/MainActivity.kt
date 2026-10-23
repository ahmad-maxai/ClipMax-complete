package com.clipmax.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        const val EXTRA_EDIT_ITEM_ID = "com.clipmax.app.extra.EDIT_ITEM_ID"
    }

    private enum class Screen { LIBRARY, DETAILS, EDITOR, SETTINGS }
    private enum class SortMode(val label: String) {
        MODIFIED("Recently modified"), NEWEST("Newest first"), OLDEST("Oldest first"), TITLE("Title A–Z")
    }
    private enum class FileAction { IMPORT_TEXT, EXPORT_TEXT, BACKUP, RESTORE }

    private lateinit var store: ClipStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var screen = Screen.LIBRARY
    private var selectedId: String? = null
    private var editorId: String? = null
    private var editorTitle: EditText? = null
    private var editorContent: EditText? = null
    private var editorCodePoints = 0
    private var countLabel: TextView? = null
    private var dirty = false
    private var saving = false
    private var sortMode = SortMode.MODIFIED
    private var searchQuery = ""
    private var pendingFileAction: FileAction? = null
    private var pendingItemId: String? = null
    private var draftRunnable: Runnable? = null
    private var searchSerial = 0
    private var libraryList: LinearLayout? = null
    private val density get() = resources.displayMetrics.density
    private val isDark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val backgroundColor get() = if (isDark) 0xFF111916.toInt() else 0xFFF4F7F5.toInt()
    private val surfaceColor get() = if (isDark) 0xFF1A2420.toInt() else 0xFFFFFFFF.toInt()
    private val textColor get() = if (isDark) 0xFFEAF1ED.toInt() else 0xFF17211D.toInt()
    private val mutedColor get() = if (isDark) 0xFFB3C2BA.toInt() else 0xFF59665F.toInt()
    private val accentColor get() = if (isDark) 0xFFA8D5C8.toInt() else 0xFF315C52.toInt()
    private val outlineColor get() = if (isDark) 0xFF33443B.toInt() else 0xFFDCE5DF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ClipStore(filesDir)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        showLibrary()
        openRequestedCardForEdit(intent)
        io.execute {
            val draft = store.loadDraft()
            if (draft != null) main.post {
                if (!isFinishing && screen == Screen.LIBRARY) {
                    AlertDialog.Builder(this)
                        .setTitle("Recover your draft?")
                        .setMessage("ClipMax found an automatically saved draft. Restore it?")
                        .setPositiveButton("Restore") { _, _ -> showEditor(draft.itemId, draft.title, draft.content, recovered = true) }
                        .setNegativeButton("Discard") { _, _ -> io.execute { store.clearDraft() } }
                        .show()
                }
            }
        }
    }

    override fun onPause() {
        if (screen == Screen.EDITOR && dirty && !saving) captureDraft()?.let { draft ->
            io.execute { runCatching { store.saveDraft(draft.first, draft.second, draft.third) } }
        }
        super.onPause()
    }

    override fun onDestroy() {
        draftRunnable?.let(main::removeCallbacks)
        io.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            Screen.LIBRARY -> super.onBackPressed()
            Screen.EDITOR -> if (dirty) confirmDiscard() else leaveEditor()
            Screen.DETAILS -> showLibrary()
            Screen.SETTINGS -> showLibrary()
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
    private fun text(value: String, size: Float = 16f, bold: Boolean = false, color: Int = textColor): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        setTextColor(if (primary) if (isDark) 0xFF13231D.toInt() else 0xFFFFFFFF.toInt() else accentColor)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) accentColor else surfaceColor)
        setOnClickListener { action() }
    }

    private fun editField(hintText: String, multiline: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(textColor)
        setHintTextColor(mutedColor)
        textSize = 16f
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(outlineColor, dp(12), outlineColor, dp(1))
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (multiline) {
            minLines = 12
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        } else {
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int = fill, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun column(padding: Int = 16): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(8), dp(padding), dp(12))
        setBackgroundColor(backgroundColor)
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun screenRoot(content: View) {
        content.setBackgroundColor(backgroundColor)
        content.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(view.paddingLeft, insets.systemWindowInsetTop, view.paddingRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(content)
        content.requestApplyInsets()
    }

    private fun header(title: String, actionText: String? = null, action: (() -> Unit)? = null, back: Boolean = false): LinearLayout =
        row().apply {
            setPadding(0, dp(4), 0, dp(8))
            if (back) addView(button("‹", action = { onBackPressed() }), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text(title, 23f, true), LinearLayout.LayoutParams(0, dp(52), 1f))
            if (actionText != null && action != null) addView(button(actionText, action = action))
        }

    private fun showLibrary() {
        screen = Screen.LIBRARY
        editorTitle = null
        editorContent = null
        val root = column(18)
        root.addView(header("ClipMax", "Settings", action = { showSettings() }))
        val searchRow = row()
        val search = editField("Search titles and text").apply {
            setSingleLine(true)
            setText(searchQuery)
            setSelectAllOnFocus(false)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
        searchRow.addView(button("Search", primary = true) {
            searchQuery = search.text.toString().trim()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
            refreshLibrary()
        })
        root.addView(searchRow)
        val summaryRow = row().apply {
            addView(text("Saved items", 14f, true, mutedColor), LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(button("${sortMode.label} ▾") { chooseSort() })
        }
        root.addView(summaryRow)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        libraryList = list
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = row()
        footer.addView(button("Import .txt") { startImportText() }, LinearLayout.LayoutParams(0, dp(54), 1f))
        footer.addView(button("New item", primary = true) { showEditor(null, "", "") }, LinearLayout.LayoutParams(0, dp(54), 1f))
        root.addView(footer)
        screenRoot(root)
        refreshLibrary(list)
    }

    private fun chooseSort() {
        val values = SortMode.values()
        AlertDialog.Builder(this).setTitle("Sort saved items")
            .setItems(values.map { it.label }.toTypedArray()) { _, which -> sortMode = values[which]; showLibrary() }
            .show()
    }

    private fun refreshLibrary(target: LinearLayout? = null) {
        val thisSearch = ++searchSerial
        val query = searchQuery
        io.execute {
            CapturePreferences.cleanupExpired(store, CapturePreferences.preferences(this@MainActivity))
            CaptureExpiryReceiver.schedule(this@MainActivity)
            val result = runCatching { if (query.isBlank()) store.listItems() else store.search(query) }.getOrElse { emptyList() }
            val used = runCatching { store.totalBytes() }.getOrDefault(0L)
            main.post {
                if (screen != Screen.LIBRARY || thisSearch != searchSerial) return@post
                val list = target ?: findLibraryList() ?: return@post
                list.removeAllViews()
                val storageLine = "${result.size} ${if (result.size == 1) "item" else "items"} · ${humanSize(used)} stored locally"
                val stat = text(storageLine, 13f, color = mutedColor).apply { setPadding(0, dp(0), 0, dp(8)) }
                list.addView(stat)
                val sorted = when (sortMode) {
                    SortMode.MODIFIED -> result.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.modifiedAt }.thenBy { it.title })
                    SortMode.NEWEST -> result.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.createdAt })
                    SortMode.OLDEST -> result.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenBy { it.createdAt })
                    SortMode.TITLE -> result.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenBy { it.title.lowercase(Locale.ROOT) })
                }
                if (sorted.isEmpty()) {
                    val empty = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(24), dp(52), dp(24), dp(52))
                        background = rounded(surfaceColor, dp(16), outlineColor, dp(1))
                        addView(text(if (query.isBlank()) "Your library is empty" else "No matches found", 19f, true))
                        addView(text(if (query.isBlank()) "Save text here so it stays available on this device." else "Try another title or phrase.", 14f, color = mutedColor))
                    }
                    list.addView(empty)
                } else sorted.forEach { item -> list.addView(itemCard(item)) }
            }
        }
    }

    private fun findLibraryList(): LinearLayout? {
        return libraryList
    }

    private fun itemCard(item: ClipItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(surfaceColor, dp(15), outlineColor, dp(1))
        isClickable = true
        isFocusable = true
        contentDescription = "${item.title}, ${item.characterCount} characters, ${if (item.pinned) "pinned" else "not pinned"}"
        val titleRow = row()
        titleRow.addView(text((if (item.pinned) "★  " else "") + item.title, 17f, true), LinearLayout.LayoutParams(0, dp(34), 1f))
        titleRow.addView(text("›", 24f, color = mutedColor))
        addView(titleRow)
        addView(text("${item.characterCount} characters · ${humanSize(item.sizeBytes)} · ${dateLabel(item.modifiedAt)}", 13f, color = mutedColor))
        setOnClickListener { showDetails(item.id) }
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        layoutParams = params
    }

    private fun showDetails(id: String) {
        screen = Screen.DETAILS
        selectedId = id
        io.execute {
            val item = store.getItem(id)
            main.post {
                if (screen != Screen.DETAILS || selectedId != id) return@post
                if (item == null) { toast("This item is no longer available."); showLibrary(); return@post }
                val root = column(18)
                root.addView(header("Item", "Library", action = { showLibrary() }), LinearLayout.LayoutParams(-1, -2))
                root.addView(text(item.title, 22f, true).apply { setPadding(0, dp(16), 0, dp(8)) })
                root.addView(text("${item.characterCount} characters · ${humanSize(item.sizeBytes)}", 14f, color = mutedColor))
                root.addView(text("Created ${dateLabel(item.createdAt)}\nModified ${dateLabel(item.modifiedAt)}", 13f, color = mutedColor).apply { setPadding(0, dp(6), 0, dp(16)) })
                val notice = text("For large text, use the ClipMax keyboard in the destination app. It reads this stored file and inserts small chunks instead of sending the whole item through Android's clipboard. The receiving app can still impose its own length limit.", 14f, color = mutedColor).apply {
                    setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(surfaceColor, dp(12), outlineColor, dp(1))
                }
                root.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                root.addView(button("Set up large-text insertion") { showKeyboardSetup() }, fullButton())
                root.addView(button("Copy entire item", primary = true) { confirmCopy(item) }, fullButton())
                root.addView(button("Edit") { openEditor(item) }, fullButton())
                root.addView(button(if (item.pinned) "Unpin" else "Pin to top") { runItemAction(id) { store.setPinned(id, !item.pinned) }; showDetails(id) }, fullButton())
                root.addView(button("Rename") { promptRename(item) }, fullButton())
                root.addView(button("Duplicate") { runItemAction(id) { store.duplicate(id) }; toast("Duplicate created."); showLibrary() }, fullButton())
                root.addView(button("Export .txt") { createDocument(FileAction.EXPORT_TEXT, "${safeFilename(item.title)}.txt", id) }, fullButton())
                root.addView(button("Share text") { shareItem(item) }, fullButton())
                root.addView(button("Delete item") { confirmDelete(item) }, fullButton())
                screenRoot(ScrollView(this).apply { isFillViewport = true; addView(root) })
            }
        }
    }

    private fun fullButton() = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(5) }

    private fun openEditor(item: ClipItem) {
        io.execute {
            val content = runCatching { store.readContent(item) }.getOrElse {
                main.post { toast("Could not open this item: ${it.message}") }; return@execute
            }
            main.post { showEditor(item.id, item.title, content) }
        }
    }

    private fun openRequestedCardForEdit(request: Intent?) {
        val id = request?.getStringExtra(EXTRA_EDIT_ITEM_ID) ?: return
        io.execute {
            val result = runCatching {
                val item = store.getItem(id) ?: throw IllegalArgumentException("The card no longer exists.")
                item to store.readContent(item)
            }
            main.post {
                result.onSuccess { (item, content) -> showEditor(item.id, item.title, content) }
                    .onFailure { toast("Could not open this card: ${it.message ?: "item unavailable"}") }
            }
        }
    }

    private fun showEditor(id: String?, title: String, content: String, recovered: Boolean = false) {
        screen = Screen.EDITOR
        editorId = id
        dirty = recovered
        saving = false
        val root = column(16)
        root.addView(header(if (id == null) "New item" else "Edit item", "Save", { saveEditor() }, back = true))
        root.addView(text("Title", 13f, true, mutedColor).apply { setPadding(0, dp(4), 0, dp(4)) })
        val titleField = editField("Name this item").apply { setText(title) }
        editorTitle = titleField
        root.addView(titleField, LinearLayout.LayoutParams(-1, dp(52)))
        val count = text("", 13f, color = mutedColor).apply { setPadding(0, dp(6), 0, dp(6)) }
        countLabel = count
        root.addView(count)
        val contentField = editField("Paste or type your text", multiline = true).apply {
            setText(content)
            setSelection(text.length)
        }
        editorContent = contentField
        editorCodePoints = content.codePointCount(0, content.length)
        root.addView(contentField, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
        root.addView(button("Choose a typing keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, fullButton())
        val actions = row().apply {
            addView(button("Paste") { pasteClipboard() }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(button("Find") { findInEditor() }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(button("Import .txt") { startImportText() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        root.addView(actions)
        screenRoot(root)
        val watcher = object : TextWatcher {
            private var previousWindowCodePoints = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (s == null) return
                val windowStart = (start - 1).coerceAtLeast(0)
                val windowEnd = (start + count + 1).coerceAtMost(s.length)
                previousWindowCodePoints = Character.codePointCount(s, windowStart, windowEnd)
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, countChanged: Int) {
                if (s != null) {
                    val windowStart = (start - 1).coerceAtLeast(0)
                    val windowEnd = (start + countChanged + 1).coerceAtMost(s.length)
                    editorCodePoints += Character.codePointCount(s, windowStart, windowEnd) - previousWindowCodePoints
                }
                dirty = true
                updateCount(editorCodePoints, s?.length?.toLong() ?: 0L)
                scheduleDraft()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        contentField.addTextChangedListener(watcher)
        titleField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { dirty = true; scheduleDraft() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateCount(editorCodePoints, content.length.toLong())
        if (recovered) toast("Draft restored. Save when you're ready.")
    }

    private fun updateCount(characters: Int, utf16Length: Long) {
        countLabel?.text = "$characters characters · about ${humanSize(utf16Length * 2)} UTF-8 (exact on save)${if (dirty) " · Unsaved changes" else ""}"
    }

    private fun scheduleDraft() {
        draftRunnable?.let(main::removeCallbacks)
        val run = Runnable {
            val snapshot = captureDraft() ?: return@Runnable
            io.execute { runCatching { store.saveDraft(snapshot.first, snapshot.second, snapshot.third) } }
        }
        draftRunnable = run
        main.postDelayed(run, 1200)
    }

    private fun captureDraft(): Triple<String?, String, String>? {
        if (screen != Screen.EDITOR) return null
        val title = editorTitle?.text?.toString() ?: return null
        val content = editorContent?.text?.toString() ?: return null
        return Triple(editorId, title, content)
    }

    private fun saveEditor() {
        if (saving) return
        val titleField = editorTitle ?: return
        val contentField = editorContent ?: return
        val title = titleField.text.toString().trim().ifEmpty { "Untitled" }
        if (title.length > 200) { toast("Title must be 200 characters or fewer."); return }
        val content = contentField.text.toString()
        val id = editorId
        saving = true
        toast("Saving…")
        io.execute {
            val saved = runCatching { store.save(id, title, content) }
            if (saved.isSuccess) runCatching { store.clearDraft() }
            main.post {
                saving = false
                if (saved.isFailure) {
                    dirty = true
                    toast("Save failed: ${saved.exceptionOrNull()?.message ?: "storage error"}")
                } else {
                    dirty = false
                    val item = saved.getOrThrow()
                    toast("Saved ${item.characterCount} characters (${humanSize(item.sizeBytes)}).")
                    showDetails(item.id)
                }
            }
        }
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this).setTitle("Unsaved changes")
            .setMessage("Save this item before leaving?")
            .setPositiveButton("Save") { _, _ -> saveEditor() }
            .setNegativeButton("Discard") { _, _ ->
                draftRunnable?.let(main::removeCallbacks)
                dirty = false
                io.execute { store.clearDraft() }
                leaveEditor()
            }
            .setNeutralButton("Keep editing", null).show()
    }

    private fun leaveEditor() {
        val id = editorId
        if (id != null) showDetails(id) else showLibrary()
    }

    private fun pasteClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) { toast("Clipboard is empty."); return }
        val pasted = runCatching { clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() }.getOrElse {
            toast("Could not read clipboard text."); return
        }
        val field = editorContent ?: return
        val start = field.selectionStart.coerceAtLeast(0)
        val end = field.selectionEnd.coerceAtLeast(0)
        field.text.replace(minOf(start, end), maxOf(start, end), pasted)
    }

    private fun findInEditor() {
        val field = editorContent ?: return
        val input = EditText(this).apply { hint = "Find text"; setSingleLine(true); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        AlertDialog.Builder(this).setTitle("Find in this item").setView(input)
            .setPositiveButton("Find") { _, _ ->
                val needle = input.text.toString()
                if (needle.isBlank()) return@setPositiveButton
                val haystack = field.text.toString()
                io.execute {
                    val index = haystack.indexOf(needle, (field.selectionStart + 1).coerceAtLeast(0), ignoreCase = true)
                    main.post {
                        if (index >= 0) { field.requestFocus(); field.setSelection(index, (index + needle.length).coerceAtMost(field.length())); toast("Match selected.") }
                        else toast("No match found.")
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmCopy(item: ClipItem) {
        val doCopy = {
            io.execute {
                val value = runCatching { store.readContent(item) }
                main.post {
                    if (value.isFailure) toast("Copy failed: ${value.exceptionOrNull()?.message}")
                    else {
                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(item.title, value.getOrThrow()))
                        toast("Copied. Very large clipboard contents may not work in every app.")
                    }
                }
            }
        }
        if (item.sizeBytes > 256 * 1024) AlertDialog.Builder(this).setTitle("Copy a large item?")
            .setMessage("This item is ${humanSize(item.sizeBytes)}. Android or the destination app may limit large clipboard contents.")
            .setPositiveButton("Copy") { _, _ -> doCopy() }.setNegativeButton("Cancel", null).show()
        else doCopy()
    }

    private fun shareItem(item: ClipItem) {
        val send = {
            io.execute {
                val value = runCatching { store.readContent(item) }
                main.post {
                    value.onSuccess { content ->
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, content)
                        startActivity(Intent.createChooser(intent, "Share text"))
                    }.onFailure { toast("Could not prepare share: ${it.message}") }
                }
            }
        }
        if (item.sizeBytes > 256 * 1024) AlertDialog.Builder(this).setTitle("Share a large item?")
            .setMessage("The receiving app may not accept very large text.")
            .setPositiveButton("Continue") { _, _ -> send() }.setNegativeButton("Cancel", null).show()
        else send()
    }

    private fun promptRename(item: ClipItem) {
        val field = editField("Title").apply { setSingleLine(true); setText(item.title) }
        AlertDialog.Builder(this).setTitle("Rename item").setView(field)
            .setPositiveButton("Save") { _, _ -> runItemAction(item.id) { store.rename(item.id, field.text.toString()) }; showDetails(item.id) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(item: ClipItem) {
        AlertDialog.Builder(this).setTitle("Delete this item?")
            .setMessage("“${item.title}” will be removed from ClipMax. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    val result = runCatching {
                        store.delete(item.id).also {
                            CapturePreferences.removeExpiry(CapturePreferences.preferences(this@MainActivity), item.id)
                            CaptureExpiryReceiver.schedule(this@MainActivity)
                        }
                    }
                    main.post { if (result.isSuccess) { toast("Item deleted."); showLibrary() } else toast("Delete failed: ${result.exceptionOrNull()?.message}") }
                }
            }.show()
    }

    private fun runItemAction(id: String, action: () -> Any?) {
        io.execute {
            val result = runCatching { action() }
            main.post { if (result.isFailure) toast("Action failed: ${result.exceptionOrNull()?.message}") }
        }
    }

    private fun showKeyboardSetup() {
        AlertDialog.Builder(this)
            .setTitle("Automatic copy capture")
            .setMessage("Enable ClipMax keyboard in Android keyboard settings. Text selected inside the focused editable field can be captured directly; for read-only text such as a message or article, use that app's Copy action. ClipMax watches Copy while active and checks the current clipboard when opened, saving available text as serially numbered 50,000-character cards. Search from the keyboard. Tap More or long-press a card for Edit, Pin, or Delete. Choose permanent or timed retention in Settings. Android/source apps may still limit what they expose. Password fields are excluded.")
            .setPositiveButton("Open keyboard settings") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                    .onFailure { toast("Could not open keyboard settings: ${it.message}") }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun chooseCaptureMode() {
        val preferences = CapturePreferences.preferences(this)
        val current = CapturePreferences.mode(preferences)
        val selected = CapturePreferences.modes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Automatic copy capture")
            .setMessage("ClipMax captures selections directly only inside the focused editable field. For read-only content, use the app's Copy action; ClipMax checks the clipboard while active and when the keyboard opens, then saves available text as serially numbered 50,000-character cards. Choose permanent storage, timed deletion, or turn automatic capture off.")
            .setSingleChoiceItems(CapturePreferences.labels.toTypedArray(), selected) { dialog, which ->
                preferences.edit().putString(CapturePreferences.MODE_KEY, CapturePreferences.modes[which]).apply()
                CaptureExpiryReceiver.schedule(this)
                dialog.dismiss()
                showSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettings() {
        screen = Screen.SETTINGS
        val root = column(18)
        root.addView(header("Settings", "Library", action = { showLibrary() }))
        root.addView(text("Storage & privacy", 18f, true).apply { setPadding(0, dp(12), 0, dp(8)) })
        val storageText = text("Checking local storage…", 14f, color = mutedColor)
        root.addView(storageText)
        root.addView(button("Set up ClipMax keyboard") { showKeyboardSetup() }, fullButton())
        val captureMode = CapturePreferences.mode(CapturePreferences.preferences(this))
        root.addView(button("Automatic capture: ${CapturePreferences.modeLabel(captureMode)}") { chooseCaptureMode() }, fullButton())
        root.addView(button("Create library backup (.zip)") { createDocument(FileAction.BACKUP, "ClipMax-backup.zip") }, fullButton())
        root.addView(button("Restore from ClipMax backup") { startRestore() }, fullButton())
        root.addView(text("ClipMax works offline and stores items in this app's private storage. When automatic capture is enabled, it observes selection and clipboard changes only while ClipMax is the active keyboard and splits captured text into numbered 50,000-character cards. Search cards on the keyboard. Tap More or long-press a card for Edit, Pin, or Delete. Android/source apps may still limit what they expose. ClipMax does not record keystrokes and disables capture in password fields. Items are limited to 16 MiB each.", 14f, color = mutedColor).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(surfaceColor, dp(12), outlineColor, dp(1))
        })
        root.addView(button("Delete all saved data") {
            AlertDialog.Builder(this).setTitle("Delete all ClipMax data?")
                .setMessage("This removes every saved item and draft from this app. Export a backup first if you may need the data.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete all") { _, _ ->
                    io.execute { val result = runCatching { store.deleteAll().also { CapturePreferences.clearExpiryLedger(CapturePreferences.preferences(this@MainActivity)); CaptureExpiryReceiver.schedule(this@MainActivity) } }; main.post { if (result.isSuccess) { toast("Local data deleted."); showLibrary() } else toast("Could not delete all data.") } }
                }.show()
        }, fullButton())
        root.addView(text("ClipMax v0.5.1 · data stays on this device unless you export or share it.", 12f, color = mutedColor).apply { setPadding(0, dp(18), 0, 0) })
        screenRoot(ScrollView(this).apply { isFillViewport = true; addView(root) })
        io.execute {
            CapturePreferences.cleanupExpired(store, CapturePreferences.preferences(this@MainActivity))
            CaptureExpiryReceiver.schedule(this@MainActivity)
            val items = store.listItems().size
            val bytes = store.totalBytes()
            main.post { if (screen == Screen.SETTINGS) storageText.text = "$items saved ${if (items == 1) "item" else "items"} · ${humanSize(bytes)} of text" }
        }
    }

    private fun createDocument(action: FileAction, name: String, itemId: String? = null) {
        pendingFileAction = action
        pendingItemId = itemId
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (action) { FileAction.EXPORT_TEXT -> "text/plain"; FileAction.BACKUP -> "application/zip"; else -> "*/*" }
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, 501)
    }

    private fun startImportText() {
        pendingFileAction = FileAction.IMPORT_TEXT
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*")); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, 501)
    }

    private fun startRestore() {
        pendingFileAction = FileAction.RESTORE
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream")); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, 501)
    }

    @Deprecated("Using platform document picker for broad API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 501 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val action = pendingFileAction ?: return
        val id = pendingItemId
        pendingFileAction = null
        pendingItemId = null
        io.execute {
            val result = runCatching {
                when (action) {
                    FileAction.IMPORT_TEXT -> {
                        val title = displayTitle(uri)
                        contentResolver.openInputStream(uri)?.use { store.importText(it, title) } ?: error("Could not open the selected file.")
                    }
                    FileAction.EXPORT_TEXT -> {
                        val targetId = id ?: error("No item selected for export.")
                        contentResolver.openOutputStream(uri, "w")?.use { store.writeTextTo(targetId, it) } ?: error("Could not create the export file.")
                        null
                    }
                    FileAction.BACKUP -> {
                        contentResolver.openOutputStream(uri, "w")?.use { store.backup(it) } ?: error("Could not create the backup file.")
                        null
                    }
                    FileAction.RESTORE -> {
                        contentResolver.openInputStream(uri)?.use { store.restore(it) } ?: error("Could not open this backup.")
                    }
                }
            }
            main.post {
                if (result.isFailure) toast("File operation failed: ${result.exceptionOrNull()?.message ?: "unsupported file or storage error"}")
                else when (action) {
                    FileAction.IMPORT_TEXT -> { val item = result.getOrNull() as? ClipItem; toast("Text imported."); item?.let { showDetails(it.id) } }
                    FileAction.EXPORT_TEXT -> toast("Text exported.")
                    FileAction.BACKUP -> toast("Backup created.")
                    FileAction.RESTORE -> { toast("${result.getOrNull() ?: 0} item(s) restored."); showLibrary() }
                }
            }
        }
    }

    private fun displayTitle(uri: Uri): String {
        val raw = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.substringAfterLast('/') ?: "Imported text"
        return raw.removeSuffix(".txt").take(200).ifBlank { "Imported text" }
    }

    private fun confirmRestore() = Unit

    private fun updateCountUnused() = Unit

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0))
    }

    private fun dateLabel(millis: Long): String = android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(millis)) +
        " " + android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(millis))

    private fun safeFilename(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "ClipMax-item" }.take(80)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
