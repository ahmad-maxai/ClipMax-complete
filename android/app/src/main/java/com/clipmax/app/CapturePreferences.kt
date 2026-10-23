package com.clipmax.app

import android.content.Context
import android.content.SharedPreferences

/** Preferences shared by the app and its IME for opt-in automatic copy capture and expiry. */
internal object CapturePreferences {
    const val PREFS_NAME = "clipmax_capture"
    const val MODE_KEY = "mode"
    const val DEFAULT_MODE = "24h"
    private const val LAST_CLIPBOARD_SIGNATURE_KEY = "last_clipboard_signature"
    private const val EXPIRY_PREFIX = "expiry:"

    val modes = listOf("forever", "1h", "6h", "24h", "72h", "off")
    val labels = listOf(
        "Automatically save copies · keep forever",
        "Automatically save · delete after 1 hour",
        "Automatically save · delete after 6 hours",
        "Automatically save · delete after 24 hours",
        "Automatically save · delete after 72 hours",
        "Turn automatic capture off"
    )

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mode(preferences: SharedPreferences): String =
        preferences.getString(MODE_KEY, DEFAULT_MODE)?.takeIf { it in modes } ?: DEFAULT_MODE

    fun lastClipboardSignature(preferences: SharedPreferences): String? =
        preferences.getString(LAST_CLIPBOARD_SIGNATURE_KEY, null)

    fun markClipboardSignature(preferences: SharedPreferences, signature: String) {
        preferences.edit().putString(LAST_CLIPBOARD_SIGNATURE_KEY, signature).apply()
    }

    fun modeLabel(value: String): String = labels[modes.indexOf(value).takeIf { it >= 0 } ?: modes.indexOf(DEFAULT_MODE)]

    fun isEnabled(value: String): Boolean = value != "off"

    /** Returns null for permanent storage; disabled mode should never be passed here. */
    private fun retentionMillis(value: String): Long? = when (value) {
        "forever" -> null
        "1h" -> 60L * 60L * 1000L
        "6h" -> 6L * 60L * 60L * 1000L
        "24h" -> 24L * 60L * 60L * 1000L
        "72h" -> 72L * 60L * 60L * 1000L
        else -> 24L * 60L * 60L * 1000L
    }

    fun markCaptured(preferences: SharedPreferences, itemId: String, value: String, now: Long = System.currentTimeMillis()) {
        val expiry = retentionMillis(value)
        val editor = preferences.edit()
        if (expiry == null) editor.remove(expiryKey(itemId))
        else editor.putLong(expiryKey(itemId), now + expiry)
        editor.apply()
    }

    fun removeExpiry(preferences: SharedPreferences, itemId: String) {
        preferences.edit().remove(expiryKey(itemId)).apply()
    }

    fun clearExpiryLedger(preferences: SharedPreferences) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(EXPIRY_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    fun nextExpiry(preferences: SharedPreferences): Long? = preferences.all
        .asSequence()
        .filter { it.key.startsWith(EXPIRY_PREFIX) && it.value is Long }
        .mapNotNull { it.value as? Long }
        .minOrNull()

    /** Deletes expired capture cards; expired IDs are removed from the preference ledger too. */
    @Synchronized
    fun cleanupExpired(store: ClipStore, preferences: SharedPreferences, now: Long = System.currentTimeMillis()): Int {
        var deleted = 0
        val editor = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith(EXPIRY_PREFIX) || value !is Long || value > now) return@forEach
            val id = key.removePrefix(EXPIRY_PREFIX)
            val existing = store.getItem(id)
            if (existing == null || runCatching { store.delete(id) }.getOrDefault(false)) {
                if (existing != null) deleted++
                editor.remove(key)
            }
        }
        editor.apply()
        return deleted
    }

    private fun expiryKey(id: String) = "$EXPIRY_PREFIX$id"
}
