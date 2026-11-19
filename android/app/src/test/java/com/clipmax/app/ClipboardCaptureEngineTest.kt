package com.clipmax.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JVM Unit tests verifying multi-copy clipboard history accumulation, monotonic ordering,
 * cross-instance ClipStore locking, long chunked document independence, duplicate policy,
 * polling stability, sensitive suppression, and persistence across store/engine restarts.
 */
class ClipboardCaptureEngineTest {

    private lateinit var tempDir: File
    private lateinit var store: ClipStore
    private lateinit var stateStore: InMemorySignatureStateStore
    private lateinit var engine: ClipboardCaptureEngine

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("clipmax_capture_test").toFile()
        store = ClipStore(tempDir)
        stateStore = InMemorySignatureStateStore()
        engine = ClipboardCaptureEngine(store, stateStore)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testMultipleShortCopiesFromDifferentSourcesAccumulateNewestFirst() {
        val copies = listOf(
            "Copy 1 from Chrome: https://example.com",
            "Copy 2 from Notes: Meeting action items",
            "Copy 3 from ChatGPT: Architecture summary",
            "Copy 4 from Messages: OTP code 994218",
            "Copy 5 from Email: Flight itinerary EK203"
        )
        copies.forEachIndexed { idx, text ->
            val outcome = engine.captureText(
                text = text,
                source = CaptureSource.CLIPBOARD_LISTENER,
                clipTimestamp = 1_000_000L + idx * 500L
            )
            assertEquals(1, outcome.savedCount)
        }

        val items = store.listItems()
        assertEquals(5, items.size)
        val contents = items.map { store.readContent(it) }
        assertEquals(copies.reversed(), contents)
    }

