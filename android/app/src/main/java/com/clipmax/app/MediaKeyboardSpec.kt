package com.clipmax.app

import android.text.InputType
import java.util.Locale

/**
 * Pure JVM specification for ClipMax's 3-mode native media keyboard (Emoji, Stickers,
 * Kaomoji): mode selector state, favorites/recents persistence, installed sticker pack policy,
 * field eligibility checks, and rich-content MIME compatibility policy.
 */
internal object MediaKeyboardSpec {

    enum class MediaSubMode {
        EMOJI,
        STICKERS,
        KAOMOJI
    }

    const val PREF_MEDIA_FILE = "clipmax_media_prefs"
    const val PREF_RECENT_STICKERS = "recent_stickers"
    const val PREF_FAVORITE_STICKERS = "favorite_stickers"
    const val PREF_INSTALLED_STICKER_PACKS = "installed_sticker_packs"
    const val PREF_STICKER_PACKS_INIT = "sticker_packs_v2_initialized"
    const val PREF_DELETED_STICKERS = "deleted_stickers"
    const val PREF_RECENT_KAOMOJI = "recent_kaomoji"
    const val MAX_RECENT_MEDIA = 24
    const val MAX_FAVORITE_STICKERS = 64

    const val TOP_BAR_HEIGHT_PORTRAIT_DP = 44f
    const val TOP_BAR_HEIGHT_LANDSCAPE_DP = 38f
    const val BOTTOM_BAR_HEIGHT_PORTRAIT_DP = 44f
    const val BOTTOM_BAR_HEIGHT_LANDSCAPE_DP = 38f

    const val STICKER_COLS_PORTRAIT = 4
    const val STICKER_COLS_LANDSCAPE = 6
    const val KAOMOJI_COLS_PORTRAIT = 4
    const val KAOMOJI_COLS_LANDSCAPE = 6

    object StickerPackStorePolicy {
        fun encodeInstalledIds(ids: Collection<String>): String =
            ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

        fun decodeInstalledIds(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val validIds = StickerCatalog.PACKS.map { it.packId }.toSet()
            return raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in validIds }
                .distinct()
        }

