package com.clipmax.app

/** Persists one independently insertable ClipMax item per bounded chunk of captured text. */
internal object ClipCaptureWriter {
    fun saveToCards(
        store: ClipStore,
        text: CharSequence,
        sourceLabel: String,
        timeLabel: String,
        capturedAt: Long = System.currentTimeMillis(),
        onSaved: (ClipItem) -> Unit = {}
    ): Int {
        require(sourceLabel.length <= 40)
        val total = TextChunker.chunkCount(text)
        var index = 0
        TextChunker.forEachChunk(TextChunker.reader(text)) { chunk ->
            index++
            val serial = index.toString().padStart(3, '0')
            val title = "$sourceLabel · Chunk $serial/${total.toString().padStart(3, '0')} · $timeLabel"
            onSaved(store.save(null, title, chunk, pinned = false, savedAt = capturedAt))
            true
        }
        return index
    }
}
