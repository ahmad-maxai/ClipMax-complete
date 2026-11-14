package com.clipmax.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide serialized coordinator for Android clipboard and selection capture.
 * Runs all signature hashing and persistence sequentially on a dedicated worker thread
 * so rapid consecutive copies never race or overwrite each other across IME and Activity instances.
 */
internal object ClipboardCaptureCoordinator {
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val SELF_COPY_LABEL = "ClipMax"
    private const val SELF_COPY_WINDOW_MS = 15_000L

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ClipMaxCapture").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initLock = Any()

    private var initializedStore: ClipStore? = null
    private var initializedPrefs: SharedPreferences? = null
    private var prefsStateStore: PrefsSignatureStateStore? = null
    private var engine: ClipboardCaptureEngine? = null

    // Guarded by single-threaded worker execution
    private var recentSelfCopySignature: String? = null
    private var recentSelfCopyElapsed: Long = 0L

    private class PrefsSignatureStateStore(
        private val preferences: SharedPreferences
    ) : SignatureStateStore {
        override fun lastSignature(): String? = CapturePreferences.lastClipboardSignature(preferences)

        override fun lastClipTimestamp(): Long = CapturePreferences.lastClipTimestamp(preferences)

        override fun lastCapturedIds(): Set<String> = CapturePreferences.lastCapturedIds(preferences)

        override fun recordCapture(signature: String, clipTimestamp: Long, itemIds: List<String>) {
            CapturePreferences.markClipboardState(preferences, signature, clipTimestamp, itemIds)
        }

        override fun updateObservedTimestamp(signature: String, clipTimestamp: Long) {
            val currentIds = CapturePreferences.lastCapturedIds(preferences)
            CapturePreferences.markClipboardState(preferences, signature, clipTimestamp, currentIds)
        }
    }

    private fun ensureEngine(appContext: Context): Pair<ClipboardCaptureEngine, PrefsSignatureStateStore> {
        synchronized(initLock) {
            val existingEngine = engine
            val existingState = prefsStateStore
            if (existingEngine != null && existingState != null) {
                return existingEngine to existingState
            }
            val store = ClipStore(appContext.filesDir)
            val prefs = CapturePreferences.preferences(appContext)
            val stateStore = PrefsSignatureStateStore(prefs)
            val createdEngine = ClipboardCaptureEngine(store, stateStore)
            initializedStore = store
            initializedPrefs = prefs
            prefsStateStore = stateStore
            engine = createdEngine
            return createdEngine to stateStore
        }
    }

    /**
     * Records an internal copy initiated by ClipMax UI so the resulting system
     * OnPrimaryClipChangedListener callback does not re-capture an existing item.
     * Submitted onto the worker queue before setPrimaryClip so ordering is strict.
     */
    fun markSelfCopy(text: CharSequence) {
        if (text.isEmpty()) return
        val snapshot = text.toString()
        val capturedElapsed = SystemClock.elapsedRealtime()
        worker.execute {
            recentSelfCopySignature = ClipboardCaptureEngine.computeContentSignature(snapshot)
            recentSelfCopyElapsed = capturedElapsed
        }
    }

