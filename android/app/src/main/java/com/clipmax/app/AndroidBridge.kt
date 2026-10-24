package com.clipmax.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun getItems(): String {
        return try {
            val items = store.listItems()
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
            store.delete(id)
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun setPinned(id: String, pinned: Boolean): String {
        return try {
            val updated = store.setPinned(id, pinned)
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
            val existingIds = store.listItems().map { it.id }.toSet()
            val newIds = mutableSetOf<String>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = if (obj.has("id")) obj.getString("id") else null
                val title = if (obj.has("title")) obj.getString("title") else "Untitled"
                val content = if (obj.has("content")) obj.getString("content") else ""
                val pinned = if (obj.has("pinned")) obj.getBoolean("pinned") else false
                val savedAt = if (obj.has("modifiedAt")) obj.getLong("modifiedAt") else null

                val saved = store.save(id = id, title = title, content = content, pinned = pinned, savedAt = savedAt)
                newIds.add(saved.id)
            }

            for (oldId in existingIds) {
                if (oldId !in newIds) {
                    store.delete(oldId)
                }
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
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String): Boolean {
        return try {
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
            prefs.edit().putString(CapturePreferences.MODE_KEY, retention).apply()
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
            // ignore
        }
        return true
    }

    @JavascriptInterface
    fun setCaptureEnabled(enabled: Boolean) {
        try {
            val prefs = CapturePreferences.preferences(context)
            val current = CapturePreferences.mode(prefs)
            if (!enabled) {
                prefs.edit().putString(CapturePreferences.MODE_KEY, "off").apply()
            } else if (current == "off") {
                prefs.edit().putString(CapturePreferences.MODE_KEY, "24h").apply()
            }
        } catch (e: Throwable) {
            // ignore
        }
    }
}
