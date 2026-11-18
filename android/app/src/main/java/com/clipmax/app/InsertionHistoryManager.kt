package com.clipmax.app

import java.util.ArrayDeque

/**
 * Tracks host-application text insertions (clipboard pastes and automatic spelling corrections)
 * completely separately from ClipStore's card Merge/Split history.
 *
 * Enforces strict surrounding-text and editor-identity verification before modifying any host
 * application text so moved cursors, manual edits, uncooperative InputConnections, or switched apps
 * never delete unrelated user text.
 */
internal class InsertionHistoryManager(
    private val maxHistorySize: Int = MAX_HISTORY_ENTRIES,
    private val maxTrackedChars: Int = MAX_TRACKED_INSERTION_CHARS
) {
    companion object {
        const val MAX_HISTORY_ENTRIES = 25
        const val MAX_TRACKED_INSERTION_CHARS = 500_000
        const val MAX_TOTAL_TRACKED_CHARS = 1_500_000
        const val ANCHOR_LENGTH = 48
        private val EMBEDDED_URL_REGEX = Regex("https?://[^\\s<>\\]]+", RegexOption.IGNORE_CASE)
    }

    enum class ActionKind {
        CLIP_INSERT,
        AUTOCORRECT
    }

    data class EditorTarget(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
        val sessionId: Long = 0L
    ) {
        fun isCompatibleWith(other: EditorTarget): Boolean {
            if (packageName != other.packageName) return false
            if (sessionId != other.sessionId) return false
            val thisClass = inputType and InputFieldPolicy.TYPE_MASK_CLASS
            val otherClass = other.inputType and InputFieldPolicy.TYPE_MASK_CLASS
            if (thisClass != 0 && otherClass != 0 && thisClass != otherClass) {
                return false
            }
            if (fieldId != 0 && other.fieldId != 0 && fieldId != other.fieldId) {
                return false
            }
            return true
        }
    }

    data class HistoryEntry(
        val kind: ActionKind,
        val target: EditorTarget,
        val originalText: String,
        val insertedText: String,
        val rawOriginalWord: String,
        val beforeAnchor: String,
        val afterAnchor: String
    )

    data class Availability(
        val allowed: Boolean,
        val reason: String? = null
    )

    data class OperationResult(
        val success: Boolean,
        val message: String,
        val entry: HistoryEntry? = null
    )

    interface HostEditorGateway {
        fun currentTarget(): EditorTarget
        fun isPasswordField(): Boolean
        fun hasActiveSelection(): Boolean
        fun textBeforeCursor(maxChars: Int): String?
        fun textAfterCursor(maxChars: Int): String?
        fun deleteBeforeCursor(length: Int): Boolean
        fun commitText(text: String): Boolean
        fun runBatchEdit(block: () -> Boolean): Boolean
    }

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var immediateBackspaceAutocorrectActive = false

    fun hasAnyUndoEntries(): Boolean = undoStack.isNotEmpty()
    fun hasAnyRedoEntries(): Boolean = redoStack.isNotEmpty()

    fun peekUndo(): HistoryEntry? = undoStack.peekFirst()
    fun peekRedo(): HistoryEntry? = redoStack.peekFirst()

    fun invalidateImmediateBackspaceRevert() {
        immediateBackspaceAutocorrectActive = false
    }

    fun clearForTargetChangeIfNeeded(newTarget: EditorTarget) {
        val topUndo = undoStack.peekFirst()
        val topRedo = redoStack.peekFirst()
        if ((topUndo != null && !topUndo.target.isCompatibleWith(newTarget)) ||
            (topRedo != null && !topRedo.target.isCompatibleWith(newTarget))
        ) {
            immediateBackspaceAutocorrectActive = false
        }
    }

    fun recordClipInsertion(
        target: EditorTarget,
        insertedText: String,
        beforeAnchor: String,
        afterAnchor: String
    ): Boolean {
        immediateBackspaceAutocorrectActive = false
        if (insertedText.isEmpty() || insertedText.length > maxTrackedChars) {
            return false
        }
        pushUndo(
            HistoryEntry(
                kind = ActionKind.CLIP_INSERT,
                target = target,
                originalText = "",
                insertedText = insertedText,
                rawOriginalWord = "",
                beforeAnchor = beforeAnchor.takeLast(ANCHOR_LENGTH),
                afterAnchor = afterAnchor.take(ANCHOR_LENGTH)
            )
        )
        redoStack.clear()
        return true
    }

    fun recordAutocorrect(
        target: EditorTarget,
        rawOriginalWord: String,
        correctedWord: String,
        delimiter: String,
        beforeAnchor: String,
        afterAnchor: String
    ): Boolean {
        val originalWithDelim = rawOriginalWord + delimiter
        val correctedWithDelim = correctedWord + delimiter
        if (rawOriginalWord.isEmpty() || correctedWithDelim.isEmpty() || correctedWithDelim.length > maxTrackedChars) {
            return false
        }
        pushUndo(
            HistoryEntry(
                kind = ActionKind.AUTOCORRECT,
                target = target,
                originalText = originalWithDelim,
                insertedText = correctedWithDelim,
                rawOriginalWord = rawOriginalWord,
                beforeAnchor = beforeAnchor.takeLast(ANCHOR_LENGTH),
                afterAnchor = afterAnchor.take(ANCHOR_LENGTH)
            )
        )
        redoStack.clear()
        immediateBackspaceAutocorrectActive = true
        return true
    }

    private fun normalizeLineEndings(text: String): String {
        if (!text.contains('\r')) return text
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun stripLineEndings(text: String): String {
        if (!text.contains('\n') && !text.contains('\r')) return text
        return text.replace("\r\n", "").replace("\r", "").replace("\n", "")
    }

    private fun isInvisibleFormattingChar(ch: Char): Boolean =
        ch == '\u200B' || ch == '\u200C' || ch == '\u200D' ||
            ch == '\uFEFF' || ch == '\u2060' || ch == '\uFFFC'

    private data class StrippedTextMap(
        val cleaned: String,
        val rawIndices: IntArray
    )

    private fun stripInvisibleWithMap(text: String): StrippedTextMap {
        var hasInvisible = false
        for (i in text.indices) {
            if (isInvisibleFormattingChar(text[i])) {
                hasInvisible = true
                break
            }
        }
        if (!hasInvisible) {
            val indices = IntArray(text.length + 1) { it }
            return StrippedTextMap(text, indices)
        }
        val sb = StringBuilder(text.length)
        val rawIndices = IntArray(text.length + 1)
        var cleanIdx = 0
        for (i in text.indices) {
            val ch = text[i]
            if (!isInvisibleFormattingChar(ch)) {
                sb.append(ch)
                rawIndices[cleanIdx++] = i
            }
        }
        rawIndices[cleanIdx] = text.length
        return StrippedTextMap(sb.toString(), rawIndices)
    }

    private fun normalizeHostSpacesAndLines(text: String, collapseNewlinesToSpace: Boolean = false): String {
        var out = normalizeLineEndings(text)
        if (out.indexOf('\u00A0') >= 0) {
            out = out.replace('\u00A0', ' ')
        }
        if (out.indexOf('\u2007') >= 0) {
            out = out.replace('\u2007', ' ')
        }
        if (out.indexOf('\u202F') >= 0) {
            out = out.replace('\u202F', ' ')
        }
        if (collapseNewlinesToSpace && out.indexOf('\n') >= 0) {
            out = out.replace('\n', ' ')
        }
        return out
    }

    private fun collapseWhitespaceSingleLine(text: String): String {
        val sb = StringBuilder(text.length)
        var prevSpace = false
        for (ch in normalizeHostSpacesAndLines(text, collapseNewlinesToSpace = true)) {
            if (ch == ' ' || ch == '\t' || ch == '\n') {
                if (!prevSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(ch)
                prevSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private fun isLikelyUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.contains("youtube.com", ignoreCase = true) ||
            t.contains("youtu.be", ignoreCase = true) ||
            t.contains("://")
    }

    private fun extractYoutubeVideoId(url: String): String? {
        val lower = url.lowercase()
        val youtuBeIdx = lower.indexOf("youtu.be/")
        if (youtuBeIdx >= 0) {
            val start = youtuBeIdx + "youtu.be/".length
            val end = url.indexOfAny(charArrayOf('?', '&', '#', '/', ' '), start).let { if (it < 0) url.length else it }
            val id = url.substring(start, end)
            if (id.isNotEmpty()) return id
        }
        if (lower.contains("youtube.com")) {
            for (marker in listOf("?v=", "&v=")) {
                val idx = lower.indexOf(marker)
                if (idx >= 0) {
                    val start = idx + marker.length
                    val end = url.indexOfAny(charArrayOf('&', '#', '/', ' '), start).let { if (it < 0) url.length else it }
                    val id = url.substring(start, end)
                    if (id.isNotEmpty()) return id
                }
            }
            for (pathMarker in listOf("/shorts/", "/live/", "/embed/")) {
                val idx = lower.indexOf(pathMarker)
                if (idx >= 0) {
                    val start = idx + pathMarker.length
                    val end = url.indexOfAny(charArrayOf('?', '&', '#', '/', ' '), start).let { if (it < 0) url.length else it }
                    val id = url.substring(start, end)
                    if (id.isNotEmpty()) return id
                }
            }
        }
        return null
    }

    private fun urlsReferToSameTarget(renderedUrl: String, pastedUrl: String): Boolean {
        val r = renderedUrl.trim().trimEnd('/')
        val p = pastedUrl.trim().trimEnd('/')
        if (r.equals(p, ignoreCase = true)) return true
        val rYt = extractYoutubeVideoId(r)
        val pYt = extractYoutubeVideoId(p)
        if (rYt != null && pYt != null && rYt == pYt) return true
        val rBase = stripSchemeAndWww(r).substringBefore('?').substringBefore('#').trimEnd('/')
        val pBase = stripSchemeAndWww(p).substringBefore('?').substringBefore('#').trimEnd('/')
        if (rBase.isNotEmpty() && rBase.equals(pBase, ignoreCase = true)) return true
        return false
    }

    private fun stripSchemeAndWww(url: String): String {
        var out = url
        if (out.startsWith("https://", ignoreCase = true)) {
            out = out.substring(8)
        } else if (out.startsWith("http://", ignoreCase = true)) {
            out = out.substring(7)
        }
        if (out.startsWith("www.", ignoreCase = true)) {
            out = out.substring(4)
        }
        return out
    }

    private fun lowercaseSchemeAndHost(url: String): String {
        val schemeEnd = url.indexOf("://")
        val hostStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val hostEnd = url.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let { if (it < 0) url.length else it }
        return url.substring(0, hostEnd).lowercase() + url.substring(hostEnd)
    }

    private fun buildUrlElisionCandidates(text: String): List<String> {
        val trimmed = text.trim()
        if (!isLikelyUrl(trimmed)) return emptyList()
        val out = LinkedHashSet<String>()
        val lowerHostUrl = lowercaseSchemeAndHost(trimmed)
        for (base in listOf(trimmed, lowerHostUrl)) {
            out.add(base)
            if (base.startsWith("https://", ignoreCase = true)) {
                out.add(base.substring(8))
            } else if (base.startsWith("http://", ignoreCase = true)) {
                out.add(base.substring(7))
            }
            val noSchemeAndWww = stripSchemeAndWww(base)
            if (noSchemeAndWww.isNotEmpty()) {
                out.add(noSchemeAndWww)
            }
        }
        val snapshot = out.toList()
        for (item in snapshot) {
            if (item.endsWith('/') && item.length > 1) {
                out.add(item.removeSuffix("/"))
            }
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun suffixMatchesAnchor(prefix: String, anchor: String): Boolean {
        if (anchor.isEmpty()) return true
        val rawExpected = if (prefix.length >= anchor.length) anchor else anchor.takeLast(prefix.length)
        if (rawExpected.isEmpty() || prefix.endsWith(rawExpected)) return true
        val normPrefix = normalizeHostSpacesAndLines(stripInvisibleWithMap(prefix).cleaned)
        val normAnchor = normalizeHostSpacesAndLines(stripInvisibleWithMap(anchor).cleaned)
        val normExpected = if (normPrefix.length >= normAnchor.length) normAnchor else normAnchor.takeLast(normPrefix.length)
        if (normExpected.isEmpty() || normPrefix.endsWith(normExpected)) return true
        if (normPrefix.indexOf('\n') < 0 && normAnchor.indexOf('\n') >= 0) {
            val singleAnchor = normalizeHostSpacesAndLines(normAnchor, collapseNewlinesToSpace = true)
            val singleExpected = if (normPrefix.length >= singleAnchor.length) singleAnchor else singleAnchor.takeLast(normPrefix.length)
            if (singleExpected.isEmpty() || normPrefix.endsWith(singleExpected)) return true
            val strippedAnchor = stripLineEndings(normAnchor)
            val strippedExpected = if (normPrefix.length >= strippedAnchor.length) strippedAnchor else strippedAnchor.takeLast(normPrefix.length)
            if (strippedExpected.isEmpty() || normPrefix.endsWith(strippedExpected)) return true
        }
        return false
    }

    private fun resolveUndoMatchedText(
        before: String,
        entry: HistoryEntry,
        after: String? = null
    ): String? {
        if (before.length >= entry.insertedText.length && before.endsWith(entry.insertedText)) {
            val prefix = before.substring(0, before.length - entry.insertedText.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return entry.insertedText
            }
        }
        val normInserted = normalizeLineEndings(entry.insertedText)
        if (normInserted != entry.insertedText && before.length >= normInserted.length && before.endsWith(normInserted)) {
            val prefix = before.substring(0, before.length - normInserted.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return normInserted
            }
        }

        // Check full-text host normalizations (spaces, single-line newline collapse/strip, trim, URL scheme elision)
        // against both raw `before` and invisible-char-stripped `cleanBefore`.
        val strippedMap = stripInvisibleWithMap(before)
        val cleanBefore = strippedMap.cleaned
        val normCleanBefore = normalizeHostSpacesAndLines(cleanBefore)
        val spaceNormInserted = normalizeHostSpacesAndLines(entry.insertedText)
        val singleLineEligible = cleanBefore.indexOf('\n') < 0 && entry.insertedText.indexOf('\n') >= 0
        val singleLineSpaceInserted = if (singleLineEligible) {
            normalizeHostSpacesAndLines(entry.insertedText, collapseNewlinesToSpace = true)
        } else null
        val singleLineStrippedInserted = if (singleLineEligible) {
            normalizeHostSpacesAndLines(stripLineEndings(entry.insertedText))
        } else null
        val collapsedWsInserted = if (entry.kind == ActionKind.CLIP_INSERT && cleanBefore.indexOf('\n') < 0) {
            collapseWhitespaceSingleLine(entry.insertedText).takeIf { it.isNotEmpty() && it != spaceNormInserted }
        } else null
        val trimmedInserted = if (entry.kind == ActionKind.CLIP_INSERT) {
            spaceNormInserted.trim().takeIf { it.isNotEmpty() && it != spaceNormInserted }
        } else null

        val exactCandidates = LinkedHashSet<String>().apply {
            add(entry.insertedText)
            add(normInserted)
            add(spaceNormInserted)
            singleLineSpaceInserted?.let {
                add(it)
                if (entry.kind == ActionKind.CLIP_INSERT) {
                    add(it.trim())
                }
            }
            singleLineStrippedInserted?.let {
                add(it)
                if (entry.kind == ActionKind.CLIP_INSERT) {
                    add(it.trim())
                }
            }
            collapsedWsInserted?.let { add(it) }
            trimmedInserted?.let { add(it) }
            if (entry.kind == ActionKind.CLIP_INSERT) {
                for (urlVar in buildUrlElisionCandidates(entry.insertedText)) {
                    add(urlVar)
                }
            }
        }.filter { it.isNotEmpty() }

        for (candidate in exactCandidates) {
            if (normCleanBefore.length >= candidate.length && normCleanBefore.endsWith(candidate)) {
                val startIdx = normCleanBefore.length - candidate.length
                val rawStart = rawStartAdjustingInvisible(before, strippedMap, startIdx, entry.beforeAnchor)
                val prefix = before.substring(0, rawStart)
                if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                    return before.substring(rawStart)
                }
            }
        }

        // Host editor trailing delimiter / auto-spacing / link normalization / Markdown wrapping fallback
        val trailingMatch = resolveUndoTrailingDelimiterMatch(before, strippedMap, entry, exactCandidates)
        if (trailingMatch != null) {
            return trailingMatch
        }

        // Embedded URL wrapped in Markdown within a multi-word paste (e.g. ChatGPT wrapping a pasted YouTube link
        // inside a sentence into [Title](url) while leaving the rest of the pasted text intact).
        val embeddedMarkdownMatch = resolveEmbeddedMarkdownUrlMatch(before, strippedMap, entry)
        if (embeddedMarkdownMatch != null) {
            return embeddedMarkdownMatch
        }

        // Truncated paste in constrained / compact search fields (e.g. YouTube search InputFilter.LengthFilter)
        // vs clamped surrounding-text buffer (e.g. Chrome/WebView returning last 512..2048 chars of a large paste).
        if (cleanBefore.isNotEmpty() && entry.kind == ActionKind.CLIP_INSERT) {
            val truncatedMatch = resolveSearchFieldPrefixTruncation(
                before = before,
                strippedMap = strippedMap,
                normCleanBefore = normCleanBefore,
                entry = entry,
                candidates = exactCandidates,
                after = after
            )
            if (truncatedMatch != null) {
                return truncatedMatch
            }
            if (truncatedMatch == null && isCursorMidPasteAgainstCandidate(normCleanBefore, entry, exactCandidates, after)) {
                return null
            }

            // Clamped buffer handling: only when the host returned a large window (>= 256 chars,
            // or matching tail when prefix truncation is not active) and cursor is not mid-paste.
            val clampedTailMin = minOf(spaceNormInserted.length, 256)
            if (before.length < entry.insertedText.length && before.length >= minOf(entry.insertedText.length, clampedTailMin) &&
                entry.insertedText.endsWith(before)
            ) {
                return entry.insertedText
            }
            if (normCleanBefore.length < spaceNormInserted.length && normCleanBefore.length >= minOf(spaceNormInserted.length, clampedTailMin) &&
                spaceNormInserted.endsWith(normCleanBefore)
            ) {
                return spaceNormInserted
            }
            if (singleLineSpaceInserted != null && normCleanBefore.length < singleLineSpaceInserted.length &&
                normCleanBefore.length >= minOf(singleLineSpaceInserted.length, clampedTailMin) &&
                singleLineSpaceInserted.endsWith(normCleanBefore)
            ) {
                return singleLineSpaceInserted
            }
        }
        return null
    }

    private fun rawStartAdjustingInvisible(
        rawBefore: String,
        strippedMap: StrippedTextMap,
        cleanStartIdx: Int,
        beforeAnchor: String
    ): Int {
        var rawStart = strippedMap.rawIndices[cleanStartIdx.coerceIn(0, strippedMap.cleaned.length)]
        while (rawStart > 0 && isInvisibleFormattingChar(rawBefore[rawStart - 1])) {
            val candidatePrefix = rawBefore.substring(0, rawStart - 1)
            if (suffixMatchesAnchor(candidatePrefix, beforeAnchor)) {
                rawStart--
            } else {
                break
            }
        }
        return rawStart
    }

    private fun isCursorMidPasteAgainstCandidate(
        normCleanBefore: String,
        entry: HistoryEntry,
        candidates: List<String>,
        after: String?
    ): Boolean {
        if (after.isNullOrEmpty()) return false
        val normCleanAfter = normalizeHostSpacesAndLines(stripInvisibleWithMap(after).cleaned)
        if (normCleanAfter.isEmpty()) return false
        val anchorNorm = normalizeHostSpacesAndLines(stripInvisibleWithMap(entry.beforeAnchor).cleaned)
        val afterBeforeAnchor = if (anchorNorm.isNotEmpty()) {
            val idx = normCleanBefore.lastIndexOf(anchorNorm)
            if (idx >= 0) normCleanBefore.substring(idx + anchorNorm.length) else null
        } else {
            normCleanBefore
        } ?: return false
        if (afterBeforeAnchor.isEmpty()) return false
        for (cand in candidates) {
            if (afterBeforeAnchor.length < cand.length && cand.startsWith(afterBeforeAnchor)) {
                val remaining = cand.substring(afterBeforeAnchor.length)
                val probeLen = minOf(remaining.length, normCleanAfter.length, 8)
                if (probeLen > 0 && normCleanAfter.startsWith(remaining.substring(0, probeLen))) {
                    return true
                }
            }
        }
        return false
    }

    private fun resolveSearchFieldPrefixTruncation(
        before: String,
        strippedMap: StrippedTextMap,
        normCleanBefore: String,
        entry: HistoryEntry,
        candidates: List<String>,
        after: String?
    ): String? {
        val anchorClean = normalizeHostSpacesAndLines(stripInvisibleWithMap(entry.beforeAnchor).cleaned)
        val normCleanAfter = after?.let { normalizeHostSpacesAndLines(stripInvisibleWithMap(it).cleaned) }
        val minPrefixChars = 4

        val insertionCleanStart: Int
        val insertedPart: String
        if (anchorClean.isNotEmpty()) {
            var anchorIdx = normCleanBefore.lastIndexOf(anchorClean)
            var matchedAnchorLen = anchorClean.length
            if (anchorIdx < 0 && anchorClean.indexOf('\n') >= 0 && normCleanBefore.indexOf('\n') < 0) {
                val spaceAnchor = normalizeHostSpacesAndLines(anchorClean, collapseNewlinesToSpace = true)
                val spaceIdx = normCleanBefore.lastIndexOf(spaceAnchor)
                if (spaceIdx >= 0) {
                    anchorIdx = spaceIdx
                    matchedAnchorLen = spaceAnchor.length
                } else {
                    val strippedAnchor = stripLineEndings(anchorClean)
                    val strippedIdx = normCleanBefore.lastIndexOf(strippedAnchor)
                    if (strippedIdx >= 0) {
                        anchorIdx = strippedIdx
                        matchedAnchorLen = strippedAnchor.length
                    }
                }
            }
            if (anchorIdx < 0) return null
            insertionCleanStart = anchorIdx + matchedAnchorLen
            if (insertionCleanStart >= normCleanBefore.length) return null
            insertedPart = normCleanBefore.substring(insertionCleanStart)
        } else {
            insertionCleanStart = 0
            insertedPart = normCleanBefore
        }

        for (cand in candidates) {
            if (insertedPart.length in minPrefixChars until cand.length && cand.startsWith(insertedPart)) {
                // If non-empty trailing text matches the remainder of the pasted candidate, the user moved the cursor mid-paste.
                if (!normCleanAfter.isNullOrEmpty()) {
                    val remaining = cand.substring(insertedPart.length)
                    val probeLen = minOf(remaining.length, normCleanAfter.length, 8)
                    if (probeLen > 0 && normCleanAfter.startsWith(remaining.substring(0, probeLen))) {
                        return null
                    }
                }
                // For empty-anchor uniform repeated strings (e.g. "A".repeat(500)), distinguish truncated search field (< 512)
                // from a large clamped surrounding-text buffer (>= 512).
                if (anchorClean.isEmpty() && normCleanBefore.length >= 512 && cand.endsWith(insertedPart)) {
                    continue
                }
                val rawStart = rawStartAdjustingInvisible(before, strippedMap, insertionCleanStart, entry.beforeAnchor)
                if (suffixMatchesAnchor(before.substring(0, rawStart), entry.beforeAnchor)) {
                    return before.substring(rawStart)
                }
            }
        }
        return null
    }

    private fun resolveRedoMatchedOriginal(before: String, entry: HistoryEntry): String? {
        if (entry.originalText.isEmpty()) {
            return if (suffixMatchesAnchor(before, entry.beforeAnchor)) "" else null
        }
        if (before.length >= entry.originalText.length && before.endsWith(entry.originalText)) {
            val prefix = before.substring(0, before.length - entry.originalText.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return entry.originalText
            }
        }
        val normOrig = normalizeLineEndings(entry.originalText)
        if (normOrig != entry.originalText && before.length >= normOrig.length && before.endsWith(normOrig)) {
            val prefix = before.substring(0, before.length - normOrig.length)
            if (suffixMatchesAnchor(prefix, entry.beforeAnchor)) {
                return normOrig
            }
        }
        return null
    }

    private fun checkAfterAnchor(gateway: HostEditorGateway, afterAnchor: String): Availability? {
        if (afterAnchor.isEmpty()) return null
        val after = gateway.textAfterCursor(afterAnchor.length)
        if (after == null) {
            return null
        }
        if (after.startsWith(afterAnchor)) return null
        val normAfter = normalizeHostSpacesAndLines(stripInvisibleWithMap(after).cleaned)
        val normAnchor = normalizeHostSpacesAndLines(stripInvisibleWithMap(afterAnchor).cleaned)
        if (normAfter.startsWith(normAnchor)) return null
        if (normAfter.indexOf('\n') < 0 && normAnchor.indexOf('\n') >= 0) {
            val singleAnchor = normalizeHostSpacesAndLines(normAnchor, collapseNewlinesToSpace = true)
            if (normAfter.startsWith(singleAnchor)) return null
            if (normAfter.length < singleAnchor.length && singleAnchor.startsWith(normAfter) && normAfter.isNotEmpty()) {
                return null
            }
            val strippedAnchor = stripLineEndings(normAnchor)
            if (normAfter.startsWith(strippedAnchor)) return null
        }
        if (normAfter.length < normAnchor.length && normAnchor.startsWith(normAfter) && normAfter.isNotEmpty()) {
            return null
        }
        return Availability(false, "Trailing text changed; cannot safely modify.")
    }

    private fun queryBeforeAndAfter(
        gateway: HostEditorGateway,
        entry: HistoryEntry
    ): Pair<String?, String?> {
        val requestLen = entry.insertedText.length * 2 + entry.beforeAnchor.length + 128
        var before = gateway.textBeforeCursor(requestLen)
        if ((before == null || (before.isEmpty() && requestLen > 2048)) && requestLen > 2048) {
            before = gateway.textBeforeCursor(2048)
        }
        if ((before == null || (before.isEmpty() && requestLen > 512)) && requestLen > 512) {
            before = gateway.textBeforeCursor(512)
        }
        val after = gateway.textAfterCursor(maxOf(ANCHOR_LENGTH, entry.afterAnchor.length))
        return Pair(before, after)
    }

    fun canSafelyUndo(gateway: HostEditorGateway): Availability {
        val entry = undoStack.peekFirst() ?: return Availability(false, "Nothing to undo.")
        if (gateway.isPasswordField()) {
            return Availability(false, "Insertion undo disabled in password fields.")
        }
        if (!entry.target.isCompatibleWith(gateway.currentTarget())) {
            return Availability(false, "Input field changed; cannot safely undo.")
        }
        if (gateway.hasActiveSelection()) {
            return Availability(false, "Text is selected; cannot safely undo.")
        }

        val (before, after) = queryBeforeAndAfter(gateway, entry)
        if (before == null) {
            return Availability(false, "App did not expose surrounding text.")
        }

        val matched = resolveUndoMatchedText(before, entry, after)
        if (matched == null) {
            return Availability(false, "Inserted text or cursor position changed.")
        }

        checkAfterAnchor(gateway, entry.afterAnchor)?.let { return it }

        return Availability(true, null)
    }

    fun canSafelyRedo(gateway: HostEditorGateway): Availability {
        val entry = redoStack.peekFirst() ?: return Availability(false, "Nothing to redo.")
        if (gateway.isPasswordField()) {
            return Availability(false, "Insertion redo disabled in password fields.")
        }
        if (!entry.target.isCompatibleWith(gateway.currentTarget())) {
            return Availability(false, "Input field changed; cannot safely redo.")
        }
        if (gateway.hasActiveSelection()) {
            return Availability(false, "Text is selected; cannot safely redo.")
        }

        val expectedTailLen = entry.beforeAnchor.length + entry.originalText.length
        if (expectedTailLen > 0) {
            val before = gateway.textBeforeCursor(expectedTailLen)
                ?: return Availability(false, "App did not expose surrounding text.")
            if (resolveRedoMatchedOriginal(before, entry) == null) {
                return Availability(false, "Cursor moved or text changed; cannot safely redo.")
            }
        } else {
            gateway.textBeforeCursor(16)
                ?: return Availability(false, "App did not expose surrounding text.")
        }

        checkAfterAnchor(gateway, entry.afterAnchor)?.let { return it }

        return Availability(true, null)
    }

    fun undo(gateway: HostEditorGateway): OperationResult {
        val check = canSafelyUndo(gateway)
        if (!check.allowed) {
            return OperationResult(false, check.reason ?: "Undo unavailable.")
        }
        val entry = undoStack.peekFirst() ?: return OperationResult(false, "Nothing to undo.")
        val (before, after) = queryBeforeAndAfter(gateway, entry)
        if (before == null) {
            return OperationResult(false, "App did not expose surrounding text.")
        }
        val matchedInserted = resolveUndoMatchedText(before, entry, after)
            ?: return OperationResult(false, "Inserted text or cursor position changed.")

        val ok = gateway.runBatchEdit {
            val deleted = gateway.deleteBeforeCursor(matchedInserted.length)
            if (!deleted) return@runBatchEdit false
            if (entry.originalText.isNotEmpty()) {
                gateway.commitText(entry.originalText)
            } else {
                true
            }
        }
        if (!ok) {
            return OperationResult(false, "Destination app rejected undo.")
        }
        undoStack.removeFirst()
        pushRedo(entry)
        immediateBackspaceAutocorrectActive = false
        val label = if (entry.kind == ActionKind.AUTOCORRECT) {
            "Reverted correction to \"${entry.rawOriginalWord}\""
        } else {
            "Undid clip insertion (${matchedInserted.length} chars)"
        }
        return OperationResult(true, label, entry)
    }

    fun redo(gateway: HostEditorGateway): OperationResult {
        val check = canSafelyRedo(gateway)
        if (!check.allowed) {
            return OperationResult(false, check.reason ?: "Redo unavailable.")
        }
        val entry = redoStack.peekFirst() ?: return OperationResult(false, "Nothing to redo.")
        val expectedTailLen = entry.beforeAnchor.length + entry.originalText.length
        val matchedOriginal = if (expectedTailLen > 0) {
            val before = gateway.textBeforeCursor(expectedTailLen)
                ?: return OperationResult(false, "App did not expose surrounding text.")
            resolveRedoMatchedOriginal(before, entry)
                ?: return OperationResult(false, "Cursor moved or text changed; cannot safely redo.")
        } else ""

        val ok = gateway.runBatchEdit {
            if (matchedOriginal.isNotEmpty()) {
                if (!gateway.deleteBeforeCursor(matchedOriginal.length)) return@runBatchEdit false
            }
            gateway.commitText(entry.insertedText)
        }
        if (!ok) {
            return OperationResult(false, "Destination app rejected redo.")
        }
        redoStack.removeFirst()
        pushUndo(entry)
        immediateBackspaceAutocorrectActive = false
        val label = if (entry.kind == ActionKind.AUTOCORRECT) {
            "Restored correction"
        } else {
            "Redid clip insertion (${entry.insertedText.length} chars)"
        }
        return OperationResult(true, label, entry)
    }

    /**
     * Verifies whether the host editor has actually applied the undo mutation before allowing
     * UI transition to Redo. Prevents false-positive Redo state when preBefore == "" or beforeAnchor == "".
     */
    fun confirmUndoMutation(
        entry: HistoryEntry,
        preBefore: String?,
        postBefore: String?,
        postAfter: String?,
        gateway: HostEditorGateway
    ): Boolean {
        if (!entry.target.isCompatibleWith(gateway.currentTarget())) return false
        if (postBefore == null) return false
        if (preBefore != null) {
            if (postBefore == preBefore) return false
            if (entry.originalText.isNotEmpty()) {
                return resolveRedoMatchedOriginal(postBefore, entry) != null || postBefore != preBefore
            }
            return postBefore.length < preBefore.length ||
                resolveUndoMatchedText(postBefore, entry, postAfter) == null
        }
        return resolveUndoMatchedText(postBefore, entry, postAfter) == null
    }

    /**
     * Verifies whether the host editor has actually applied the redo mutation before allowing
     * UI transition back to Undo/Hidden.
     */
    fun confirmRedoMutation(
        entry: HistoryEntry,
        preBefore: String?,
        postBefore: String?,
        postAfter: String?,
        gateway: HostEditorGateway
    ): Boolean {
        if (!entry.target.isCompatibleWith(gateway.currentTarget())) return false
        if (postBefore == null) return false
        if (preBefore != null && postBefore == preBefore) return false
        return resolveUndoMatchedText(postBefore, entry, postAfter) != null ||
            (preBefore != null && postBefore.length > preBefore.length)
    }

    /**
     * Rolls back the most recent undo transition if an optimistic asynchronous InputConnection
     * silently ignored or rejected the deletion in the host editor.
     */
    fun rollbackLastUndo(entry: HistoryEntry): Boolean {
        if (redoStack.peekFirst() !== entry) return false
        redoStack.removeFirst()
        pushUndo(entry)
        return true
    }

    /**
     * Rolls back the most recent redo transition if an optimistic asynchronous InputConnection
     * silently ignored or rejected the re-insertion in the host editor.
     */
    fun rollbackLastRedo(entry: HistoryEntry): Boolean {
        if (undoStack.peekFirst() !== entry) return false
        undoStack.removeFirst()
        pushRedo(entry)
        return true
    }

    /**
     * Supports standard immediate-backspace undo right after an automatic spelling correction:
     * deletes "correctedWord + delimiter" and commits "rawOriginalWord" without the delimiter.
     * Returns the restored original word if reverted, or null if normal backspace should proceed.
     */
    fun tryImmediateBackspaceRevertAutocorrect(gateway: HostEditorGateway): String? {
        if (!immediateBackspaceAutocorrectActive) return null
        val entry = undoStack.peekFirst() ?: run {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        if (entry.kind != ActionKind.AUTOCORRECT) {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val check = canSafelyUndo(gateway)
        if (!check.allowed) {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val before = gateway.textBeforeCursor(entry.insertedText.length + entry.beforeAnchor.length) ?: run {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val after = gateway.textAfterCursor(maxOf(ANCHOR_LENGTH, entry.afterAnchor.length))
        val matched = resolveUndoMatchedText(before, entry, after) ?: run {
            immediateBackspaceAutocorrectActive = false
            return null
        }
        val ok = gateway.runBatchEdit {
            if (!gateway.deleteBeforeCursor(matched.length)) return@runBatchEdit false
            gateway.commitText(entry.rawOriginalWord)
        }
        immediateBackspaceAutocorrectActive = false
        if (!ok) return null
        undoStack.removeFirst()
        return entry.rawOriginalWord
    }

    private fun pushUndo(entry: HistoryEntry) {
        undoStack.addFirst(entry)
        while (undoStack.size > maxHistorySize) {
            undoStack.removeLast()
        }
        var totalChars = undoStack.sumOf { it.insertedText.length + it.originalText.length }
        while (undoStack.size > 1 && totalChars > MAX_TOTAL_TRACKED_CHARS) {
            val evicted = undoStack.removeLast()
            totalChars -= (evicted.insertedText.length + evicted.originalText.length)
        }
    }

    private fun pushRedo(entry: HistoryEntry) {
        redoStack.addFirst(entry)
        while (redoStack.size > maxHistorySize) {
            redoStack.removeLast()
        }
    }

    private fun resolveUndoTrailingDelimiterMatch(
        rawBefore: String,
        strippedMap: StrippedTextMap,
        entry: HistoryEntry,
        candidates: List<String>
    ): String? {
        val cleanBefore = strippedMap.cleaned
        val normClean = normalizeHostSpacesAndLines(cleanBefore)
        val trimmedClean = normClean.trimEnd { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it.isWhitespace() }
        val trailingWsLen = normClean.length - trimmedClean.length
        val isUrl = isLikelyUrl(entry.insertedText)

        for (candidate in candidates) {
            if (trailingWsLen in 1..4) {
                if (trimmedClean.length >= candidate.length && trimmedClean.endsWith(candidate)) {
                    val startIdx = trimmedClean.length - candidate.length
                    val rawStart = rawStartAdjustingInvisible(rawBefore, strippedMap, startIdx, entry.beforeAnchor)
                    if (suffixMatchesAnchor(rawBefore.substring(0, rawStart), entry.beforeAnchor)) {
                        return rawBefore.substring(rawStart)
                    }
                }
            }

            if (isUrl) {
                // 1) Check standalone Markdown link wrapping: [Title](url) with optional tracking-param or trailing-slash changes
                val markdownMatch = matchTrailingMarkdownLinkForUrl(rawBefore, strippedMap, normClean, candidate, entry.beforeAnchor)
                if (markdownMatch != null) {
                    return markdownMatch
                }

                // 2) Check <url> or (url) or [url] or literal url with trailing spaces/closing delimiters
                val idx = normClean.lastIndexOf(candidate)
                if (idx >= 0) {
                    val afterUrl = normClean.substring(idx + candidate.length)
                    if (afterUrl.all { it == ')' || it == ']' || it == '>' || it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '/' }) {
                        val beforeUrl = normClean.substring(0, idx)
                        val matchStart = if (beforeUrl.endsWith("<") || beforeUrl.endsWith("[") || beforeUrl.endsWith("(")) {
                            idx - 1
                        } else {
                            idx
                        }
                        val rawStart = rawStartAdjustingInvisible(rawBefore, strippedMap, matchStart, entry.beforeAnchor)
                        if (suffixMatchesAnchor(rawBefore.substring(0, rawStart), entry.beforeAnchor)) {
                            return rawBefore.substring(rawStart)
                        }
                    }
                }

                // 3) Trailing '/' added or stripped by browser / chat app
                val trimmedSlash = trimmedClean.trimEnd('/')
                val slashCount = trimmedClean.length - trimmedSlash.length
                if (slashCount in 1..2 && !candidate.endsWith('/')) {
                    if (trimmedSlash.length >= candidate.length && trimmedSlash.endsWith(candidate)) {
                        val startIdx = trimmedSlash.length - candidate.length
                        val rawStart = rawStartAdjustingInvisible(rawBefore, strippedMap, startIdx, entry.beforeAnchor)
                        if (suffixMatchesAnchor(rawBefore.substring(0, rawStart), entry.beforeAnchor)) {
                            return rawBefore.substring(rawStart)
                        }
                    }
                }
                if (candidate.endsWith('/') && candidate.length > 1) {
                    val candidateNoSlash = candidate.removeSuffix("/")
                    if (trimmedClean.length >= candidateNoSlash.length && trimmedClean.endsWith(candidateNoSlash)) {
                        val startIdx = trimmedClean.length - candidateNoSlash.length
                        val rawStart = rawStartAdjustingInvisible(rawBefore, strippedMap, startIdx, entry.beforeAnchor)
                        if (suffixMatchesAnchor(rawBefore.substring(0, rawStart), entry.beforeAnchor)) {
                            return rawBefore.substring(rawStart)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun matchTrailingMarkdownLinkForUrl(
        rawBefore: String,
        strippedMap: StrippedTextMap,
        normClean: String,
        pastedUrl: String,
        beforeAnchor: String
    ): String? {
        // Trim up to 4 trailing spaces/newlines after ')'
        val trimmed = normClean.trimEnd { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it.isWhitespace() }
        if (normClean.length - trimmed.length > 4) return null
        if (!trimmed.endsWith(')')) return null
        val closeParenIdx = trimmed.length - 1
        val midIdx = trimmed.lastIndexOf("](", startIndex = closeParenIdx)
        if (midIdx < 0) return null
        val renderedUrl = trimmed.substring(midIdx + 2, closeParenIdx).trim()
        if (renderedUrl.isEmpty() || !urlsReferToSameTarget(renderedUrl, pastedUrl)) {
            return null
        }
        val openBracketIdx = trimmed.lastIndexOf('[', startIndex = midIdx)
        if (openBracketIdx < 0 || midIdx - openBracketIdx > 256) return null
        val titleSegment = trimmed.substring(openBracketIdx + 1, midIdx)
        if (titleSegment.indexOf('\n') >= 0) return null
        val rawStart = rawStartAdjustingInvisible(rawBefore, strippedMap, openBracketIdx, beforeAnchor)
        if (!suffixMatchesAnchor(rawBefore.substring(0, rawStart), beforeAnchor)) return null
        return rawBefore.substring(rawStart)
    }

    private fun resolveEmbeddedMarkdownUrlMatch(
        rawBefore: String,
        strippedMap: StrippedTextMap,
        entry: HistoryEntry
    ): String? {
        if (entry.kind != ActionKind.CLIP_INSERT) return null
        val normInserted = normalizeHostSpacesAndLines(stripInvisibleWithMap(entry.insertedText).cleaned)
        val urlMatches = EMBEDDED_URL_REGEX.findAll(normInserted).toList()
        if (urlMatches.isEmpty()) return null

        val cleanNorm = normalizeHostSpacesAndLines(strippedMap.cleaned)
        val trimmedClean = cleanNorm.trimEnd { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it.isWhitespace() }
        val trailingWs = cleanNorm.length - trimmedClean.length
        if (trailingWs > 4) return null

        // Walk backwards from end of trimmedClean matching each literal segment & URL (literal or [Title](renderedUrl))
        val target = trimmedClean
        var cursor = target.length
        var prevEnd = normInserted.length

        for (i in urlMatches.indices.reversed()) {
            val match = urlMatches[i]
            val rawToken = match.value
            val trimmedToken = rawToken.trimEnd('.', ',', '!', '?', ';', ':', ')')
            val urlEndInInserted = match.range.first + trimmedToken.length
            val tailLiteral = normInserted.substring(urlEndInInserted, prevEnd)

            // Allow optional trailing whitespace on the final segment
            val matchedTail = if (i == urlMatches.lastIndex) {
                val tailTrimmed = tailLiteral.trimEnd { it.isWhitespace() }
                when {
                    target.substring(0, cursor).endsWith(tailLiteral) -> tailLiteral
                    target.substring(0, cursor).endsWith(tailTrimmed) -> tailTrimmed
                    else -> null
                }
            } else {
                if (target.substring(0, cursor).endsWith(tailLiteral)) tailLiteral else null
            } ?: return null

            cursor -= matchedTail.length

            // Now match either Markdown link [title](renderedUrl) or literal trimmedToken ending at `cursor`
            val window = target.substring(0, cursor)
            if (window.endsWith(trimmedToken)) {
                cursor -= trimmedToken.length
            } else if (window.endsWith(')')) {
                val closeParen = window.length - 1
                val midIdx = window.lastIndexOf("](", startIndex = closeParen)
                if (midIdx < 0) return null
                val renderedUrl = window.substring(midIdx + 2, closeParen).trim()
                if (!urlsReferToSameTarget(renderedUrl, trimmedToken)) return null
                val openBracket = window.lastIndexOf('[', startIndex = midIdx)
                if (openBracket < 0 || midIdx - openBracket > 256) return null
                cursor = openBracket
            } else {
                return null
            }
            prevEnd = match.range.first
        }

        val leadLiteral = normInserted.substring(0, prevEnd)
        if (!target.substring(0, cursor).endsWith(leadLiteral)) return null
        cursor -= leadLiteral.length

        val rawStart = rawStartAdjustingInvisible(rawBefore, strippedMap, cursor, entry.beforeAnchor)
        if (!suffixMatchesAnchor(rawBefore.substring(0, rawStart), entry.beforeAnchor)) return null
        return rawBefore.substring(rawStart)
    }
}