package com.clipmax.app

/**
 * Pure JVM specification and Unicode helpers for ClipMax Emoji, Sticker, GIF and Kaomoji interface:
 * grapheme cluster deletion, recent history management for emoji, stickers, GIFs, and kaomojis,
 * and grid/navigation proportions.
 */
internal object EmojiInterfaceSpec {
    const val PREF_FILE = "clipmax_emoji_prefs"
    const val PREF_RECENT_EMOJIS = "recent_emojis"
    const val PREF_RECENT_STICKERS = "recent_stickers"
    const val PREF_RECENT_GIFS = "recent_gifs"
    const val PREF_RECENT_KAOMOJIS = "recent_kaomojis"
    const val MAX_RECENT_EMOJIS = 28
    const val MAX_RECENT_ITEMS = 24

    const val PORTRAIT_COLUMNS = 7
    const val LANDSCAPE_COLUMNS = 7

    const val NAV_HEADER_HEIGHT_PORTRAIT_DP = 42f
    const val NAV_HEADER_HEIGHT_LANDSCAPE_DP = 36f
    const val TAB_WIDTH_DP = 40f
    const val TAB_GAP_DP = 6f
    const val TAB_ICON_SIZE_DP = 17.5f
    const val BACKSPACE_WIDTH_DP = 44f
    const val BACKSPACE_ICON_SIZE_DP = 18f

    const val CELL_HEIGHT_PORTRAIT_DP = 46f
    const val CELL_HEIGHT_LANDSCAPE_DP = 38f
    const val CELL_TEXT_SIZE_SP = 30f
    const val CELL_ROW_GAP_DP = 6f

    private const val ZWJ = 0x200D
    private const val VARIATION_SELECTOR_TEXT = 0xFE0E
    private const val VARIATION_SELECTOR_EMOJI = 0xFE0F
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FFE
    private fun isSkinToneModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF
    private fun isTagChar(cp: Int): Boolean = cp in 0xE0020..0xE007FE

    private fun isEmojiPresentationExtend(cp: Int): Boolean {
        if (cp == VARIATION_SELECTOR_TEXT || cp == VARIATION_SELECTOR_EMOJI || cp == COMBINING_ENCLOSING_KEYCAP) {
            return true
        }
        if (isSkinToneModifier(cp) || isTagChar(cp)) return true
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun lastGraphemeUtf16Length(text: CharSequence?): Int {
        if (text == null || text.isEmpty()) return 0
        val len = text.length
        var idx = len
        if (idx >= 2 && text[idx - 1] == '\n' && text[idx - 2] == '\r') {
            return 2
        }
        val lastCp = Character.codePointBefore(text, idx)
        if (isRegionalIndicator(lastCp)) {
            var riCount = 0
            var scan = idx
            while (scan > 0) {
                val cp = Character.codePointBefore(text, scan)
                if (!isRegionalIndicator(cp)) break
                riCount++
                scan -= Character.charCount(cp)
            }
            return if (riCount >= 2 && riCount % 2 == 0) {
                val firstLen = Character.charCount(lastCp)
                val prevCp = Character.codePointBefore(text, idx - firstLen)
                firstLen + Character.charCount(prevCp)
            } else {
                Character.charCount(lastCp)
            }
        }
        while (idx > 0) {
            while (idx > 0) {
                val cp = Character.codePointBefore(text, idx)
                if (!isEmojiPresentationExtend(cp)) break
                idx -= Character.charCount(cp)
            }
            if (idx <= 0) break
            val baseCp = Character.codePointBefore(text, idx)
            idx -= Character.charCount(baseCp)
            if (idx > 0 && text[idx - 1].code == ZWJ) {
                idx -= 1
                continue
            }
            break
        }
        return (len - idx).coerceAtLeast(1)
    }

    fun dropLastGrapheme(text: String): String {
        if (text.isEmpty()) return text
        val count = lastGraphemeUtf16Length(text).coerceIn(1, text.length)
        return text.substring(0, text.length - count)
    }

    class RecentEmojiHistory(
        initial: List<String> = emptyList(),
        private val maxEntries: Int = MAX_RECENT_EMOJIS
    ) {
        private val entries = ArrayList<String>().apply {
            initial.filter { it.isNotBlank() }.distinct().take(maxEntries).forEach { add(it) }
        }

        fun items(): List<String> = entries.toList()

        fun record(emoji: String) {
            if (emoji.isBlank()) return
            entries.remove(emoji)
            entries.add(0, emoji)
            while (entries.size > maxEntries) {
                entries.removeAt(entries.lastIndex)
            }
        }

        fun remove(emoji: String): Boolean = entries.remove(emoji)

        fun encode(): String = entries.joinToString("\n")

        companion object {
            fun decode(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_RECENT_EMOJIS)
            }
        }
    }