    @Test
    fun testRapidConsecutiveAndConcurrentCopiesNeverDropOrRace() {
        // Frozen wall clock to guarantee <1ms same-millisecond rapid copy behavior
        val frozenClock = { 5_000_000L }
        val storeA = ClipStore(tempDir)
        val storeB = ClipStore(tempDir)
        val engineFrozen = ClipboardCaptureEngine(storeA, stateStore, frozenClock)

        val rapidCopies = (1..10).map { "Rapid snippet #$it · payload-${it * 97}" }
        rapidCopies.forEachIndexed { idx, payload ->
            val outcome = engineFrozen.captureText(
                text = payload,
                source = CaptureSource.CLIPBOARD_LISTENER,
                clipTimestamp = 2_000_000L + idx
            )
            assertEquals(1, outcome.savedCount)
        }

        val storedAfterRapid = storeB.listItems()
        assertEquals(10, storedAfterRapid.size)
        assertEquals(rapidCopies.reversed(), storedAfterRapid.map { storeB.readContent(it) })
        // Strictly monotonic createdAt timestamps despite frozen wall clock
        for (i in 0 until storedAfterRapid.size - 1) {
            assertTrue(storedAfterRapid[i].createdAt > storedAfterRapid[i + 1].createdAt)
        }

        // Concurrent saves across two separate ClipStore instances pointing to the same directory
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(8)
        for (t in 0 until 8) {
            pool.execute {
                try {
                    val threadStore = if (t % 2 == 0) storeA else storeB
                    threadStore.save(null, "Concurrent $t", "Concurrent body $t", savedAt = 6_000_000L + t)
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(18, storeA.listItems().size)
    }

    @Test
    fun testMultipleLongDocumentsChunkedWithoutMixing() {
        val doc25k = ("Doc1-🚀-alpha-").repeat(2_000) // ~26k chars -> 1 chunk
        val doc60k = ("Doc2-日本語-beta-").repeat(4_300) // ~60.2k chars -> 2 chunks
        val doc115k = ("Doc3-🌍-gamma-").repeat(8_300) // ~116.2k chars -> 3 chunks

        val out1 = engine.captureText(doc25k, CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 100L)
        val out2 = engine.captureText(doc60k, CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 200L)
        val out3 = engine.captureText(doc115k, CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 300L)

        assertEquals(1, out1.savedCount)
        assertEquals(2, out2.savedCount)
        assertEquals(3, out3.savedCount)

        val allItems = store.listItems()
        assertEquals(6, allItems.size)

        // All chunks of doc115k must appear first (001, 002, 003), then doc60k (001, 002), then doc25k
        val reconstructedDoc3 = allItems.subList(0, 3).joinToString("") { store.readContent(it) }
        val reconstructedDoc2 = allItems.subList(3, 5).joinToString("") { store.readContent(it) }
        val reconstructedDoc1 = store.readContent(allItems[5])

        assertEquals(doc115k, reconstructedDoc3)
        assertEquals(doc60k, reconstructedDoc2)
        assertEquals(doc25k, reconstructedDoc1)

        // Verify chunk titles never mix across batches
        assertTrue(allItems[0].title.contains("Chunk 001/003"))
        assertTrue(allItems[1].title.contains("Chunk 002/003"))
        assertTrue(allItems[2].title.contains("Chunk 003/003"))
        assertTrue(allItems[3].title.contains("Chunk 001/002"))
        assertTrue(allItems[4].title.contains("Chunk 002/002"))
        assertTrue(allItems[5].title.contains("Chunk 001/001"))
    }

    @Test
    fun testDuplicatePolicyConsecutiveVsAlternatingAndDeletedReCopy() {
        // Consecutive identical copies: X -> X -> X retains 1
        val x1 = engine.captureText("Alpha", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 10L)
        val x2 = engine.captureText("Alpha", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 20L)
        val x3 = engine.captureText("Alpha", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 30L)
        assertEquals(1, x1.savedCount)
        assertEquals(0, x2.savedCount)
        assertEquals("consecutive-duplicate", x2.skippedReason)
        assertEquals(0, x3.savedCount)

        // Non-consecutive repeat: Alpha -> Beta -> Alpha retains [Alpha, Beta, Alpha]
        val y1 = engine.captureText("Beta", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 40L)
        val x4 = engine.captureText("Alpha", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 50L)
        assertEquals(1, y1.savedCount)
        assertEquals(1, x4.savedCount)
        assertEquals(listOf("Alpha", "Beta", "Alpha"), store.listItems().map { store.readContent(it) })

        // Delete newest Alpha, ensure POLL doesn't resurrect, but explicit new LISTENER copy re-captures Alpha
        val newestId = x4.savedItems.first().id
        assertTrue(store.delete(newestId))
        val pollSame = engine.captureText("Alpha", CaptureSource.CLIPBOARD_POLL, clipTimestamp = 50L)
        assertEquals(0, pollSame.savedCount)
        val recopy = engine.captureText("Alpha", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 60L)
        assertEquals(1, recopy.savedCount)
        assertEquals(listOf("Alpha", "Beta", "Alpha"), store.listItems().map { store.readContent(it) })
    }

    @Test
    fun testLargeStringsWithSingleTailCharDifferenceDoNotCollide() {
        val base = "a".repeat(55_000)
        val variantA = base + "1"
        val variantB = base + "2"

        val outA = engine.captureText(variantA, CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 100L)
        val outB = engine.captureText(variantB, CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 200L)

        assertEquals(2, outA.savedCount)
        assertEquals(2, outB.savedCount)
        assertEquals(4, store.listItems().size)
    }

    @Test
    fun testRepeatedKeyboardPollingAndRestartPersistence() {
        engine.captureText("Item A", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 101L)
        engine.captureText("Item B", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 102L)
        engine.captureText("Item C", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 103L)

        // Simulate repeated keyboard open/close polling on untouched clipboard
        repeat(5) {
            val poll = engine.captureText("Item C", CaptureSource.CLIPBOARD_POLL, clipTimestamp = 103L)
            assertEquals(0, poll.savedCount)
        }
        assertEquals(listOf("Item C", "Item B", "Item A"), store.listItems().map { store.readContent(it) })

        // Simulate process/device restart by constructing fresh ClipStore & engine on same directory
        val restartedStore = ClipStore(tempDir)
        val restartedEngine = ClipboardCaptureEngine(restartedStore, stateStore)
        assertEquals(3, restartedStore.listItems().size)

        val outD = restartedEngine.captureText("Item D", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 200L)
        assertEquals(1, outD.savedCount)
        assertEquals(
            listOf("Item D", "Item C", "Item B", "Item A"),
            restartedStore.listItems().map { restartedStore.readContent(it) }
        )
    }

    @Test
    fun testSensitiveContentIsSuppressed() {
        engine.captureText("Public Clip", CaptureSource.CLIPBOARD_LISTENER, clipTimestamp = 1L)
        val sensitive = engine.captureText(
            text = "s3cr3t-password",
            source = CaptureSource.CLIPBOARD_LISTENER,
            clipTimestamp = 2L,
            isSensitive = true
        )
        assertEquals(0, sensitive.savedCount)
        assertEquals("sensitive", sensitive.skippedReason)
        assertEquals(listOf("Public Clip"), store.listItems().map { store.readContent(it) })
    }
}
