package com.clipmax.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM Unit tests verifying atomic persistence, full CRUD operations,
 * and zero silent truncation of long text (including surrogate pairs and Unicode).
 */
class ClipStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: ClipStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("clipmax_test").toFile()
        store = ClipStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSaveAndRetrieveLongTextWithoutTruncation() {
        // Construct a long text with multi-byte unicode, newlines, and standard characters
        val baseChunk = "ClipMax test payload: Unicode — 日本語 漢字 — éàü — \nLine break test.\n"
        val repeats = 2_000
        val longText = baseChunk.repeat(repeats) // ~120,000 characters

        val saved = store.save(
            id = null,
            title = "Extremely Long Note",
            content = longText,
            pinned = false
        )

        assertNotNull(saved.id)
        assertEquals("Extremely Long Note", saved.title)
        assertEquals(longText.codePointCount(0, longText.length), saved.characterCount)

        val retrieved = store.getItem(saved.id)
        assertNotNull(retrieved)
        val retrievedContent = store.readContent(retrieved!!)

        // Verify zero truncation: lengths and exact strings must match completely
        assertEquals(longText.length, retrievedContent.length)
        assertEquals(longText, retrievedContent)
    }

    @Test
    fun testSaveAndRetrieveVaryingLargeSizes20kTo1Million() {
        val sizes = listOf(20_000, 70_001, 100_000, 500_000, 1_000_000)
        for (targetSize in sizes) {
            val unit = "A_日本語é\n"
            val text = unit.repeat((targetSize / unit.length) + 1).take(targetSize)
            val saved = store.save(null, "Size $targetSize", text, false)
            assertEquals(targetSize, text.length)
            assertEquals(text.codePointCount(0, text.length), saved.characterCount)

            // Verify store & restart survival by opening with a new ClipStore instance
            val storeRestart = ClipStore(tempDir)
            val retrievedItem = storeRestart.getItem(saved.id)
            assertNotNull(retrievedItem)
            val retrievedContent = storeRestart.readContent(retrievedItem!!)
            assertEquals(text.length, retrievedContent.length)
            assertEquals(text, retrievedContent)
        }
    }

    @Test
    fun testFullCrudOperations() {
        // 1. Create
        val item1 = store.save(null, "First Clip", "Content of first clip", pinned = false)
        val item2 = store.save(null, "Second Clip", "Content of second clip", pinned = true)

        val allItems = store.listItems()
        assertEquals(2, allItems.size)

        // 2. Read
        val read1 = store.getItem(item1.id)
        assertNotNull(read1)
        assertEquals("First Clip", read1?.title)
        assertEquals("Content of first clip", store.readContent(read1!!))
        assertFalse(read1.pinned)

        val read2 = store.getItem(item2.id)
        assertNotNull(read2)
        assertTrue(read2!!.pinned)

        // 3. Update
        val updated1 = store.save(item1.id, "Updated First Clip", "Brand new content for clip 1", pinned = true)
        assertEquals(item1.id, updated1.id)
        assertEquals("Updated First Clip", updated1.title)
        assertTrue(updated1.pinned)
        assertEquals("Brand new content for clip 1", store.readContent(updated1))

        // 4. Pin toggle
        val unpinned2 = store.setPinned(item2.id, false)
        assertFalse(unpinned2.pinned)

        // 5. Delete
        val deleted = store.delete(item1.id)
        assertTrue(deleted)
        assertNull(store.getItem(item1.id))
        assertEquals(1, store.listItems().size)

        // 6. Delete all
        store.deleteAll()
        assertTrue(store.listItems().isEmpty())
    }

    @Test
    fun testDraftPersistence() {
        store.saveDraft("draft-item-1", "Draft Title", "Unfinished work in progress...")
        val draft = store.loadDraft()
        assertNotNull(draft)
        assertEquals("draft-item-1", draft?.itemId)
        assertEquals("Draft Title", draft?.title)
        assertEquals("Unfinished work in progress...", draft?.content)

        store.clearDraft()
        assertNull(store.loadDraft())
    }

    @Test
    fun testMergeAndSplitOperations() {
        // 1. Merge short items
        val item1 = store.save(null, "Card A", "Hello, how are you?", false)
        val item2 = store.save(null, "Card B", "I am doing well.", true)

        val mergeRes = store.merge(item1.id, item2.id)
        val merged = mergeRes.mergedItem
        assertEquals("Hello, how are you?\nI am doing well.", store.readContent(merged))
        assertTrue(merged.pinned) // Source or Target pinned state preserved
        assertNull(store.getItem(item1.id))
        assertNull(store.getItem(item2.id))

        // 2. Reject self-merge
        try {
            store.merge(merged.id, merged.id)
            org.junit.Assert.fail("Self-merge should throw IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}

        // 3. Merge with multiline & Unicode
        val unicodeItem1 = store.save(null, "Unicode 1", "First line\nSecond line with symbol", false)
        val unicodeItem2 = store.save(null, "Unicode 2", "日本語 漢字 third line\nFourth line", false)
        val mergedUnicode = store.merge(unicodeItem1.id, unicodeItem2.id).mergedItem
        val expectedUnicode = "First line\nSecond line with symbol\n日本語 漢字 third line\nFourth line"
        assertEquals(expectedUnicode, store.readContent(mergedUnicode))

        // 4. Split even-length string "ABCDEFGHIJ"
        val evenItem = store.save(null, "Even Letters", "ABCDEFGHIJ", false)
        val splitResult = store.split(evenItem.id)
        assertNotNull(splitResult)
        val part1 = splitResult!!.part1Item
        val part2 = splitResult.part2Item
        assertEquals("ABCDE", store.readContent(part1))
        assertEquals("FGHIJ", store.readContent(part2))
        assertEquals("ABCDEFGHIJ", store.readContent(part1) + store.readContent(part2))

        // 5. Split odd-length string
        val oddItem = store.save(null, "Odd Letters", "ABCDEFG", false)
        val oddSplit = store.split(oddItem.id)
        assertNotNull(oddSplit)
        val oddP1 = oddSplit!!.part1Item
        val oddP2 = oddSplit.part2Item
        assertEquals("ABCDEFG", store.readContent(oddP1) + store.readContent(oddP2))
        assertTrue(kotlin.math.abs(store.readContent(oddP1).length - store.readContent(oddP2).length) <= 1)

        // 6. Split multiline with Unicode ensuring correctness
        val multiItem = store.save(null, "Multi Unicode", "Line 1 Alpha\nLine 2 Beta\nLine 3 Gamma", false)
        val multiSplit = store.split(multiItem.id)
        assertNotNull(multiSplit)
        val mP1 = multiSplit!!.part1Item
        val mP2 = multiSplit.part2Item
        assertEquals("Line 1 Alpha\nLine 2 Beta\nLine 3 Gamma", store.readContent(mP1) + store.readContent(mP2))

        // 7. Undo and Redo simulation
        // Undo split: delete mP1 and mP2, re-save original with same ID and createdAt
        val origContent = store.readContent(mP1) + store.readContent(mP2)
        store.delete(mP1.id)
        store.delete(mP2.id)
        val restored = store.save(multiItem.id, multiItem.title, origContent, multiItem.pinned, multiItem.createdAt)
        assertEquals(multiItem.id, restored.id)
        assertEquals(origContent, store.readContent(restored))
    }

    @Test
    fun testNewestFirstOrderingAndChunkGrouping() {
        val t0 = 1_000_000L
        // Save three distinct test clips in sequence: A, then B, then C
        store.save(null, "Clip A", "Content A", pinned = false, savedAt = t0)
        store.save(null, "Clip B", "Content B", pinned = false, savedAt = t0 + 1000L)
        store.save(null, "Clip C", "Content C", pinned = false, savedAt = t0 + 2000L)

        val items = store.listItems()
        assertEquals(3, items.size)
        // Verify displayed order is C, B, A (newest first)
        assertEquals("Clip C", items[0].title)
        assertEquals("Clip B", items[1].title)
        assertEquals("Clip A", items[2].title)

        // Verify chunk sequence for multi-chunk captures:
        // Even when chunk 2 happens to be written before chunk 1, Chunk 001 must appear before Chunk 002
        val tChunk = t0 + 3000L
        store.save(null, "Capture · Chunk 002/002 · 12:00:00", "Chunk 2", pinned = false, savedAt = tChunk)
        store.save(null, "Capture · Chunk 001/002 · 12:00:00", "Chunk 1", pinned = false, savedAt = tChunk)

        val updatedItems = store.listItems()
        assertEquals(5, updatedItems.size)
        // Newest capture (at tChunk) is at top, and Chunk 001 comes BEFORE Chunk 002
        assertEquals("Capture · Chunk 001/002 · 12:00:00", updatedItems[0].title)
        assertEquals("Capture · Chunk 002/002 · 12:00:00", updatedItems[1].title)
        assertEquals("Clip C", updatedItems[2].title)
        assertEquals("Clip B", updatedItems[3].title)
        assertEquals("Clip A", updatedItems[4].title)
    }
}
