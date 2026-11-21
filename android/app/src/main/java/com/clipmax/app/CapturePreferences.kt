package com.clipmax.app

import android.content.Context
import android.content.SharedPreferences

/** Preferences shared by the app and its IME for opt-in automatic copy capture and expiry. */
internal object CapturePreferences {
    const val PREFS_NAME = "clipmax_capture"
    const val MODE_KEY = "mode"
    const val DEFAULT_MODE = "24h"
    const val CUSTOM_DURATION_KEY = "custom_retention_duration"
    const val CUSTOM_UNIT_KEY = "custom_retention_unit"
    const val DEFAULT_CUSTOM_DURATION = 15
    const val DEFAULT_CUSTOM_UNIT = "m"

    private const val LAST_ENABLED_MODE_KEY = "last_enabled_mode"
    private const val LAST_CLIPBOARD_SIGNATURE_KEY = "last_clipboard_signature"
    private const val LAST_CLIP_TIMESTAMP_KEY = "last_clipboard_timestamp"
    private const val LAST_CAPTURED_IDS_KEY = "last_captured_ids"
    private const val BASELINE_INITIALIZED_KEY = "baseline_initialized"
    private const val EXPIRY_PREFIX = "expiry:"

    val modes = listOf("forever", "1h", "6h", "24h", "72h", "off")
    val labels = listOf(
        "Automatically save · keep forever",
        "Automatically save · delete after 1 hour",
        "Automatically save · delete after 6 hours",
        "Automatically save · delete after 24 hours",
        "Automatically save · delete after 72 hours",
        "Turn automatic capture off"
    )

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBaselineInitialized(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(BASELINE_INITIALIZED_KEY, false)

    fun markBaselineInitialized(preferences: SharedPreferences) {
        preferences.edit().putBoolean(BASELINE_INITIALIZED_KEY, true).commit()
    }

    fun customDuration(preferences: SharedPreferences): Int =
        preferences.getInt(CUSTOM_DURATION_KEY, DEFAULT_CUSTOM_DURATION).let {
            if (it > 0) it else DEFAULT_CUSTOM_DURATION
        }

    fun customUnit(preferences: SharedPreferences): String =
        preferences.getString(CUSTOM_UNIT_KEY, DEFAULT_CUSTOM_UNIT)?.takeIf {
            it in listOf("m", "h", "min", "mins", "minute", "minutes", "hour", "hours")
        }?.let { if (it.startsWith("h", ignoreCase = true)) "h" else "m" } ?: DEFAULT_CUSTOM_UNIT

    fun setCustomRetention(preferences: SharedPreferences, duration: Int, unit: String) {
        val safeDur = if (duration > 0) duration else DEFAULT_CUSTOM_DURATION
        val safeUnit = if (unit.trim().lowercase().startsWith("h")) "h" else "m"
        preferences.edit()
            .putInt(CUSTOM_DURATION_KEY, safeDur)
            .putString(CUSTOM_UNIT_KEY, safeUnit)
            .commit()
    }

    fun setMode(preferences: SharedPreferences, value: String) {
        val safeMode = if (isValidMode(value)) value else DEFAULT_MODE
        preferences.edit().putString(MODE_KEY, safeMode).commit()
    }

    fun parseCustomDurationAndUnit(str: String): Long? {
        val cleaned = str.trim().lowercase()
        val match = Regex("""^(\d+)\s*([a-z]+)$""").find(cleaned) ?: return null
        val (numStr, unitStr) = match.destructured
        val num = numStr.toLongOrNull() ?: return null
        if (num <= 0L) return null
        return when (unitStr) {
            "m", "min", "mins", "minute", "minutes" -> num * 60_000L
            "h", "hr", "hrs", "hour", "hours" -> num * 3_600_000L
            "d", "day", "days" -> num * 86_400_000L
            else -> null
        }
    }

    fun calculateCustomMillis(duration: Int, unit: String): Long {
        val safeDuration = if (duration > 0) duration.toLong() else DEFAULT_CUSTOM_DURATION.toLong()
        return if (unit.trim().lowercase().startsWith("h")) {
            safeDuration * 3_600_000L
        } else {
            safeDuration * 60_000L
        }
    }

    fun isValidMode(value: String): Boolean {
        val trimmed = value.trim().lowercase()
        if (trimmed in modes) return true
        if (trimmed == "custom") return true
        if (trimmed.startsWith("custom:")) {
            val rest = trimmed.removePrefix("custom:")
            return parseCustomDurationAndUnit(rest) != null
        }
        return parseCustomDurationAndUnit(trimmed) != null
    }

    fun mode(preferences: SharedPreferences): String =
        preferences.getString(MODE_KEY, DEFAULT_MODE)?.takeIf { isValidMode(it) } ?: DEFAULT_MODE

    fun setCaptureEnabled(preferences: SharedPreferences, enabled: Boolean) {
        val current = mode(preferences)
        val remembered = preferences.getString(LAST_ENABLED_MODE_KEY, null)?.takeIf { isValidMode(it) && it != "off" }
        val transition = ClipboardPanelSpec.resolveCaptureToggleTransition(
            currentMode = current,
            rememberedEnabledMode = remembered,
            enable = enabled,
            defaultEnabledMode = DEFAULT_MODE
        )
        val editor = preferences.edit().putString(MODE_KEY, transition.nextMode)
        if (transition.rememberedEnabledMode != null) {
            editor.putString(LAST_ENABLED_MODE_KEY, transition.rememberedEnabledMode)
        }
        editor.commit()
    }

    fun lastClipboardSignature(preferences: SharedPreferences): String? =
        preferences.getString(LAST_CLIPBOARD_SIGNATURE_KEY, null)

    fun lastClipTimestamp(preferences: SharedPreferences): Long =
        preferences.getLong(LAST_CLIP_TIMESTAMP_KEY, -1L)

    fun lastCapturedIds(preferences: SharedPreferences): Set<String> =
        preferences.getString(LAST_CAPTURED_IDS_KEY, null)
            ?.split(',')
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    fun markClipboardSignature(preferences: SharedPreferences, signature: String) {
        preferences.edit().putString(LAST_CLIPBOARD_SIGNATURE_KEY, signature).commit()
    }

    fun markClipboardState(
        preferences: SharedPreferences,
        signature: String,
        clipTimestamp: Long,
        itemIds: Collection<String>
    ) {
        preferences.edit()
            .putString(LAST_CLIPBOARD_SIGNATURE_KEY, signature)
            .putLong(LAST_CLIP_TIMESTAMP_KEY, clipTimestamp)
            .putString(LAST_CAPTURED_IDS_KEY, itemIds.joinToString(","))
            .commit()
    }

    fun onItemsDeleted(preferences: SharedPreferences, deletedIds: Collection<String>) {
        if (deletedIds.isEmpty()) return
        val lastIds = lastCapturedIds(preferences)
        if (lastIds.any { it in deletedIds }) {
            preferences.edit().remove(LAST_CAPTURED_IDS_KEY).commit()
        }
    }

    fun onAllItemsCleared(preferences: SharedPreferences) {
        preferences.edit().remove(LAST_CAPTURED_IDS_KEY).commit()
    }

    fun formatCustomLabel(duration: Int, unit: String): String {
        val safeDuration = if (duration > 0) duration else DEFAULT_CUSTOM_DURATION
        val isHours = unit.trim().lowercase().startsWith("h")
        val unitLabel = if (isHours) {
            if (safeDuration == 1) "1 hour" else "$safeDuration hours"
        } else {
            if (safeDuration == 1) "1 minute" else "$safeDuration minutes"
        }
        return "Automatically save · delete after $unitLabel"
    }

    fun modeLabel(value: String, preferences: SharedPreferences? = null): String {
        val trimmed = value.trim().lowercase()
        val idx = modes.indexOf(trimmed)
        if (idx >= 0 && idx < labels.size) {
            return labels[idx]
        }
        if (trimmed == "custom" && preferences != null) {
            return formatCustomLabel(customDuration(preferences), customUnit(preferences))
        }
        val customPart = if (trimmed.startsWith("custom:")) trimmed.removePrefix("custom:") else trimmed
        val match = Regex("""^(\d+)\s*([a-z]+)$""").find(customPart)
        if (match != null) {
            val (numStr, unitStr) = match.destructured
            val num = numStr.toIntOrNull() ?: DEFAULT_CUSTOM_DURATION
            return formatCustomLabel(num, unitStr)
        }
        return labels[modes.indexOf(DEFAULT_MODE)]
    }

    fun isEnabled(value: String): Boolean = value != "off"

    fun parseRetentionMillis(value: String, preferences: SharedPreferences? = null): Long? {
        val trimmed = value.trim().lowercase()
        return when {
            trimmed == "forever" || trimmed == "off" -> null
            trimmed == "1h" -> 1L * 3_600_000L
            trimmed == "6h" -> 6L * 3_600_000L
            trimmed == "24h" -> 24L * 3_600_000L
            trimmed == "72h" -> 72L * 3_600_000L
            trimmed == "custom" && preferences != null -> {
                calculateCustomMillis(customDuration(preferences), customUnit(preferences))
            }
            trimmed.startsWith("custom:") -> {
                val rest = trimmed.removePrefix("custom:")
                parseCustomDurationAndUnit(rest) ?: (24L * 3_600_000L)
            }
            else -> {
                parseCustomDurationAndUnit(trimmed) ?: (24L * 3_600_000L)
            }
        }
    }

    fun getExpiry(preferences: SharedPreferences, itemId: String): Long? =
        if (preferences.contains(expiryKey(itemId))) preferences.getLong(expiryKey(itemId), -1L).takeIf { it > 0L } else null

    fun setExpiry(preferences: SharedPreferences, itemId: String, expiresAtMillis: Long) {
        if (expiresAtMillis > 0L) {
            preferences.edit().putLong(expiryKey(itemId), expiresAtMillis).commit()
        } else {
            removeExpiry(preferences, itemId)
        }
    }

    fun hasFiniteRetention(preferences: SharedPreferences, value: String = mode(preferences)): Boolean =
        parseRetentionMillis(value, preferences) != null

    fun recalculateExpiryOnUnlock(
        preferences: SharedPreferences,
        itemId: String,
        mode: String = mode(preferences),
        now: Long = System.currentTimeMillis()
    ): Long? {
        val durationMs = parseRetentionMillis(mode, preferences)
        val editor = preferences.edit()
        val nextExpiryAt = if (durationMs == null) {
            editor.remove(expiryKey(itemId))
            null
        } else {
            val target = now + durationMs
            editor.putLong(expiryKey(itemId), target)
            target
        }
        editor.commit()
        return nextExpiryAt
    }

    fun markCaptured(preferences: SharedPreferences, itemId: String, value: String, now: Long = System.currentTimeMillis()) {
        val expiry = parseRetentionMillis(value, preferences)
        val editor = preferences.edit()
        if (expiry == null) editor.remove(expiryKey(itemId))
        else editor.putLong(expiryKey(itemId), now + expiry)
        editor.commit()
    }

    fun removeExpiry(preferences: SharedPreferences, itemId: String) {
        preferences.edit().remove(expiryKey(itemId)).commit()
    }

    fun clearExpiryLedger(preferences: SharedPreferences) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(EXPIRY_PREFIX) }.forEach(editor::remove)
        editor.commit()
    }

    fun nextExpiry(preferences: SharedPreferences): Long? = preferences.all
        .asSequence()
        .filter { it.key.startsWith(EXPIRY_PREFIX) && it.value is Long }
        .mapNotNull { it.value as? Long }
        .minOrNull()

    @Synchronized
    fun cleanupExpired(store: ClipStore, preferences: SharedPreferences, now: Long = System.currentTimeMillis()): Int {
        val deletedIds = mutableListOf<String>()
        val editor = preferences.edit()
        val currentMode = mode(preferences)
        val finiteDuration = parseRetentionMillis(currentMode, preferences)

        // Backfill/reconcile any items missing expiry metadata if finite retention is active
        if (finiteDuration != null) {
            for (item in store.listItems()) {
                if (item.pinned || item.isExpiryLocked) continue
                val expKey = expiryKey(item.id)
                if (!preferences.contains(expKey)) {
                    val assignedExpiry = item.createdAt + finiteDuration
                    editor.putLong(expKey, assignedExpiry)
                }
            }
            editor.commit()
        }

        preferences.all.forEach { (key, value) ->
            if (!key.startsWith(EXPIRY_PREFIX) || value !is Long) return@forEach
            val id = key.removePrefix(EXPIRY_PREFIX)
            val existing = store.getItem(id)
            if (existing != null && (existing.pinned || existing.isExpiryLocked)) {
                editor.remove(key)
                return@forEach
            }
            if (value <= now) {
                if (existing == null || runCatching { store.delete(id) }.getOrDefault(false)) {
                    if (existing != null) deletedIds.add(id)
                    editor.remove(key)
                }
            }
        }
        editor.commit()
        if (deletedIds.isNotEmpty()) {
            onItemsDeleted(preferences, deletedIds)
        }
        return deletedIds.size
    }

    private fun expiryKey(id: String) = "$EXPIRY_PREFIX$id"
}
