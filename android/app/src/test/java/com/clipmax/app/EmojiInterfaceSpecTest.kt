package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiInterfaceSpecTest {

    private fun assertNoUnpairedSurrogates(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch)) {
                assertTrue("Unpaired high surrogate at index $i", i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
                i += 2
            } else {
                assertFalse("Unpaired low surrogate at index $i", Character.isLowSurrogate(ch))
                assertNotEquals("Replacement char found", '\uFFFD', ch)
                i += 1
            }
        }
    }

    @Test
    fun testDeletesSupplementaryEmojiWithoutSurrogateFragment() {
        val sample = "\uD83D\uDE0E\uD83E\uDD13" // 😎🤓
        assertEquals(2, EmojiInterfaceSpec.lastGraphemeUtf16Length(sample))
        val afterFirst = EmojiInterfaceSpec.dropLastGrapheme(sample)
        assertEquals("\uD83D\uDE0E", afterFirst)
        assertNoUnpairedSurrogates(afterFirst)
        val afterSecond = EmojiInterfaceSpec.dropLastGrapheme(afterFirst)
        assertEquals("", afterSecond)
    }

    @Test
    fun testDeletesVariationSelectorAndKeycapEmojisIntact() {
        val heart = "\u2764\uFE0F" // ❤️
        val keycap = "1\uFE0F\u20E3" // 1️⃣
        assertEquals(heart.length, EmojiInterfaceSpec.lastGraphemeUtf16Length(heart))
        assertEquals("", EmojiInterfaceSpec.dropLastGrapheme(heart))
        assertEquals(keycap.length, EmojiInterfaceSpec.lastGraphemeUtf16Length("A$keycap") - 0)
        assertEquals("A", EmojiInterfaceSpec.dropLastGrapheme("A$keycap"))
    }

    @Test
    fun testDeletesSkinToneModifiedEmojiAsOneCluster() {
        val waveLight = "\uD83D\uDC4B\uD83C\uDFFB" // 👋🏻
        val thumbsMed = "\uD83D\uDC4D\uD83C\uDFFD" // 👍🏽
        val combined = "$waveLight$thumbsMed"
        assertEquals(thumbsMed.length, EmojiInterfaceSpec.lastGraphemeUtf16Length(combined))
        val afterFirst = EmojiInterfaceSpec.dropLastGrapheme(combined)
        assertEquals(waveLight, afterFirst)
        assertNoUnpairedSurrogates(afterFirst)
        assertEquals("", EmojiInterfaceSpec.dropLastGrapheme(afterFirst))
    }

    @Test
    fun testDeletesJoinedZwjSequencesAndCountryFlagsIntact() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" // 👨‍👩‍👧‍👦
        val dev = "\uD83E\uDDD1\u200D\uD83D\uDCBB" // 🧑‍💻
        val rainbowFlag = "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08" // 🏳️‍🌈
        val usFlag = "\uD83C\uDDFA\uD83C\uDDF8" // 🇺🇸
        val ngFlag = "\uD83C\uDDF3\uD83C\uDDEC" // 🇳🇬

        assertEquals(family.length, EmojiInterfaceSpec.lastGraphemeUtf16Length("hi $family"))
        assertEquals("hi ", EmojiInterfaceSpec.dropLastGrapheme("hi $family"))

        assertEquals(dev.length, EmojiInterfaceSpec.lastGraphemeUtf16Length(dev))
        assertEquals("", EmojiInterfaceSpec.dropLastGrapheme(dev))

        assertEquals(rainbowFlag.length, EmojiInterfaceSpec.lastGraphemeUtf16Length(rainbowFlag))
        assertEquals("", EmojiInterfaceSpec.dropLastGrapheme(rainbowFlag))

        val twoFlags = "$usFlag$ngFlag"
        assertEquals(ngFlag.length, EmojiInterfaceSpec.lastGraphemeUtf16Length(twoFlags))
        val afterOneFlag = EmojiInterfaceSpec.dropLastGrapheme(twoFlags)
        assertEquals(usFlag, afterOneFlag)
        assertNoUnpairedSurrogates(afterOneFlag)
        assertEquals("", EmojiInterfaceSpec.dropLastGrapheme(afterOneFlag))
    }

    @Test
    fun testDeletesCrlfAndAsciiCleanly() {
        assertEquals(2, EmojiInterfaceSpec.lastGraphemeUtf16Length("abc\r\n"))
        assertEquals("abc", EmojiInterfaceSpec.dropLastGrapheme("abc\r\n"))
        assertEquals(1, EmojiInterfaceSpec.lastGraphemeUtf16Length("abc"))
        assertEquals("ab", EmojiInterfaceSpec.dropLastGrapheme("abc"))
        assertEquals(0, EmojiInterfaceSpec.lastGraphemeUtf16Length(""))
        assertEquals("", EmojiInterfaceSpec.dropLastGrapheme(""))
    }

    @Test
    fun testRecentEmojiHistoryRecordsRemovesAndPreservesRemainingOrder() {
        val history = EmojiInterfaceSpec.RecentEmojiHistory(maxEntries = 4)
        history.record("😀")
        history.record("😎")
        history.record("👍🏽")
        history.record("❤️")
        assertEquals(listOf("❤️", "👍🏽", "😎", "😀"), history.items())

        // Re-recording moves existing emoji to front without duplication
        history.record("😎")
        assertEquals(listOf("😎", "❤️", "👍🏽", "😀"), history.items())

        // Long-press Remove from recents deletes only target emoji, preserving remaining recents
        assertTrue(history.remove("❤️"))
        assertFalse(history.remove("🚀"))
        assertEquals(listOf("😎", "👍🏽", "😀"), history.items())

        // Persistence round-trip preserves order and removes blanks
        val encoded = history.encode()
        val restored = EmojiInterfaceSpec.RecentEmojiHistory(EmojiInterfaceSpec.RecentEmojiHistory.decode(encoded))
        assertEquals(listOf("😎", "👍🏽", "😀"), restored.items())
    }

    @Test
    fun testEmojiGridSevenColumnsAndSpacedNavigationProportions() {
        assertEquals(7, EmojiInterfaceSpec.PORTRAIT_COLUMNS)
        assertEquals(7, EmojiInterfaceSpec.LANDSCAPE_COLUMNS)
        assertTrue(EmojiInterfaceSpec.TAB_WIDTH_DP >= 40f)
        assertTrue(EmojiInterfaceSpec.TAB_GAP_DP >= 6f)
        assertTrue(EmojiInterfaceSpec.CELL_HEIGHT_PORTRAIT_DP >= 44f)
        assertTrue(EmojiInterfaceSpec.CELL_TEXT_SIZE_SP >= 28f)
        assertTrue(EmojiInterfaceSpec.NAV_HEADER_HEIGHT_PORTRAIT_DP >= 40f)
    }
}