        fun formatStorageSize(bytes: Long): String {
            if (bytes <= 0L) return "0 KB"
            val kb = bytes / 1024.0
            return if (kb < 1024.0) {
                "${kotlin.math.round(kb).toLong().coerceAtLeast(1)} KB"
            } else {
                String.format(Locale.US, "%.1f MB", kb / 1024.0)
            }
        }
    }

    object MediaRichContentPolicy {
        enum class MediaFallbackOutcome {
            ELIGIBLE,
            NO_ACTIVE_FIELD,
            UNSUPPORTED_FIELD,
            UNSUPPORTED_MIME
        }

        fun isEligibleRichMediaEditor(
            inputType: Int,
            isPasswordTarget: Boolean
        ): Boolean {
            if (isPasswordTarget) return false
            val clazz = inputType and InputType.TYPE_MASK_CLASS
            if (clazz == InputType.TYPE_CLASS_NUMBER ||
                clazz == InputType.TYPE_CLASS_PHONE ||
                clazz == InputType.TYPE_CLASS_DATETIME
            ) {
                return false
            }
            if (clazz == InputType.TYPE_CLASS_TEXT) {
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI ||
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                ) {
                    return false
                }
            }
            return true
        }

        fun supportsMimeType(acceptedMimeTypes: Array<out String>?, targetMime: String): Boolean {
            if (acceptedMimeTypes == null || acceptedMimeTypes.isEmpty()) return false
            val normTarget = targetMime.trim().lowercase(Locale.ROOT)
            val slashIdx = normTarget.indexOf('/')
            if (slashIdx <= 0) return false
            val targetType = normTarget.substring(0, slashIdx)

            for (rawPattern in acceptedMimeTypes) {
                val pattern = rawPattern.trim().lowercase(Locale.ROOT)
                if (pattern == "*/*" || pattern == normTarget) return true
                if (pattern == "$targetType/*") return true
            }
            return false
        }

        /**
         * Resolves best sticker commit MIME type: prefers `image/png`, falls back to `image/webp`
         * for hosts (such as WhatsApp sticker/media composers) that advertise WebP over PNG.
         */
        fun resolveBestStickerMime(acceptedMimeTypes: Array<out String>?): String? {
            if (supportsMimeType(acceptedMimeTypes, "image/png")) return "image/png"
            if (supportsMimeType(acceptedMimeTypes, "image/webp")) return "image/webp"
            return null
        }

        fun evaluateMediaEligibility(
            hasActiveConnection: Boolean,
            inputType: Int,
            isPasswordTarget: Boolean,
            acceptedMimeTypes: Array<out String>?
        ): MediaFallbackOutcome {
            if (!hasActiveConnection) return MediaFallbackOutcome.NO_ACTIVE_FIELD
            if (!isEligibleRichMediaEditor(inputType, isPasswordTarget)) {
                return MediaFallbackOutcome.UNSUPPORTED_FIELD
            }
            val mime = resolveBestStickerMime(acceptedMimeTypes)
            return if (mime != null) MediaFallbackOutcome.ELIGIBLE else MediaFallbackOutcome.UNSUPPORTED_MIME
        }

        fun cacheFileNameForSticker(item: StickerCatalog.StickerItem, mimeType: String = "image/png"): String {
            val safeId = item.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return if (mimeType == "image/webp") "sticker_${safeId}.webp" else "sticker_${safeId}.png"
        }
    }

    class RecentStickerHistory(
        initialIds: List<String> = emptyList(),
        private val maxEntries: Int = MAX_RECENT_MEDIA
    ) {
        private val ids = ArrayList<String>().apply {
            initialIds.filter { it.isNotBlank() }.distinct().take(maxEntries).forEach { add(it) }
        }

        fun items(): List<StickerCatalog.StickerItem> {
            return ids.mapNotNull { StickerCatalog.findById(it) }
        }

        fun record(stickerId: String) {
            if (stickerId.isBlank() || StickerCatalog.findById(stickerId) == null) return
            ids.remove(stickerId)
            ids.add(0, stickerId)
            while (ids.size > maxEntries) {
                ids.removeAt(ids.lastIndex)
            }
        }

        fun remove(stickerId: String): Boolean {
            return ids.remove(stickerId)
        }

        fun encode(): String = ids.joinToString("\n")

        companion object {
            fun decode(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_RECENT_MEDIA)
            }
        }
    }

    class StickerFavoritesHistory(
        initialIds: List<String> = emptyList(),
        private val maxEntries: Int = MAX_FAVORITE_STICKERS
    ) {
        private val ids = ArrayList<String>().apply {
            initialIds
                .filter { it.isNotBlank() && StickerCatalog.findById(it) != null }
                .distinct()
                .take(maxEntries)
                .forEach { add(it) }
        }

        fun isFavorite(stickerId: String): Boolean = stickerId in ids

        fun toggle(stickerId: String): Boolean {
            if (stickerId.isBlank() || StickerCatalog.findById(stickerId) == null) return false
            return if (ids.remove(stickerId)) {
                false
            } else {
                ids.add(0, stickerId)
                while (ids.size > maxEntries) {
                    ids.removeAt(ids.lastIndex)
                }
                true
            }
        }

        fun remove(stickerId: String): Boolean = ids.remove(stickerId)

        fun items(): List<StickerCatalog.StickerItem> = ids.mapNotNull { StickerCatalog.findById(it) }

        fun encode(): String = ids.joinToString("\n")

        companion object {
            fun decode(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_FAVORITE_STICKERS)
            }
        }
    }

    /**
     * Pure JVM store tracking individual user-deleted sticker IDs so deleted stickers
     * stay removed across restarts without deleting their parent pack or sibling stickers.
     */
    class DeletedStickerStore(initialIds: Collection<String> = emptyList()) {
        private val ids = LinkedHashSet<String>().apply {
            initialIds
                .map { it.trim() }
                .filter { it.isNotEmpty() && StickerCatalog.findById(it) != null }
                .forEach { add(it) }
        }

        fun isDeleted(stickerId: String): Boolean = stickerId in ids

        fun deletedIds(): Set<String> = ids.toSet()

        fun markDeleted(stickerId: String): Boolean {
            if (stickerId.isBlank() || StickerCatalog.findById(stickerId) == null) return false
            return ids.add(stickerId)
        }

        fun encode(): String = ids.joinToString("\n")

        companion object {
            fun decode(raw: String?): Set<String> {
                if (raw.isNullOrBlank()) return emptySet()
                return raw.split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && StickerCatalog.findById(it) != null }
                    .toSet()
            }
        }
    }

    class RecentKaomojiHistory(
        initial: List<String> = emptyList(),
        private val maxEntries: Int = MAX_RECENT_MEDIA
    ) {
        private val entries = ArrayList<String>().apply {
            initial.filter { it.isNotBlank() }.distinct().take(maxEntries).forEach { add(it) }
        }

        fun items(): List<String> = entries.toList()

        fun record(kaomoji: String) {
            if (kaomoji.isBlank()) return
            entries.remove(kaomoji)
            entries.add(0, kaomoji)
            while (entries.size > maxEntries) {
                entries.removeAt(entries.lastIndex)
            }
        }

        fun encode(): String = entries.joinToString("\n")

        companion object {
            fun decode(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return raw.split('\n').filter { it.isNotBlank() }.distinct().take(MAX_RECENT_MEDIA)
            }
        }
    }
}
