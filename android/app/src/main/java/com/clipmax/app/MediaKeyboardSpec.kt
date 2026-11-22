package com.clipmax.app

import java.util.Locale

/**
 * Pure JVM specification for ClipMax's 4-mode native media keyboard (Emoji, GIFs, Stickers,
 * Kaomoji): mode selector state, search filtering, recent item history persistence, Tenor key
 * resolution, and rich-content MIME compatibility policy.
 */
internal object MediaKeyboardSpec {

    enum class MediaSubMode {
        EMOJI,
        GIFS,
        STICKERS,
        KAOMOJI
    }

    const val PREF_MEDIA_FILE = "clipmax_media_prefs"
    const val PREF_TENOR_API_KEY = "tenor_api_key"
    const val PREF_RECENT_STICKERS = "recent_stickers"
    const val PREF_RECENT_KAOMOJI = "recent_kaomoji"
    const val PREF_RECENT_GIFS = "recent_gifs"
    const val MAX_RECENT_MEDIA = 24

    const val TOP_BAR_HEIGHT_PORTRAIT_DP = 44f
    const val TOP_BAR_HEIGHT_LANDSCAPE_DP = 38f
    const val BOTTOM_BAR_HEIGHT_PORTRAIT_DP = 44f
    const val BOTTOM_BAR_HEIGHT_LANDSCAPE_DP = 38f

    const val STICKER_COLS_PORTRAIT = 4
    const val STICKER_COLS_LANDSCAPE = 6
    const val KAOMOJI_COLS_PORTRAIT = 4
    const val KAOMOJI_COLS_LANDSCAPE = 6
    const val GIF_COLS_PORTRAIT = 2
    const val GIF_COLS_LANDSCAPE = 3

    object TenorApiKeyConfig {
        fun resolveEffectiveKey(prefKey: String?, resourceKey: String?): String {
            val fromPref = prefKey?.trim().orEmpty()
            if (fromPref.isNotEmpty()) return fromPref
            val fromRes = resourceKey?.trim().orEmpty()
            if (fromRes.isNotEmpty()) return fromRes
            return ""
        }

        fun isConfigured(effectiveKey: String?): Boolean = !effectiveKey.isNullOrBlank()

        fun maskKey(key: String?): String {
            val trimmed = key?.trim().orEmpty()
            if (trimmed.isEmpty()) return ""
            if (trimmed.length <= 8) return "••••" + trimmed.takeLast(2)
            return trimmed.take(4) + "••••" + trimmed.takeLast(4)
        }
    }

    object MediaRichContentPolicy {
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

        fun cacheFileNameForSticker(item: StickerCatalog.StickerItem): String {
            val safeId = item.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return "sticker_${safeId}.png"
        }

        fun cacheFileNameForGif(item: GifProvider.GifItem, isPreview: Boolean): String {
            val safeId = item.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return if (isPreview) "gif_prev_${safeId}.gif" else "gif_full_${safeId}.gif"
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
            val allById = StickerCatalog.PACKS.flatMap { it.items }.associateBy { it.id }
            return ids.mapNotNull { allById[it] }
        }

        fun record(stickerId: String) {
            if (stickerId.isBlank()) return
            ids.remove(stickerId)
            ids.add(0, stickerId)
            while (ids.size > maxEntries) {
                ids.removeAt(ids.lastIndex)
            }
        }

        fun encode(): String = ids.joinToString("\n")

        companion object {
            fun decode(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_RECENT_MEDIA)
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

    class RecentGifHistory(
        initial: List<GifProvider.GifItem> = emptyList(),
        private val maxEntries: Int = MAX_RECENT_MEDIA
    ) {
        private val entries = ArrayList<GifProvider.GifItem>().apply {
            initial.distinctBy { it.id }.take(maxEntries).forEach { add(it) }
        }

        fun items(): List<GifProvider.GifItem> = entries.toList()

        fun record(item: GifProvider.GifItem) {
            entries.removeAll { it.id == item.id }
            entries.add(0, item)
            while (entries.size > maxEntries) {
                entries.removeAt(entries.lastIndex)
            }
        }

        fun encode(): String = entries.joinToString("\n") { item ->
            val safeTitle = item.title.replace("\t", " ").replace("\n", " ")
            listOf(item.id, safeTitle, item.previewUrl, item.gifUrl, item.width.toString(), item.height.toString())
                .joinToString("\t")
        }

        companion object {
            fun decode(raw: String?): List<GifProvider.GifItem> {
                if (raw.isNullOrBlank()) return emptyList()
                val out = ArrayList<GifProvider.GifItem>()
                for (line in raw.split('\n')) {
                    if (line.isBlank()) continue
                    val parts = line.split('\t')
                    if (parts.size < 6) continue
                    val id = parts[0].trim()
                    val title = parts[1].trim().ifEmpty { "GIF" }
                    val prev = parts[2].trim()
                    val full = parts[3].trim()
                    val w = parts[4].toIntOrNull()?.takeIf { it > 0 } ?: 320
                    val h = parts[5].toIntOrNull()?.takeIf { it > 0 } ?: 240
                    if (id.isNotEmpty() && prev.startsWith("https://") && full.startsWith("https://")) {
                        out.add(GifProvider.GifItem(id, title, prev, full, w, h))
                    }
                }
                return out.distinctBy { it.id }.take(MAX_RECENT_MEDIA)
            }
        }
    }
}
