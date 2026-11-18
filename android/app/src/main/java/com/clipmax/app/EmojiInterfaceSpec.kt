package com.clipmax.app

/**
 * Pure JVM specification and Unicode helpers for ClipMax Emoji interface: grapheme
 * cluster deletion, recent emoji history management, and grid/navigation proportions.
 */
internal object EmojiInterfaceSpec {
    const val PREF_FILE = "clipmax_emoji_prefs"
    const val PREF_RECENT_EMOJIS = "recent_emojis"
    const val MAX_RECENT_EMOJIS = 28

    const val PORTRAIT_COLUMNS = 7
    const val LANDSCAPE_COLUMNS = 7

    const val NAV_HEADER_HEIGHT_PORTRAIT_DP = 42f
    const val NAV_HEADER_HEIGHT_LANDSCAPE_DP = 36f
    const val TAB_WIDTH_DP = 42f
    const val TAB_GAP_DP = 6f
    const val TAB_ICON_SIZE_DP = 17.5f
    const val BACKSPACE_WIDTH_DP = 46f
    const val BACKSPACE_ICON_SIZE_DP = 18f

    const val CELL_HEIGHT_PORTRAIT_DP = 46f
    const val CELL_HEIGHT_LANDSCAPE_DP = 38f
    const val CELL_TEXT_SIZE_SP = 30f
    const val CELL_ROW_GAP_DP = 6f

    private const val ZWJ = 0x200D
    private const val VARIATION_SELECTOR_TEXT = 0xFE0E
    private const val VARIATION_SELECTOR_EMOJI = 0xFE0F
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun isSkinToneModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    private fun isTagChar(cp: Int): Boolean = cp in 0xE0020..0xE007F

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

    /**
     * Computes the exact UTF-16 char count of the trailing grapheme/emoji cluster before the cursor
     * so backspace never leaves an unpaired surrogate or broken multi-code-point emoji fragment.
     */
    fun lastGraphemeUtf16Length(text: CharSequence?): Int {
        if (text == null || text.isEmpty()) return 0
        val len = text.length
        var idx = len

        // 1. Handle CRLF newline pair as one unit
        if (idx >= 2 && text[idx - 1] == '\n' && text[idx - 2] == '\r') {
            return 2
        }

        // 2. Check trailing regional indicator flag pairs (e.g., 🇺🇸 = 2 RI code points = 4 UTF-16 chars)
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
            // Pair regional indicators from the start of the run; delete 2 if even count, 1 if odd
            return if (riCount >= 2 && riCount % 2 == 0) {
                val firstLen = Character.charCount(lastCp)
                val prevCp = Character.codePointBefore(text, idx - firstLen)
                firstLen + Character.charCount(prevCp)
            } else {
                Character.charCount(lastCp)
            }
        }

        // 3. Walk backward across combining marks, variation selectors, skin-tone modifiers, keycaps, tags, and ZWJ chains
        while (idx > 0) {
            // Consume any trailing extenders/modifiers attached to the current base
            while (idx > 0) {
                val cp = Character.codePointBefore(text, idx)
                if (!isEmojiPresentationExtend(cp)) break
                idx -= Character.charCount(cp)
            }
            if (idx <= 0) break

            // Consume the base code point
            val baseCp = Character.codePointBefore(text, idx)
            idx -= Character.charCount(baseCp)

            // If a ZWJ immediately precedes this base code point, consume the ZWJ and continue to previous component
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

    /**
     * Pure JVM recent emoji store managing MRU insertion and long-press deletion
     * without touching editor text or losing remaining history.
     */
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

        fun remove(emoji: String): Boolean {
            return entries.remove(emoji)
        }

        fun encode(): String = entries.joinToString("\n")

        companion object {
            fun decode(raw: String?): List<String> {
                if (raw.isNullOrBlank()) return emptyList()
                return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_RECENT_EMOJIS)
            }
        }
    }
}
