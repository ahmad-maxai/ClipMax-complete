package com.clipmax.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript to Kotlin bridge exposed to the WebView under the name "ClipMaxNative".
 * Provides high-performance, atomic local persistence via [ClipStore] and access
 * to real Android capabilities (IME settings, clipboard, sharing, retention).
 */
class AndroidBridge(
    private val context: Context,
    private val store: ClipStore
) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val webSyncLock = Any()
    private val lastWebSyncedIds = mutableSetOf<String>()

    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun getItems(): String {
        return try {
            val items = store.listItems()
            val readIds = ArrayList<String>(items.size)
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                obj.put("content", store.readContent(item))
                obj.put("createdAt", item.createdAt)
                obj.put("modifiedAt", item.modifiedAt)
                obj.put("characterCount", item.characterCount)
                obj.put("sizeBytes", item.sizeBytes)
                obj.put("pinned", item.pinned)
                jsonArray.put(obj)
                readIds.add(item.id)
            }
            synchronized(webSyncLock) {
                lastWebSyncedIds.clear()
                lastWebSyncedIds.addAll(readIds)
            }
            jsonArray.toString()
        } catch (e: Throwable) {
            "[]"
        }
    }

    @JavascriptInterface
    fun getItem(id: String): String {
        return try {
            val item = store.getItem(id) ?: return ""
            val content = store.readContent(item)
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("content", content)
            obj.put("createdAt", item.createdAt)
            obj.put("modifiedAt", item.modifiedAt)
            obj.put("characterCount", item.characterCount)
            obj.put("sizeBytes", item.sizeBytes)
            obj.put("pinned", item.pinned)
            obj.toString()
        } catch (e: Throwable) {
            ""
        }
    }

    @JavascriptInterface
    fun saveItem(title: String, content: String, pinned: Boolean): String {
        return try {
            val saved = store.save(id = null, title = title, content = content, pinned = pinned)
            synchronized(webSyncLock) { lastWebSyncedIds.add(saved.id) }
            val obj = JSONObject()
            obj.put("id", saved.id)
            obj.put("title", saved.title)
            obj.put("content", content)
            obj.put("createdAt", saved.createdAt)
            obj.put("modifiedAt", saved.modifiedAt)
            obj.put("characterCount", saved.characterCount)
            obj.put("sizeBytes", saved.sizeBytes)
            obj.put("pinned", saved.pinned)
            obj.toString()
        } catch (e: Throwable) {
            ""
        }
    }

    @JavascriptInterface
    fun updateItem(id: String, title: String, content: String, pinned: Boolean): String {
        return try {
            val saved = store.save(id = id, title = title, content = content, pinned = pinned)
            if (pinned) {
                CapturePreferences.removeExpiry(CapturePreferences.preferences(context), saved.id)
            }
            synchronized(webSyncLock) { lastWebSyncedIds.add(saved.id) }
            val obj = JSONObject()
            obj.put("id", saved.id)
            obj.put("title", saved.title)
            obj.put("content", content)
            obj.put("createdAt", saved.createdAt)
            obj.put("modifiedAt", saved.modifiedAt)
            obj.put("characterCount", saved.characterCount)
            obj.put("sizeBytes", saved.sizeBytes)
            obj.put("pinned", saved.pinned)
            obj.toString()
        } catch (e: Throwable) {
            ""
        }
    }

    @JavascriptInterface
    fun deleteItem(id: String): Boolean {
        return try {
            val removed = store.delete(id)
            if (removed) {
                val prefs = CapturePreferences.preferences(context)
                CapturePreferences.removeExpiry(prefs, id)
                CapturePreferences.onItemsDeleted(prefs, listOf(id))
                synchronized(webSyncLock) { lastWebSyncedIds.remove(id) }
            }
            removed
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun setPinned(id: String, pinned: Boolean): String {
        return try {
            val updated = store.setPinned(id, pinned)
            if (pinned) {
                CapturePreferences.removeExpiry(CapturePreferences.preferences(context), updated.id)
            }
            val content = store.readContent(updated)
            val obj = JSONObject()
            obj.put("id", updated.id)
            obj.put("title", updated.title)
            obj.put("content", content)
            obj.put("createdAt", updated.createdAt)
            obj.put("modifiedAt", updated.modifiedAt)
            obj.put("characterCount", updated.characterCount)
            obj.put("sizeBytes", updated.sizeBytes)
            obj.put("pinned", updated.pinned)
            obj.toString()
        } catch (e: Throwable) {
            ""
        }
    }

    @JavascriptInterface
    fun syncItems(jsonArrayString: String): Boolean {
        return try {
            val jsonArray = JSONArray(jsonArrayString)
            val existingItems = store.listItems().associateBy { it.id }
            val knownIdsSnapshot = synchronized(webSyncLock) { lastWebSyncedIds.toSet() }
            val prefs = CapturePreferences.preferences(context)
            val newIds = mutableSetOf<String>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = if (obj.has("id") && !obj.isNull("id")) obj.getString("id") else null
                val title = if (obj.has("title")) obj.getString("title") else "Untitled"
                val content = if (obj.has("content")) obj.getString("content") else ""
                val pinned = if (obj.has("pinned")) obj.getBoolean("pinned") else false
                val modifiedAt = if (obj.has("modifiedAt")) obj.getLong("modifiedAt") else null
                val createdAt = if (obj.has("createdAt")) obj.getLong("createdAt") else null

                val existing = id?.let { existingItems[it] }
                val saved = if (existing != null &&
                    existing.characterCount == content.codePointCount(0, content.length) &&
                    runCatching { store.readContent(existing) == content }.getOrDefault(false)
                ) {
                    store.updateMetadataOnly(existing.id, title, pinned, modifiedAt) ?: existing
                } else {
                    store.save(
                        id = id,
                        title = title,
                        content = content,
                        pinned = pinned,
                        savedAt = modifiedAt ?: createdAt
                    )
                }
                if (pinned) {
                    CapturePreferences.removeExpiry(prefs, saved.id)
                }
                newIds.add(saved.id)
            }

            val deletedIds = mutableListOf<String>()
            val clearAllRequested = jsonArray.length() == 0
            for (oldId in existingItems.keys) {
                if (oldId !in newIds && (clearAllRequested || oldId in knownIdsSnapshot)) {
                    if (store.delete(oldId)) {
                        CapturePreferences.removeExpiry(prefs, oldId)
                        deletedIds.add(oldId)
                    }
                }
            }
            if (deletedIds.isNotEmpty()) {
                if (clearAllRequested) {
                    CapturePreferences.onAllItemsCleared(prefs)
                } else {
                    CapturePreferences.onItemsDeleted(prefs, deletedIds)
                }
            }

            synchronized(webSyncLock) {
                lastWebSyncedIds.clear()
                lastWebSyncedIds.addAll(newIds)
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun clearAllItems(): Boolean {
        return try {
            store.deleteAll()
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.clearExpiryLedger(prefs)
            CapturePreferences.onAllItemsCleared(prefs)
            synchronized(webSyncLock) { lastWebSyncedIds.clear() }
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean {
        return try {
            ClipboardCaptureCoordinator.markSelfCopy(text)
            mainHandler.post {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("ClipMax", text)
                clipboard?.setPrimaryClip(clip)
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun shareText(text: String, title: String): Boolean {
        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Share clip").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun openImeSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Throwable) {
            // ignore
        }
    }

    @JavascriptInterface
    fun showImePicker() {
        try {
            mainHandler.post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        } catch (e: Throwable) {
            // ignore
        }
    }

    @JavascriptInterface
    fun getRetention(): String {
        return try {
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.mode(prefs)
        } catch (e: Throwable) {
            "24h"
        }
    }

    @JavascriptInterface
    fun setRetention(retention: String) {
        try {
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.setMode(prefs, retention)
        } catch (e: Throwable) {
            // ignore
        }
    }

    @JavascriptInterface
    fun getCustomRetentionDuration(): Int {
        return try {
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.customDuration(prefs)
        } catch (e: Throwable) {
            CapturePreferences.DEFAULT_CUSTOM_DURATION
        }
    }

    @JavascriptInterface
    fun getCustomRetentionUnit(): String {
        return try {
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.customUnit(prefs)
        } catch (e: Throwable) {
            CapturePreferences.DEFAULT_CUSTOM_UNIT
        }
    }

    @JavascriptInterface
    fun setCustomRetention(duration: Int, unit: String) {
        try {
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.setCustomRetention(prefs, duration, unit)
        } catch (e: Throwable) {
            // ignore
        }
    }

    @JavascriptInterface
    fun getCaptureEnabled(): Boolean {
        return try {
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.isEnabled(CapturePreferences.mode(prefs))
        } catch (e: Throwable) {
            true
        }
    }

    @JavascriptInterface
    fun setCaptureEnabled(enabled: Boolean) {
        try {
            val prefs = CapturePreferences.preferences(context)
            CapturePreferences.setCaptureEnabled(prefs, enabled)
        } catch (e: Throwable) {
            // ignore
        }
    }

    @JavascriptInterface
    fun setTheme(theme: String) {
        try {
            val normalized = SettingsPanelSpec.normalizeThemeValue(theme)
            val prefs = context.getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
            prefs.edit().putString(SettingsPanelSpec.PREF_APP_THEME, normalized).apply()
            mainHandler.post {
                (context as? MainActivity)?.applySystemBarTheme()
            }
        } catch (e: Throwable) {
            // ignore
        }
    }

    @JavascriptInterface
    fun getTheme(): String {
        return try {
            val prefs = context.getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
            val raw = prefs.getString(SettingsPanelSpec.PREF_APP_THEME, "system")
            SettingsPanelSpec.normalizeThemeValue(raw)
        } catch (e: Throwable) {
            "system"
        }
    }

    @JavascriptInterface
    fun isSystemNightMode(): Boolean {
        return try {
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        } catch (e: Throwable) {
            false
        }
    }
}