    class RecentIdHistory(
        initial: List<String> = emptyList(),
        private val maxEntries: Int = MAX_RECENT_ITEMS
    ) {
        private val entries = ArrayList<String>().apply {
            initial.filter { it.isNotBlank() }.distinct().take(maxEntries).forEach { add(it) }
        }

        fun items(): List<String> = entries.toList()

        fun record(id: String) {
            if (id.isBlank()) return
            entries.remove(id)
            entries.add(0, id)
            while (entries.size > maxEntries) {
                entries.removeAt(entries.lastIndex)
            }
        }

        fun remove(id: String): Boolean = entries.remove(id)

        fun encode(): String = entries.joinToString(",")

        companion object {
            fun decode(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_RECENT_ITEMS)
            }
        }
    }

    class ContinuousScrollTabController(initialTabIdx: Int = 0) {
        var activeTabIdx: Int = initialTabIdx.coerceAtLeast(0)
            private set
        private var programmaticTargetIdx: Int? = null
        private var programmaticTargetScrollY: Int = 0
        private var programmaticStartedAtMs: Long = 0L

        fun reset(initialTabIdx: Int = 0) {
            activeTabIdx = initialTabIdx.coerceAtLeast(0)
            programmaticTargetIdx = null
            programmaticTargetScrollY = 0
            programmaticStartedAtMs = 0L
        }

        fun onTabSelected(tabIdx: Int, targetScrollY: Int, nowMs: Long = 0L): Int {
            val clamped = tabIdx.coerceAtLeast(0)
            activeTabIdx = clamped
            programmaticTargetIdx = clamped
            programmaticTargetScrollY = targetScrollY.coerceAtLeast(0)
            programmaticStartedAtMs = nowMs
            return activeTabIdx
        }

        fun onUserTouchInteraction() {
            programmaticTargetIdx = null
        }

        fun onVerticalScroll(
            sectionTops: List<Int>,
            scrollY: Int,
            bottomReached: Boolean,
            anchorOffsetPx: Int,
            settleTolerancePx: Int,
            nowMs: Long = 0L
        ): Boolean {
            if (sectionTops.isEmpty()) return false
            val targetIdx = programmaticTargetIdx
            if (targetIdx != null) {
                val reachedTarget = kotlin.math.abs(scrollY - programmaticTargetScrollY) <= settleTolerancePx ||
                    (bottomReached && targetIdx == sectionTops.lastIndex) ||
                    (nowMs > programmaticStartedAtMs && nowMs - programmaticStartedAtMs >= 650L)
                if (!reachedTarget) {
                    return false
                }
                programmaticTargetIdx = null
            }
            val resolved = resolveScrolledSectionIndex(
                sectionTops = sectionTops,
                scrollY = scrollY,
                bottomReached = bottomReached,
                anchorOffsetPx = anchorOffsetPx
            )
            if (resolved != activeTabIdx) {
                activeTabIdx = resolved
                return true
            }
            return false
        }

        companion object {
            fun resolveScrolledSectionIndex(
                sectionTops: List<Int>,
                scrollY: Int,
                bottomReached: Boolean,
                anchorOffsetPx: Int
            ): Int {
                if (sectionTops.isEmpty()) return 0
                if (bottomReached) return sectionTops.lastIndex
                var bestIdx = 0
                for (i in sectionTops.indices) {
                    if (sectionTops[i] <= scrollY + anchorOffsetPx) {
                        bestIdx = i
                    }
                }
                return bestIdx
            }
        }
    }
}
