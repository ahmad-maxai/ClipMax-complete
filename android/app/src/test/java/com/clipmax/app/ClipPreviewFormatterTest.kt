package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPreviewFormatterTest {

    @Test
    fun testGenericTitlesReplacedWithActualContent() {
        assertTrue(ClipPreviewFormatter.isGenericTitle("Copy"))
        assertTrue(ClipPreviewFormatter.isGenericTitle("Select"))
        assertTrue(ClipPreviewFormatter.isGenericTitle("Selection"))
        assertTrue(ClipPreviewFormatter.isGenericTitle("Clip"))
        assertFalse(ClipPreviewFormatter.isGenericTitle("Project Notes"))

        assertEquals(
            "Meeting tomorrow",
            ClipPreviewFormatter.resolveChipPreview("Copy", "Meeting tomorrow")
        )
        assertEquals(
            "Hello, how are you?",
            ClipPreviewFormatter.resolveChipPreview("Select", "Hello, how are you?")
        )
    }

    @Test
    fun testEmptyAndWhitespaceHandledGracefully() {
        assertEquals(
            "(empty clip)",
            ClipPreviewFormatter.resolveChipPreview("Copy", "")
        )
        assertEquals(
            "(blank spaces)",
            ClipPreviewFormatter.resolveChipPreview("Copy", "   \n\t   ")
        )
    }

    @Test
    fun testMultilineTextNormalizedToSingleCompactLine() {
        val multiline = "  First line\n\n\tSecond   line\r\nThird line  "
        assertEquals(
            "First line Second line Third line",
            ClipPreviewFormatter.resolveChipPreview("Copy", multiline)
        )
    }

    @Test
    fun testLongTextAndUrlTruncateWithEllipsis() {
        val url = "https://example.com/very/long/path/with/query?token=abcdef123456"
        val truncatedUrl = ClipPreviewFormatter.resolveChipPreview("Copy", url, maxChars = 24)
        assertTrue(truncatedUrl.startsWith("https://example.com/"))
        assertTrue(truncatedUrl.endsWith("…"))
        assertTrue(truncatedUrl.length <= 24)

        val longText = "This is a much longer paragraph copied from an article about Android IME."
        val truncatedText = ClipPreviewFormatter.resolveChipPreview("Copy", longText, maxChars = 30)
        assertTrue(truncatedText.endsWith("…"))
        assertTrue(truncatedText.length <= 30)
    }

    @Test
    fun testPasswordTargetNeverExposesPreview() {
        assertEquals(
            "",
            ClipPreviewFormatter.resolveChipPreview("Copy", "SecretPassword123", isPasswordTarget = true)
        )
    }
}
