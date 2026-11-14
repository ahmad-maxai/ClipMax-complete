package com.clipmax.app

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class CaptureSource {
    CLIPBOARD_LISTENER,
    CLIPBOARD_POLL,
    SELECTION
}

internal interface SignatureStateStore {
    fun lastSignature(): String?
    fun lastClipTimestamp(): Long
    fun lastCapturedIds(): Set<String>
    fun recordCapture(signature: String, clipTimestamp: Long, itemIds: List<String>)
    fun updateObservedTimestamp(signature: String, clipTimestamp: Long)
}

internal class InMemorySignatureStateStore : SignatureStateStore {
    private var signature: String? = null
    private var clipTimestamp: Long = -1L
    private var capturedIds: Set<String> = emptySet()

    override fun lastSignature(): String? = signature

    override fun lastClipTimestamp(): Long = clipTimestamp

    override fun lastCapturedIds(): Set<String> = capturedIds

    override fun recordCapture(signature: String, clipTimestamp: Long, itemIds: List<String>) {
        this.signature = signature
        this.clipTimestamp = clipTimestamp
        this.capturedIds = itemIds.toSet()
    }

    override fun updateObservedTimestamp(signature: String, clipTimestamp: Long) {
        this.signature = signature
        this.clipTimestamp = clipTimestamp
    }
}

internal data class CaptureOutcome(
    val savedItems: List<ClipItem>,
    val signature: String,
    val capturedAt: Long,
    val skippedReason: String? = null
) {
    val savedCount: Int get() = savedItems.size
}

/**
 * Pure-JVM capture engine responsible for:
 * 1. Streaming SHA-256 + length content signatures (collision-safe, bounded memory).
 * 2. Deterministic duplicate suppression (deduplicating redundant listener/poll callbacks
 *    and consecutive identical copies while preserving A -> B -> A and post-delete re-copies).
 * 3. Strictly monotonic capture timestamps so rapid consecutive copies (< 1ms apart)
 *    always sort newest-first while chunks of a single copy remain grouped in ascending order.
 */
internal class ClipboardCaptureEngine(
    private val store: ClipStore,
    private val stateStore: SignatureStateStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var lastMonotonicTimestamp: Long = 0L

    @Synchronized
    fun captureText(
        text: CharSequence,
        source: CaptureSource,
        clipTimestamp: Long = -1L,
        isSensitive: Boolean = false,
        deduplicateConsecutive: Boolean = true
    ): CaptureOutcome {
        if (isSensitive) {
            return CaptureOutcome(emptyList(), "", 0L, "sensitive")
        }
        if (text.isEmpty()) {
            return CaptureOutcome(emptyList(), "", 0L, "empty")
        }

        val signature = computeContentSignature(text)
        val lastSig = stateStore.lastSignature()
        val lastTs = stateStore.lastClipTimestamp()

        if (source == CaptureSource.CLIPBOARD_POLL) {
            val sameTimedEvent = clipTimestamp > 0L && clipTimestamp == lastTs && signature == lastSig
            val sameUntimedEvent = clipTimestamp <= 0L && signature == lastSig
            val legacyAlreadyCaptured = lastTs < 0L && signature == lastSig && store.listItems().isNotEmpty()
            if (sameTimedEvent || sameUntimedEvent || legacyAlreadyCaptured) {
                if (clipTimestamp > 0L && signature == lastSig) {
                    stateStore.updateObservedTimestamp(signature, clipTimestamp)
                }
                return CaptureOutcome(emptyList(), signature, 0L, "already-observed")
            }
        } else if (source == CaptureSource.CLIPBOARD_LISTENER) {
            if (clipTimestamp > 0L && clipTimestamp == lastTs && signature == lastSig) {
                return CaptureOutcome(emptyList(), signature, 0L, "duplicate-callback")
            }
        }

        if (deduplicateConsecutive && signature == lastSig) {
            val lastIds = stateStore.lastCapturedIds()
            val previousStillExists = lastIds.isNotEmpty() && lastIds.all { store.getItem(it) != null }
            if (previousStillExists) {
                if (clipTimestamp > 0L) {
                    stateStore.updateObservedTimestamp(signature, clipTimestamp)
                }
                return CaptureOutcome(emptyList(), signature, 0L, "consecutive-duplicate")
            }
        }

        val existingMaxCreated = store.listItems().maxOfOrNull { it.createdAt } ?: 0L
        val now = clockMillis()
        val capturedAt = maxOf(now, existingMaxCreated + 1L, lastMonotonicTimestamp + 1L)
        lastMonotonicTimestamp = capturedAt

        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(capturedAt))
        val sourceLabel = if (source == CaptureSource.SELECTION) "Select" else "Copy"
        val savedItems = mutableListOf<ClipItem>()
        ClipCaptureWriter.saveToCards(store, text, sourceLabel, stamp, capturedAt) { saved ->
            savedItems.add(saved)
        }
        stateStore.recordCapture(signature, clipTimestamp, savedItems.map { it.id })
        return CaptureOutcome(savedItems, signature, capturedAt, null)
    }

    companion object {
        fun computeContentSignature(text: CharSequence): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(16 * 1024)
            var bytePos = 0
            for (i in 0 until text.length) {
                val code = text[i].code
                chunk[bytePos++] = ((code ushr 8) and 0xFF).toByte()
                chunk[bytePos++] = (code and 0xFF).toByte()
                if (bytePos >= chunk.size) {
                    digest.update(chunk, 0, bytePos)
                    bytePos = 0
                }
            }
            if (bytePos > 0) {
                digest.update(chunk, 0, bytePos)
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return "${text.length}:$hex"
        }
    }
}
