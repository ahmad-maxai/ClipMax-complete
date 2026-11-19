package com.clipmax.app

/**
 * Formats clipboard entries into readable, single-line preview labels for compact keyboard pills.
 * Pure JVM helper with no Android framework dependencies.
 */
internal object ClipPreviewFormatter {

    private val GENERIC_TITLES = setOf(
        "copy",
        "select",
        "selection",
        "clip",
        "saved clip",
        "untitled",
        "clipboard"
    )

    fun isGenericTitle(title: String?): Boolean {
        val cleaned = title?.trim().orEmpty()
        if (cleaned.isEmpty()) return true
        return GENERIC_TITLES.contains(cleaned.lowercase())
    }

    /**
     * Collapses newlines, tabs, and runs of whitespace into single spaces and truncates cleanly
     * with an ellipsis so multiline or oversized clips never expand keyboard pill height.
     */
    fun normalizePreviewText(rawText: String, maxChars: Int = 80): String {
        if (rawText.isEmpty() || maxChars <= 0) return ""
        val sb = StringBuilder(minOf(rawText.length, maxChars + 8))
        var pendingSpace = false
        var i = 0
        while (i < rawText.length && sb.length < maxChars + 4) {
            val ch = rawText[i]
            if (ch == '\r' || ch == '\n' || ch == '\t' || ch.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    pendingSpace = true
                }
            } else {
                if (pendingSpace && sb.length < maxChars + 4) {
                    sb.append(' ')
                }
                pendingSpace = false
                sb.append(ch)
            }
            i++
        }
        val collapsed = sb.toString().trim()
        if (collapsed.isEmpty()) return ""
        if (collapsed.length <= maxChars) return collapsed
        if (maxChars == 1) return "…"
        return collapsed.take(maxChars - 1).trimEnd() + "…"
    }

    /**
     * Resolves the pill preview text for a saved [ClipItem].
     * Prefers actual clipboard content over generic auto-capture labels ("Copy", "Select", etc.).
     */
    fun resolveChipPreview(
        baseTitle: String,
        rawSnippet: String,
        isPasswordTarget: Boolean = false,
        maxChars: Int = 80,
        blankSpacesLabel: String = "(blank spaces)",
        emptyClipLabel: String = "(empty clip)"
    ): String {
        if (isPasswordTarget) return ""
        val normalizedContent = normalizePreviewText(rawSnippet, maxChars)
        if (normalizedContent.isNotEmpty() && isGenericTitle(baseTitle)) {
            return normalizedContent
        }
        val normalizedTitle = normalizePreviewText(baseTitle, maxChars)
        if (!isGenericTitle(baseTitle) && normalizedTitle.isNotEmpty()) {
            return normalizedTitle
        }
        return when {
            normalizedContent.isNotEmpty() -> normalizedContent
            rawSnippet.isNotEmpty() -> blankSpacesLabel
            else -> emptyClipLabel
        }
    }
}
