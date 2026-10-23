package com.clipmax.app

import java.io.IOException
import java.io.Reader

/** Streams Unicode text in bounded chunks, never splitting a valid surrogate pair between chunks. */
internal object TextChunker {
    /** Maximum UTF-16 code units per stored capture card and InputConnection commit. */
    const val DEFAULT_CHUNK_CHARS = 50_000

    /** Counts the exact greedy chunk total without allocating chunk strings. */
    fun chunkCount(text: CharSequence, chunkChars: Int = DEFAULT_CHUNK_CHARS): Int {
        require(chunkChars >= 2) { "Chunk size must be at least two UTF-16 code units." }
        var chunks = 0
        var used = 0
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val width = if (Character.isHighSurrogate(first) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) 2 else 1
            if (used + width > chunkChars) {
                chunks++
                used = 0
            }
            used += width
            index += width
        }
        return chunks + if (used > 0) 1 else 0
    }

    /** Reads a host-provided selection CharSequence without making an additional full-size String copy. */
    fun reader(source: CharSequence): Reader = object : Reader() {
        private var position = 0
        private var closed = false

        override fun read(target: CharArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("Reader is closed")
            if (offset < 0 || length < 0 || length > target.size - offset) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (position >= source.length) return -1
            val count = minOf(length, source.length - position)
            for (i in 0 until count) target[offset + i] = source[position + i]
            position += count
            return count
        }

        override fun close() { closed = true }
    }

    /** Returns false when cancelled or when [consume] declines a chunk; true on complete EOF. */
    fun forEachChunk(
        reader: Reader,
        chunkChars: Int = DEFAULT_CHUNK_CHARS,
        isCancelled: () -> Boolean = { false },
        consume: (String) -> Boolean
    ): Boolean {
        require(chunkChars >= 2) { "Chunk size must be at least two UTF-16 code units." }
        val buffer = CharArray(chunkChars)
        var pendingHighSurrogate: Char? = null
        while (!isCancelled()) {
            // Reserve room for a high surrogate carried over from the previous read.
            val readLimit = if (pendingHighSurrogate != null) chunkChars - 1 else chunkChars
            val count = reader.read(buffer, 0, readLimit)
            if (count < 0) {
                val trailing = pendingHighSurrogate ?: return true
                return consume(trailing.toString())
            }
            if (count == 0) continue
            var chunk = buildString(count + 1) {
                pendingHighSurrogate?.let(::append)
                append(buffer, 0, count)
            }
            pendingHighSurrogate = null
            if (chunk.isNotEmpty() && Character.isHighSurrogate(chunk.last())) {
                pendingHighSurrogate = chunk.last()
                chunk = chunk.dropLast(1)
            }
            if (chunk.isNotEmpty() && !consume(chunk)) return false
        }
        return false
    }
}