    fun captureCurrentClipboard(
        context: Context,
        source: CaptureSource,
        isSensitiveField: Boolean = false,
        onComplete: ((CaptureOutcome?) -> Unit)? = null
    ) {
        if (isSensitiveField) {
            onComplete?.let { mainHandler.post { it(null) } }
            return
        }
        val appContext = context.applicationContext
        val prefs = CapturePreferences.preferences(appContext)
        val mode = CapturePreferences.mode(prefs)
        if (!CapturePreferences.isEnabled(mode)) {
            onComplete?.let { mainHandler.post { it(null) } }
            return
        }

        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
        val description = clip.description
        val clipTimestamp = description?.timestamp ?: -1L
        val label = description?.label?.toString()
        val clipSensitive = description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        if (clipSensitive) {
            onComplete?.let { mainHandler.post { it(null) } }
            return
        }

        val extractedItems = ArrayList<CharSequence>(clip.itemCount)
        for (i in 0 until clip.itemCount) {
            val text = extractItemText(appContext, description, clip.getItemAt(i))
            if (!text.isNullOrEmpty()) {
                extractedItems.add(text)
            }
        }
        if (extractedItems.isEmpty()) {
            onComplete?.let { mainHandler.post { it(null) } }
            return
        }

        worker.execute {
            val (captureEngine, stateStore) = ensureEngine(appContext)
            val currentMode = CapturePreferences.mode(prefs)
            if (!CapturePreferences.isEnabled(currentMode)) {
                onComplete?.let { mainHandler.post { it(null) } }
                return@execute
            }

            var lastOutcome: CaptureOutcome? = null
            var anySaved = false
            for (itemText in extractedItems) {
                val signature = ClipboardCaptureEngine.computeContentSignature(itemText)
                val nowElapsed = SystemClock.elapsedRealtime()
                val isSelfCopy = label == SELF_COPY_LABEL &&
                    signature == recentSelfCopySignature &&
                    (nowElapsed - recentSelfCopyElapsed) < SELF_COPY_WINDOW_MS
                if (isSelfCopy) {
                    recentSelfCopySignature = null
                    if (clipTimestamp > 0L) {
                        stateStore.updateObservedTimestamp(signature, clipTimestamp)
                    }
                    continue
                }

                val outcome = runCatching {
                    captureEngine.captureText(
                        text = itemText,
                        source = source,
                        clipTimestamp = clipTimestamp,
                        isSensitive = false,
                        deduplicateConsecutive = true
                    )
                }.getOrNull() ?: continue

                if (outcome.savedCount > 0) {
                    anySaved = true
                    for (saved in outcome.savedItems) {
                        CapturePreferences.markCaptured(prefs, saved.id, currentMode, outcome.capturedAt)
                    }
                    lastOutcome = outcome
                }
            }

            if (anySaved) {
                CaptureExpiryReceiver.schedule(appContext)
            }
            onComplete?.let { callback ->
                mainHandler.post { callback(lastOutcome) }
            }
        }
    }

    fun captureSelection(
        context: Context,
        selectedText: CharSequence,
        isSensitiveField: Boolean = false,
        onComplete: ((CaptureOutcome?) -> Unit)? = null
    ) {
        if (isSensitiveField || selectedText.isEmpty()) {
            onComplete?.let { mainHandler.post { it(null) } }
            return
        }
        val appContext = context.applicationContext
        val prefs = CapturePreferences.preferences(appContext)
        if (!CapturePreferences.isEnabled(CapturePreferences.mode(prefs))) {
            onComplete?.let { mainHandler.post { it(null) } }
            return
        }

        worker.execute {
            val (captureEngine, _) = ensureEngine(appContext)
            val currentMode = CapturePreferences.mode(prefs)
            if (!CapturePreferences.isEnabled(currentMode)) {
                onComplete?.let { mainHandler.post { it(null) } }
                return@execute
            }

            val outcome = runCatching {
                captureEngine.captureText(
                    text = selectedText,
                    source = CaptureSource.SELECTION,
                    clipTimestamp = -1L,
                    isSensitive = false,
                    deduplicateConsecutive = true
                )
            }.getOrNull()

            if (outcome != null && outcome.savedCount > 0) {
                for (saved in outcome.savedItems) {
                    CapturePreferences.markCaptured(prefs, saved.id, currentMode, outcome.capturedAt)
                }
                CaptureExpiryReceiver.schedule(appContext)
            }
            onComplete?.let { callback ->
                mainHandler.post { callback(outcome) }
            }
        }
    }

    private fun extractItemText(
        context: Context,
        description: ClipDescription?,
        item: ClipData.Item
    ): CharSequence? {
        item.text?.takeIf { it.isNotEmpty() }?.let { return it }
        item.htmlText?.let { html ->
            runCatching { Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        if (description?.hasMimeType("text/*") == true) {
            runCatching { item.coerceToText(context) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }
}
