package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImeInputPipelineTest {

    private fun resolveRepoFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("..", relativePath),
            File("../..", relativePath),
            File("../../..", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate repo file: $relativePath from cwd=${File(".").absolutePath}")
    }

    @Test
    fun shadowBufferSeedingAndStaleRevisionRejection() {
        val shadow = ImeInputPipeline.EditorShadowBuffer()

        // Unsynchronized session (e.g. WebView before background snapshot completes)
        shadow.resetForInputSession(
            initialBefore = null,
            initialAfter = null,
            initialHasSelection = false,
            selStart = -1,
            selEnd = -1
        )
        assertFalse(shadow.synchronizedWithHost)
        assertTrue(shadow.awaitingHostSync)
        assertNull(shadow.currentComposingContext())

        val staleRev = shadow.revision
        shadow.onCommitText("h")
        shadow.onCommitText("i")
        assertEquals("hi", shadow.beforeText())
        assertEquals("hi", shadow.currentComposingContext()?.composingWord)

        // Late host snapshot captured at staleRev must be rejected
        assertFalse(shadow.applyHostSnapshot(staleRev, "old", "", false))
        assertEquals("hi", shadow.beforeText())

        // Snapshot matching current revision applies cleanly
        val currentRev = shadow.revision
        assertTrue(shadow.applyHostSnapshot(currentRev, "hello hi", " world", false))
        assertTrue(shadow.synchronizedWithHost)
        assertFalse(shadow.awaitingHostSync)
        assertEquals("hello hi", shadow.beforeText())
        assertEquals(" world", shadow.afterText())

        // API 30+ initial text seeding marks synchronized immediately
        shadow.resetForInputSession(
            initialBefore = "First line. ",
            initialAfter = "",
            initialHasSelection = false,
            selStart = 12,
            selEnd = 12
        )
        assertTrue(shadow.synchronizedWithHost)
        assertFalse(shadow.awaitingHostSync)
        assertEquals("First line. ", shadow.beforeText())
    }

    @Test
    fun shadowBufferEmojiBackspaceReplaceAndSelectionClassification() {
        val shadow = ImeInputPipeline.EditorShadowBuffer()
        shadow.resetForInputSession("", "", false, 0, 0)

        shadow.onCommitText("hi 🎉")
        assertEquals(ImeInputPipeline.SelectionClassification.EXPECTED_STEP, shadow.onSelectionReported(0, 0, 5, 5))

        val deleteCount = KeyboardLayoutSpec.backspaceUtf16Count(shadow.beforeText(64), SettingsPanelSpec.LANG_ENGLISH)
        assertEquals(2, deleteCount)
        shadow.onDeleteBeforeCursor(deleteCount)
        assertEquals("hi ", shadow.beforeText())
        assertEquals(ImeInputPipeline.SelectionClassification.EXPECTED_STEP, shadow.onSelectionReported(5, 5, 3, 3))

        shadow.onCommitText("teh ")
        assertEquals("hi teh ", shadow.beforeText())
        shadow.onReplaceBeforeCursor(4, "the ")
        assertEquals("hi the ", shadow.beforeText())

        // Selection range & cursor jump classifications
        assertEquals(ImeInputPipeline.SelectionClassification.RANGE_SELECTED, shadow.onSelectionReported(7, 7, 2, 5))
        assertTrue(shadow.hasSelection)
        assertEquals(ImeInputPipeline.SelectionClassification.CURSOR_JUMP, shadow.onSelectionReported(2, 5, 1, 1))
        assertFalse(shadow.hasSelection)

        // Bounds check on huge strings
        val longText = "a".repeat(700)
        shadow.onCommitText(longText)
        assertEquals(ImeInputPipeline.EditorShadowBuffer.MAX_SHADOW_BEFORE_CHARS, shadow.beforeCursor.length)
    }

    @Test
    fun extractComposingContextAndMidWordChecks() {
        assertNull(ImeInputPipeline.extractComposingContext("hello", "", hasSelection = true))

        val midWord = ImeInputPipeline.extractComposingContext("hel", "lo", hasSelection = false)
        assertNotNull(midWord)
        assertTrue(midWord!!.midWord)
        assertEquals("", midWord.composingWord)

        val sentenceStart = ImeInputPipeline.extractComposingContext("Hello. wrld", "", hasSelection = false)
        assertNotNull(sentenceStart)
        assertFalse(sentenceStart!!.midWord)
        assertTrue(sentenceStart.isSentenceStart)
        assertEquals("wrld", sentenceStart.composingWord)
        assertNull(sentenceStart.previousWord)

        val midSentence = ImeInputPipeline.extractComposingContext("Hello wrld", "", hasSelection = false)
        assertNotNull(midSentence)
        assertFalse(midSentence!!.isSentenceStart)
        assertEquals("wrld", midSentence.composingWord)
        assertEquals("Hello", midSentence.previousWord)

        val afterSpace = ImeInputPipeline.extractComposingContext("Good ", "", hasSelection = false)
        assertNotNull(afterSpace)
        assertTrue(afterSpace!!.endedWithSpace)
        assertEquals("Good", afterSpace.previousWord)
    }

    @Test
    fun snapshotHostEditorGatewaySupportsPasteUndoRedoAndAutocorrectRevert() {
        val target = InsertionHistoryManager.EditorTarget("com.clipmax.app", 1, 1, 42L)
        val buffer = StringBuilder("Prefix ")
        val history = InsertionHistoryManager()

        fun gateway(before: String = buffer.toString()): InsertionHistoryManager.HostEditorGateway {
            val sink = object : ImeInputPipeline.MutationSink {
                override fun deleteBeforeCursor(length: Int): Boolean {
                    if (length > buffer.length) return false
                    buffer.delete(buffer.length - length, buffer.length)
                    return true
                }

                override fun commitText(text: String): Boolean {
                    buffer.append(text)
                    return true
                }

                override fun runBatchEdit(block: () -> Boolean): Boolean = block()
            }
            return ImeInputPipeline.SnapshotHostEditorGateway(
                target = target,
                passwordField = false,
                preReadBefore = before,
                preReadAfter = "",
                preReadSelected = null,
                sink = sink
            )
        }

        // Paste insertion + Undo + Redo
        val pasted = "ClipMax snapshot paste"
        assertTrue(history.recordClipInsertion(target, pasted, beforeAnchor = "Prefix ", afterAnchor = ""))
        buffer.append(pasted)
        assertTrue(history.canSafelyUndo(gateway()).allowed)

        val preUndo = buffer.toString()
        val undoRes = history.undo(gateway(preUndo))
        assertTrue(undoRes.success)
        assertEquals("Prefix ", buffer.toString())
        assertTrue(history.confirmUndoMutation(undoRes.entry!!, preUndo, buffer.toString(), "", gateway()))

        val preRedo = buffer.toString()
        val redoRes = history.redo(gateway(preRedo))
        assertTrue(redoRes.success)
        assertEquals("Prefix ClipMax snapshot paste", buffer.toString())
        assertTrue(history.confirmRedoMutation(redoRes.entry!!, preRedo, buffer.toString(), "", gateway()))

        // Immediate backspace autocorrect revert
        buffer.append(" the ")
        assertTrue(
            history.recordAutocorrect(
                target = target,
                rawOriginalWord = "teh",
                correctedWord = "the",
                delimiter = " ",
                beforeAnchor = "Prefix ClipMax snapshot paste ",
                afterAnchor = ""
            )
        )
        val reverted = history.tryImmediateBackspaceRevertAutocorrect(gateway())
        assertEquals("teh", reverted)
        assertEquals("Prefix ClipMax snapshot paste teh", buffer.toString())
    }

    @Test
    fun quickHighConfidenceCorrectionIsDeterministicAndSafe() {
        val engine = TypingIntelligenceEngine.createFallback()
        assertEquals("the", engine.quickHighConfidenceCorrection("teh", "teh", isSentenceStart = false))
        assertEquals("The", engine.quickHighConfidenceCorrection("Teh", "Teh", isSentenceStart = true))
        assertNull(engine.quickHighConfidenceCorrection("teh", "teh", isSentenceStart = false, suppressedWordsLower = setOf("teh")))
        assertNull(engine.quickHighConfidenceCorrection("hello", "hello", isSentenceStart = false))
        assertNull(engine.quickHighConfidenceCorrection("teh", "https://teh.com/teh", isSentenceStart = false))
        assertNull(engine.quickHighConfidenceCorrection("مرحبا", "مرحبا", isSentenceStart = false))
    }

    @Test
    fun viewSignaturesAndClipStoreChangeVersionTrackStateChanges() {
        val rowsA = ImeInputPipeline.ViewSignatureSpec.keyboardRowsSignature(
            language = "en",
            mode = KeyboardLayoutSpec.KeyboardMode.ALPHA,
            themeKey = "system",
            heightPref = "normal",
            screenHeightDp = 720,
            isLandscape = false,
            isFilterMode = false,
            isInserting = false,
            isPasswordTarget = false
        )
        val rowsB = ImeInputPipeline.ViewSignatureSpec.keyboardRowsSignature(
            language = "en",
            mode = KeyboardLayoutSpec.KeyboardMode.ALPHA,
            themeKey = "system",
            heightPref = "normal",
            screenHeightDp = 720,
            isLandscape = false,
            isFilterMode = false,
            isInserting = false,
            isPasswordTarget = false
        )
        val rowsC = ImeInputPipeline.ViewSignatureSpec.keyboardRowsSignature(
            language = "ar",
            mode = KeyboardLayoutSpec.KeyboardMode.ALPHA,
            themeKey = "system",
            heightPref = "normal",
            screenHeightDp = 720,
            isLandscape = false,
            isFilterMode = false,
            isInserting = false,
            isPasswordTarget = false
        )
        assertEquals(rowsA, rowsB)
        assertTrue(rowsA != rowsC)

        val undoConfig = PasteUndoCapsuleSpec.resolveConfig(isActive = true, isRedoMode = false)
        val redoConfig = PasteUndoCapsuleSpec.resolveConfig(isActive = true, isRedoMode = true)
        val hiddenConfig = PasteUndoCapsuleSpec.resolveConfig(isActive = false, isRedoMode = false)
        assertNull(hiddenConfig.action)

        val sigUndo = ImeInputPipeline.ViewSignatureSpec.pasteCapsuleUiSignature(undoConfig, 0, "Undo", 0xFF000000.toInt())
        val sigRedo = ImeInputPipeline.ViewSignatureSpec.pasteCapsuleUiSignature(redoConfig, 0, "Redo", 0xFF000000.toInt())
        val sigHidden = ImeInputPipeline.ViewSignatureSpec.pasteCapsuleUiSignature(hiddenConfig, 0, "", 0xFF000000.toInt())
        assertTrue(sigUndo != sigRedo)
        assertTrue(sigHidden.contains("|NONE|"))

        val tempDir = Files.createTempDirectory("clipstore-version-test").toFile()
        try {
            val store = ClipStore(tempDir)
            val v0 = ClipStore.currentChangeVersion()
            val saved = store.save(null, "Note", "Hello ClipMax")
            val v1 = ClipStore.currentChangeVersion()
            assertTrue(v1 > v0)
            store.setPinned(saved.id, true)
            val v2 = ClipStore.currentChangeVersion()
            assertTrue(v2 > v1)
            assertTrue(store.delete(saved.id))
            val v3 = ClipStore.currentChangeVersion()
            assertTrue(v3 > v2)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun staticGuardZeroMainThreadIcBlockingAndNo100Dvh() {
        val imeFile = resolveRepoFile("android/app/src/main/java/com/clipmax/app/ClipMaxImeService.kt")
        val imeSrc = imeFile.readText()

        assertFalse("Obsolete blocking buildEditorGateway must stay removed", imeSrc.contains("buildEditorGateway"))
        assertFalse("Raw io.execute must not exist; use submitSafely(io)", imeSrc.contains("io.execute"))
        assertFalse("Raw inserter.submit must not exist; use submitFutureSafely", imeSrc.contains("inserter.submit"))
        assertFalse("Raw suggestExecutor.execute must not exist; use submitSafely", imeSrc.contains("suggestExecutor.execute"))
        assertFalse("Labeled return@execute must be updated to return@submitSafely", imeSrc.contains("return@execute"))

        // capturePreferenceListener must not rebuild toolbar chips
        val capStart = imeSrc.indexOf("private val capturePreferenceListener")
        val capEnd = imeSrc.indexOf("override fun onCreate()", capStart)
        assertTrue(capStart >= 0 && capEnd > capStart)
        val capBlock = imeSrc.substring(capStart, capEnd)
        assertFalse("capturePreferenceListener must not call renderClipChips()", capBlock.contains("renderClipChips("))

        // Every InputConnection query method in ClipMaxImeService must live ONLY inside off-main-thread helpers
        val icReadStart = imeSrc.indexOf("private fun readHasSelectionFromIcOffMainThread")
        val icReadEnd = imeSrc.indexOf("private fun buildSnapshotGatewayOffMainThread", icReadStart)
        assertTrue(icReadStart >= 0 && icReadEnd > icReadStart)

        val lines = imeSrc.lines()
        val icQueryRegex = Regex("""ic\.(getTextBeforeCursor|getTextAfterCursor|getSelectedText|getSurroundingText|getExtractedText|getCursorCapsMode)\(|conn\??\.(getTextBeforeCursor|getTextAfterCursor|getSelectedText|getSurroundingText|getExtractedText|getCursorCapsMode)\(""")
        val allowedStartLine = imeSrc.substring(0, icReadStart).lines().size
        val allowedEndLine = imeSrc.substring(0, icReadEnd).lines().size
        lines.forEachIndexed { idx, line ->
            val lineNum = idx + 1
            if (icQueryRegex.containsMatchIn(line)) {
                assertTrue(
                    "Synchronous IC query found outside off-main helpers at line $lineNum: $line",
                    lineNum in allowedStartLine..allowedEndLine
                )
            }
        }

        // Web screens must use h-full max-h-full rather than 100dvh inside the WebView shell
        val webScreens = listOf(
            "src/components/SettingsScreen.tsx",
            "src/components/EditorScreen.tsx",
            "src/components/LibraryScreen.tsx",
            "src/components/DetailsScreen.tsx"
        )
        webScreens.forEach { rel ->
            val txt = resolveRepoFile(rel).readText()
            assertFalse("$rel must not contain 100dvh", txt.contains("100dvh"))
            assertTrue("$rel must use h-full max-h-full", txt.contains("h-full max-h-full"))
        }

        val settingsTxt = resolveRepoFile("src/components/SettingsScreen.tsx").readText()
        assertTrue("SettingsScreen must debounce custom expiry bridge writes", settingsTxt.contains("customDurationDebounceRef"))
        assertTrue("SettingsScreen must flush debounced expiry writes on blur", settingsTxt.contains("onBlur={flushPendingCustomDuration}"))
    }
}
