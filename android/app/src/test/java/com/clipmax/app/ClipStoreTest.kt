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
        // Construct a long text with multi-byte unicode, newlines, emojis, and standard characters
        val baseChunk = "ClipMax test payload: Unicode 🚀 — 日本語 漢字 — éàü — \nLine break test.\n"
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
        assertEquals(longText.length, saved.characterCount)

        val retrieved = store.getItem(saved.id)
        assertNotNull(retrieved)
        val retrievedContent = store.readContent(retrieved!!)

        // Verify zero truncation: lengths and exact strings must match completely
        assertEquals(longText.length, retrievedContent.length)
        assertEquals(longText, retrievedContent)
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
