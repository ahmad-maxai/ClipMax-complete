package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipboardPanelSpecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBottomMostCardIsAlwaysNumberOneCountingUpwards() {
        for (total in listOf(1, 2, 3, 4, 5, 10)) {
            val labels = (0 until total).map { idx ->
                ClipboardPanelSpec.formatSerialLabel(idx, total)
            }
            assertEquals("#1", labels.last())
            assertEquals("#$total", labels.first())
            val expected = (total downTo 1).map { "#$it" }
            assertEquals(expected, labels)
        }
    }

    @Test
    fun testSerialNumberingUpdatesImmediatelyAfterDeleteSplitMergeAndUndoRedo() {
        val store = ClipStore(tempFolder.newFolder("clipstore_phase2b"))
        val oldest = store.save(null, "Copy", "Bottom alpha clip with enough words to split cleanly across halves", false)
        Thread.sleep(5)
        val middle = store.save(null, "Copy", "Middle beta clip content for deletion and merge verification", false)
        Thread.sleep(5)
        val newest = store.save(null, "Copy", "Top gamma clip content", false)

        fun visibleSerials(): List<Pair<String, String>> {
            val list = store.listItems()
            return list.mapIndexed { idx, item ->
                ClipboardPanelSpec.formatSerialLabel(idx, list.size) to item.id
            }
        }

        // Initial 3 cards: top=#3, middle=#2, bottom=#1
        val initial = visibleSerials()
        assertEquals(listOf("#3", "#2", "#1"), initial.map { it.first })
        assertEquals(oldest.id, initial.last().second)

        // 1. Delete middle card (#2) -> remaining immediately become #2 and #1 (bottom still #1)
        assertTrue(store.delete(middle.id))
        val afterDelete = visibleSerials()
        assertEquals(listOf("#2", "#1"), afterDelete.map { it.first })
        assertEquals(oldest.id, afterDelete.last().second)

        // 2. Split bottom card (#1) -> count increases from 2 to 3; bottom-most is #1, top-most is #3
        val splitRes = store.split(oldest.id)!!
        val afterSplit = visibleSerials()
        assertEquals(listOf("#3", "#2", "#1"), afterSplit.map { it.first })
        assertNotEquals("#1", splitRes.part1Item.id)
        assertNotEquals("#2", splitRes.part2Item.id)

        // Delete split parts and save original text as a new clip -> back to 2 cards (#2, #1)
        assertTrue(store.delete(splitRes.part1Item.id))
        assertTrue(store.delete(splitRes.part2Item.id))
        val restored = store.save(null, splitRes.originalItem.title, splitRes.originalText, splitRes.originalItem.pinned)
        val afterUndoSplit = visibleSerials()
        assertEquals(2, afterUndoSplit.size)
        assertEquals(listOf("#2", "#1"), afterUndoSplit.map { it.first })

        // 3. Merge 2 cards -> count drops from 3 to 2; remaining immediately become #2 and #1
        val mergeRes = store.merge(splitRes.part1Item.id, splitRes.part2Item.id)
        val afterMerge = visibleSerials()
        assertEquals(listOf("#2", "#1"), afterMerge.map { it.first })
        assertTrue(newest.id in afterMerge.map { it.second })
        assertTrue(mergeRes.mergedItem.id in afterMerge.map { it.second })
    }

    @Test
    fun testUnifiedCardContentPreviewStartsWithOriginalContentAndExcludesMetadata() {
        val original = "To replace ClipMax's current secondary toolbar icons cleanly without nested boxes or size labels."
        val preview = ClipboardPanelSpec.resolveCardContentPreview(original, maxChars = 64)
        assertTrue(preview.startsWith("To replace ClipMax's"))
        assertTrue(preview.endsWith("…"))
        assertFalse(preview.contains("Chunk 001"))
        assertFalse(preview.contains("KB"))

        val multiline = "\n\n  First real line of clip\nSecond line of clip  "
        assertEquals(
            "First real line of clip Second line of clip",
            ClipboardPanelSpec.resolveCardContentPreview(multiline)
        )

        assertEquals("(blank spaces)", ClipboardPanelSpec.resolveCardContentPreview("   \t  "))
        assertEquals("(empty clip)", ClipboardPanelSpec.resolveCardContentPreview(""))
    }

    @Test
    fun testSpacingIsOneAndAHalfTimesPreviousAndHeightIncreasedTenToFifteenPercent() {
        assertEquals(
            ClipboardPanelSpec.BASE_CARD_SPACING_DP * 1.5f,
            ClipboardPanelSpec.CARD_SPACING_DP,
            0.001f
        )
        assertEquals(9f, ClipboardPanelSpec.CARD_SPACING_DP, 0.001f)

        assertTrue(ClipboardPanelSpec.CARD_MIN_HEIGHT_PORTRAIT_DP in 74f..78f)
        assertTrue(ClipboardPanelSpec.CARD_MIN_HEIGHT_LANDSCAPE_DP < ClipboardPanelSpec.CARD_MIN_HEIGHT_PORTRAIT_DP)
    }

    @Test
    fun testCaptureToggleTransitionsPreserveRetentionPreferenceWithoutClearingSavedClips() {
        val store = ClipStore(tempFolder.newFolder("clipstore_capture_toggle"))
        val saved = store.save(null, "Copy", "Existing saved clip must stay intact when capture turns off", false)

        val off = ClipboardPanelSpec.resolveCaptureToggleTransition(
            currentMode = "72h",
            rememberedEnabledMode = null,
            enable = false
        )
        assertEquals("off", off.nextMode)
        assertFalse(CapturePreferences.isEnabled(off.nextMode))
        assertEquals("72h", off.rememberedEnabledMode)

        assertEquals(1, store.listItems().size)
        assertEquals(saved.id, store.getItem(saved.id)?.id)

        val on = ClipboardPanelSpec.resolveCaptureToggleTransition(
            currentMode = off.nextMode,
            rememberedEnabledMode = off.rememberedEnabledMode,
            enable = true
        )
        assertEquals("72h", on.nextMode)
        assertTrue(CapturePreferences.isEnabled(on.nextMode))

        val fallbackOn = ClipboardPanelSpec.resolveCaptureToggleTransition(
            currentMode = "off",
            rememberedEnabledMode = null,
            enable = true
        )
        assertEquals("24h", fallbackOn.nextMode)
        assertTrue(CapturePreferences.isEnabled(fallbackOn.nextMode))
    }
}
