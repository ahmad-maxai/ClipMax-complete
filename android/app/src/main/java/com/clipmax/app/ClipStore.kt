package com.clipmax.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A compact metadata record; large text is always kept in a separate UTF-8 file. */
data class ClipItem(
    val id: String,
    val title: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val characterCount: Int,
    val sizeBytes: Long,
    val pinned: Boolean,
    val contentFile: String,
    val isExpiryLocked: Boolean = false
)

data class ClipDraft(val itemId: String?, val title: String, val content: String)

data class MergeResult(
    val mergedItem: ClipItem,
    val sourceItem: ClipItem,
    val sourceText: String,
    val targetItem: ClipItem,
    val targetText: String
)

data class SplitResult(
    val originalItem: ClipItem,
    val originalText: String,
    val part1Item: ClipItem,
    val part1Text: String,
    val part2Item: ClipItem,
    val part2Text: String
)

data class RestoreRecord(
    val id: String,
    val title: String,
    val content: String,
    val pinned: Boolean,
    val createdAt: Long,
    val isExpiryLocked: Boolean = false
)

/**
 * Local-only storage. Content versions are written first, then a small metadata record is
 * atomically replaced. A crash before metadata commit leaves an unreferenced file, not a
 * truncated saved item. This class has no Android dependencies and can be JVM unit-tested.
 */
class ClipStore(root: File) {
    companion object {
        const val MAX_ITEM_BYTES = 16L * 1024L * 1024L
        const val MAX_BACKUP_BYTES = 128L * 1024L * 1024L
        private const val META_MAGIC = 0x434D4158 // "CMAX"
        private const val META_VERSION = 2
        private const val META_VERSION_V1 = 1
        private const val DRAFT_MAGIC = 0x434D4452 // "CMDR"

        private val storeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val changeListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        private val changeVersionCounter = java.util.concurrent.atomic.AtomicLong(1L)

        fun currentChangeVersion(): Long = changeVersionCounter.get()

        private fun lockFor(base: File): Any {
            val key = runCatching { base.canonicalPath }.getOrElse { base.absolutePath }
            return storeLocks.computeIfAbsent(key) { Any() }
        }

        fun addChangeListener(listener: () -> Unit) {
            changeListeners.add(listener)
        }

        fun removeChangeListener(listener: () -> Unit) {
            changeListeners.remove(listener)
        }

        private fun notifyChanged() {
            changeVersionCounter.incrementAndGet()
            changeListeners.forEach { listener ->
                runCatching { listener() }
            }
        }
    }

    private val base = File(root, "clipmax_store")
    private val storeLock = lockFor(base)
    private val metadataDir = File(base, "metadata")
    private val contentDir = File(base, "content")
    private val draftDir = File(base, "draft")

    init {
        synchronized(storeLock) {
            check(metadataDir.mkdirs() || metadataDir.isDirectory) { "Cannot create ClipMax metadata storage" }
            check(contentDir.mkdirs() || contentDir.isDirectory) { "Cannot create ClipMax content storage" }
            check(draftDir.mkdirs() || draftDir.isDirectory) { "Cannot create ClipMax draft storage" }
        }
    }

    fun listItems(): List<ClipItem> = synchronized(storeLock) {
        metadataDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".bin") }
            .mapNotNull { runCatching { readMetadata(it) }.getOrNull() }
            .filter { safeContentName(it.contentFile) }
            .filter { File(contentDir, it.contentFile).isFile }
            .sortedWith(
                compareByDescending<ClipItem> { it.pinned }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.title }
            )
            .toList()
    }

    fun getItem(id: String): ClipItem? = synchronized(storeLock) {
        if (!safeId(id)) return null
        val file = File(metadataDir, "$id.bin")
        runCatching { readMetadata(file) }.getOrNull()
            ?.takeIf { safeContentName(it.contentFile) && File(contentDir, it.contentFile).isFile }
    }

    fun save(
        id: String?,
        title: String,
        content: String,
        pinned: Boolean? = null,
        savedAt: Long? = null,
        isExpiryLocked: Boolean? = null
    ): ClipItem {
        return saveFromReader(id, title, StringReader(content), pinned, savedAt, isExpiryLocked)
    }

    /** Saves a selected text Reader incrementally, without assembling a second full String in ClipMax. */
    fun saveFromReader(
        id: String?,
        title: String,
        reader: Reader,
        pinned: Boolean? = null,
        savedAt: Long? = null,
        isExpiryLocked: Boolean? = null
    ): ClipItem {
        val saved = synchronized(storeLock) {
            saveInternal(id, title, reader, pinned, savedAt, isExpiryLocked)
        }
        notifyChanged()
        return saved
    }

    private fun saveInternal(
        id: String?,
        title: String,
        reader: Reader,
        pinned: Boolean? = null,
        savedAt: Long? = null,
        isExpiryLocked: Boolean? = null
    ): ClipItem {
        val cleanTitle = title.trim().ifEmpty { "Untitled" }
        require(cleanTitle.length <= 200) { "Title must be 200 characters or fewer." }
        val prior = id?.let { getItem(it) }
        val stableId = prior?.id ?: (if (id != null && safeId(id)) id else UUID.randomUUID().toString())
        val now = savedAt ?: System.currentTimeMillis()
        val contentName = "$stableId-$now-${UUID.randomUUID().toString().take(8)}.txt"
        val finalContent = File(contentDir, contentName)
        val tempContent = File(contentDir, ".$contentName.tmp")
        var characterCount = 0
        var byteCount = 0L
        try {
            FileOutputStream(tempContent).use { raw ->
                val limited = LimitedOutputStream(raw, MAX_ITEM_BYTES)
                BufferedWriter(OutputStreamWriter(limited, StandardCharsets.UTF_8), 32 * 1024).use { writer ->
                    characterCount = reader.use { source -> writeReaderContent(source, writer) }
                    writer.flush()
                    byteCount = limited.bytesWritten
                    raw.fd.sync()
                }
            }
            moveReplace(tempContent, finalContent)
        } catch (e: Exception) {
            tempContent.delete()
            throw e
        }
        val item = ClipItem(
            id = stableId,
            title = cleanTitle,
            createdAt = prior?.createdAt ?: now,
            modifiedAt = now,
            characterCount = characterCount,
            sizeBytes = byteCount,
            pinned = pinned ?: prior?.pinned ?: false,
            contentFile = contentName,
            isExpiryLocked = isExpiryLocked ?: prior?.isExpiryLocked ?: false
        )
        writeMetadataAtomic(item)
        prior?.let { old -> if (old.contentFile != contentName) File(contentDir, old.contentFile).delete() }
        return item
    }

    private fun writeReaderContent(reader: Reader, writer: BufferedWriter): Int {
        val buffer = CharArray(16 * 1024)
        var pendingHigh: Char? = null
        var codePoints = 0
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            val chunk = StringBuilder(count + 1)
            pendingHigh?.let(chunk::append)
            chunk.append(buffer, 0, count)
            pendingHigh = null
            if (chunk.isNotEmpty() && Character.isHighSurrogate(chunk.last())) {
                pendingHigh = chunk.last()
                chunk.setLength(chunk.length - 1)
            }
            if (chunk.isNotEmpty()) {
                val text = chunk.toString()
                writer.write(text)
                codePoints += text.codePointCount(0, text.length)
            }
        }
        pendingHigh?.let { writer.write(it.code); codePoints++ }
        return codePoints
    }

    private class LimitedOutputStream(private val destination: OutputStream, private val limit: Long) : OutputStream() {
        var bytesWritten: Long = 0
            private set

        override fun write(value: Int) {
            if (bytesWritten >= limit) throw IllegalArgumentException("This item is larger than ClipMax's supported ${limit / (1024 * 1024)} MiB per-item limit. No item was saved.")
            destination.write(value)
            bytesWritten++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length > limit - bytesWritten) throw IllegalArgumentException("This item is larger than ClipMax's supported ${limit / (1024 * 1024)} MiB per-item limit. No item was saved.")
            destination.write(buffer, offset, length)
            bytesWritten += length
        }

        override fun flush() = destination.flush()
        override fun close() = destination.close()
    }

    fun rename(id: String, title: String): ClipItem {
        val updated = synchronized(storeLock) {
            val old = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
            val cleanTitle = title.trim().ifEmpty { "Untitled" }
            require(cleanTitle.length <= 200) { "Title must be 200 characters or fewer." }
            old.copy(title = cleanTitle, modifiedAt = System.currentTimeMillis()).also(::writeMetadataAtomic)
        }
        notifyChanged()
        return updated
    }

    fun setPinned(id: String, pinned: Boolean): ClipItem {
        val updated = synchronized(storeLock) {
            val old = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
            old.copy(pinned = pinned, modifiedAt = System.currentTimeMillis()).also(::writeMetadataAtomic)
        }
        notifyChanged()
        return updated
    }

    fun setExpiryLocked(id: String, locked: Boolean): ClipItem {
        val updated = synchronized(storeLock) {
            val old = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
            old.copy(isExpiryLocked = locked, modifiedAt = System.currentTimeMillis()).also(::writeMetadataAtomic)
        }
        notifyChanged()
        return updated
    }

    fun updateMetadataOnly(
        id: String,
        title: String,
        pinned: Boolean,
        modifiedAt: Long? = null,
        isExpiryLocked: Boolean? = null
    ): ClipItem? {
        val updated = synchronized(storeLock) {
            val old = getItem(id) ?: return@synchronized null
            val cleanTitle = title.trim().ifEmpty { "Untitled" }
            require(cleanTitle.length <= 200) { "Title must be 200 characters or fewer." }
            val nextLocked = isExpiryLocked ?: old.isExpiryLocked
            if (old.title == cleanTitle &&
                old.pinned == pinned &&
                old.isExpiryLocked == nextLocked &&
                (modifiedAt == null || old.modifiedAt == modifiedAt)
            ) {
                return@synchronized old
            }
            old.copy(
                title = cleanTitle,
                pinned = pinned,
                isExpiryLocked = nextLocked,
                modifiedAt = modifiedAt ?: System.currentTimeMillis()
            ).also(::writeMetadataAtomic)
        }
        if (updated != null) notifyChanged()
        return updated
    }

    fun duplicate(id: String): ClipItem {
        val (title, text) = synchronized(storeLock) {
            val old = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
            old.title to readContent(old)
        }
        return save(null, "$title (copy)".take(200), text, false)
    }

    fun delete(id: String): Boolean {
        val removed = synchronized(storeLock) {
            deleteInternal(id)
        }
        if (removed) notifyChanged()
        return removed
    }

    private fun deleteInternal(id: String): Boolean {
        val item = getItem(id) ?: return false
        val metaFile = File(metadataDir, "$id.bin")
        if (!metaFile.delete()) throw IllegalStateException("Could not delete item metadata.")
        File(contentDir, item.contentFile).delete()
        return true
    }

    fun deleteAll() {
        synchronized(storeLock) {
            metadataDir.listFiles().orEmpty().forEach { if (!it.delete()) throw IllegalStateException("Could not clear saved-item metadata.") }
            contentDir.listFiles().orEmpty().forEach { it.delete() }
            clearDraft()
        }
        notifyChanged()
    }

    fun readContent(item: ClipItem): String {
        require(safeContentName(item.contentFile)) { "Invalid content reference." }
        val file = File(contentDir, item.contentFile)
        require(file.isFile) { "The saved text file is missing." }
        require(file.length() <= MAX_ITEM_BYTES) { "The saved text exceeds ClipMax's supported size." }
        return InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8).use { reader ->
            val out = StringBuilder(minOf(file.length().toInt(), 1024 * 1024))
            val buffer = CharArray(16 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                out.append(buffer, 0, count)
            }
            out.toString()
        }
    }

    fun merge(sourceId: String, targetId: String): MergeResult {
        val result = synchronized(storeLock) {
            require(sourceId != targetId) { "Cannot merge a card with itself." }
            val source = getItem(sourceId) ?: throw IllegalArgumentException("Source item not found: $sourceId")
            val target = getItem(targetId) ?: throw IllegalArgumentException("Target item not found: $targetId")
            val sourceText = readContent(source)
            val targetText = readContent(target)
            val mergedText = "$sourceText\n$targetText"
            val mergedTitle = source.title.take(180)
            val mergedItem = saveInternal(
                id = null,
                title = mergedTitle,
                reader = StringReader(mergedText),
                pinned = source.pinned || target.pinned,
                isExpiryLocked = source.isExpiryLocked || target.isExpiryLocked
            )
            deleteInternal(sourceId)
            deleteInternal(targetId)
            MergeResult(mergedItem, source, sourceText, target, targetText)
        }
        notifyChanged()
        return result
    }

    fun findSafeMidpoint(text: String): Int {
        if (text.length < 2) return -1
        val targetMid = text.length / 2

        val window = minOf(40, (text.length * 0.15f).toInt()).coerceAtLeast(3)
        val searchStart = (targetMid - window).coerceAtLeast(1)
        val searchEnd = (targetMid + window).coerceAtMost(text.length - 1)

        var bestBoundary = -1
        var bestDistance = Int.MAX_VALUE

        // 1. Prefer newline
        for (i in searchStart..searchEnd) {
            if (text[i] == '\n') {
                val dist = kotlin.math.abs(i - targetMid)
                if (dist < bestDistance) {
                    bestDistance = dist
                    bestBoundary = i + 1
                }
            }
        }

        // 2. If no newline nearby, prefer whitespace
        if (bestBoundary == -1) {
            for (i in searchStart..searchEnd) {
                if (Character.isWhitespace(text[i])) {
                    val dist = kotlin.math.abs(i - targetMid)
                    if (dist < bestDistance) {
                        bestDistance = dist
                        bestBoundary = i + 1
                    }
                }
            }
        }

        var chosen = if (bestBoundary in 1 until text.length) bestBoundary else targetMid

        // Guarantee no surrogate pair is split
        if (chosen in 1 until text.length) {
            if (Character.isHighSurrogate(text[chosen - 1]) && Character.isLowSurrogate(text[chosen])) {
                chosen++
            }
        }

        return chosen.coerceIn(1, text.length - 1)
    }

    fun split(id: String): SplitResult? {
        val result = synchronized(storeLock) {
            val original = getItem(id) ?: return@synchronized null
            val fullText = readContent(original)
            if (fullText.length < 2) return@synchronized null
            val splitIndex = findSafeMidpoint(fullText)
            if (splitIndex <= 0 || splitIndex >= fullText.length) return@synchronized null

            val part1Text = fullText.substring(0, splitIndex)
            val part2Text = fullText.substring(splitIndex)
            if (part1Text.isEmpty() || part2Text.isEmpty()) return@synchronized null

            val baseTitle = original.title.replace(Regex("\\s*\\(Part \\d+\\)$"), "")
            val title1 = "$baseTitle (Part 1)".take(180)
            val title2 = "$baseTitle (Part 2)".take(180)

            val item1 = saveInternal(
                null,
                title1,
                StringReader(part1Text),
                pinned = original.pinned,
                savedAt = original.createdAt,
                isExpiryLocked = original.isExpiryLocked
            )
            val item2 = saveInternal(
                null,
                title2,
                StringReader(part2Text),
                pinned = original.pinned,
                savedAt = original.createdAt + 1L,
                isExpiryLocked = original.isExpiryLocked
            )
            deleteInternal(id)
            SplitResult(original, fullText, item1, part1Text, item2, part2Text)
        }
        if (result != null) notifyChanged()
        return result
    }

    /**
     * Atomically applies an Undo or Redo snapshot transaction under [storeLock]
     * with a single change notification so listeners never observe partial state.
     */
    fun applyHistoryTransaction(deleteIds: List<String>, restoreRecords: List<RestoreRecord>) {
        synchronized(storeLock) {
            for (delId in deleteIds) {
                deleteInternal(delId)
            }
            for (rec in restoreRecords) {
                saveInternal(
                    id = rec.id,
                    title = rec.title,
                    reader = StringReader(rec.content),
                    pinned = rec.pinned,
                    savedAt = rec.createdAt,
                    isExpiryLocked = rec.isExpiryLocked
                )
            }
        }
        notifyChanged()
    }

    fun search(query: String): List<ClipItem> {
        val q = query.trim()
        if (q.isEmpty()) return listItems()
        return synchronized(storeLock) {
            listItems().filter { item ->
                item.title.contains(q, ignoreCase = true) || containsText(File(contentDir, item.contentFile), q)
            }
        }
    }

    private fun containsText(file: File, query: String): Boolean {
        val needle = query.lowercase(Locale.ROOT)
        if (needle.isEmpty()) return true
        var overlap = ""
        val buffer = CharArray(8192)
        BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8), 16 * 1024).use { reader ->
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) return false
                val block = (overlap + String(buffer, 0, n)).lowercase(Locale.ROOT)
                if (block.contains(needle)) return true
                overlap = if (needle.length <= 1) "" else block.takeLast(minOf(needle.length - 1, block.length))
            }
        }
    }

    fun totalBytes(): Long = listItems().sumOf { it.sizeBytes }

    /** Opens a saved item's UTF-8 text for bounded-memory consumers such as the insert keyboard. */
    fun openTextReader(id: String): BufferedReader = synchronized(storeLock) {
        val item = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
        val file = File(contentDir, item.contentFile)
        require(file.length() <= MAX_ITEM_BYTES) { "The saved text exceeds ClipMax's supported size." }
        BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8), 16 * 1024)
    }

    fun writeTextTo(id: String, output: OutputStream) {
        synchronized(storeLock) {
            val item = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
            FileInputStream(File(contentDir, item.contentFile)).use { input -> input.copyTo(output, 32 * 1024) }
        }
    }

    fun importText(input: InputStream, title: String): ClipItem {
        val data = readBounded(input, MAX_ITEM_BYTES)
        return save(null, title, String(data, StandardCharsets.UTF_8))
    }

    fun backup(output: OutputStream) {
        synchronized(storeLock) {
            ZipOutputStream(BufferedOutputStream(output, 32 * 1024)).use { zip ->
                listItems().forEach { item ->
                    val metaBytes = ByteArrayOutputStream().also { writeMetadata(item, it) }.toByteArray()
                    zip.putNextEntry(ZipEntry("metadata/${item.id}.bin"))
                    zip.write(metaBytes)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("content/${item.contentFile}"))
                    BufferedInputStream(FileInputStream(File(contentDir, item.contentFile))).use { it.copyTo(zip, 32 * 1024) }
                    zip.closeEntry()
                }
                zip.finish()
            }
        }
    }

    /** Merge a ClipMax ZIP backup, assigning a new stable ID if one already exists. */
    fun restore(input: InputStream): Int {
        val restored = synchronized(storeLock) {
            val staging = File(base, "restore-${UUID.randomUUID()}")
            check(staging.mkdirs()) { "Cannot create temporary restore storage." }
            var unpacked = 0L
            try {
                ZipInputStream(BufferedInputStream(input, 32 * 1024)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        val safePath = entry.name
                        val valid = safePath.matches(Regex("metadata/[A-Za-z0-9._-]{1,64}\\.(bin|json)")) ||
                            safePath.matches(Regex("content/[A-Za-z0-9._-]{1,180}\\.txt"))
                        if (!valid) throw IllegalArgumentException("This backup contains an unsupported file path.")
                        val target = File(staging, safePath)
                        target.parentFile?.mkdirs()
                        val limit = if (safePath.startsWith("metadata/")) 4096L else MAX_ITEM_BYTES
                        val copied = FileOutputStream(target).use { output -> copyBounded(zip, output, limit) }
                        unpacked += copied
                        if (unpacked > MAX_BACKUP_BYTES) throw IllegalArgumentException("The backup exceeds ClipMax's 128 MiB restore limit.")
                        zip.closeEntry()
                    }
                }
                val stagedMeta = File(staging, "metadata")
                var count = 0
                stagedMeta.listFiles().orEmpty().forEach { metaFile ->
                    val archived = runCatching { readMetadata(metaFile) }.getOrNull() ?: return@forEach
                    if (!safeContentName(archived.contentFile)) return@forEach
                    val archivedContent = File(staging, "content/${archived.contentFile}")
                    if (!archivedContent.isFile || archivedContent.length() > MAX_ITEM_BYTES) return@forEach
                    val existing = getItem(archived.id)
                    val id = if (existing == null) archived.id else UUID.randomUUID().toString()
                    val newContentName = "$id-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.txt"
                    val newContent = File(contentDir, newContentName)
                    val tempContent = File(contentDir, ".$newContentName.tmp")
                    FileInputStream(archivedContent).use { src ->
                        FileOutputStream(tempContent).use { dst -> src.copyTo(dst, 32 * 1024); dst.fd.sync() }
                    }
                    moveReplace(tempContent, newContent)
                    val restoredItem = archived.copy(
                        id = id,
                        title = if (existing == null) archived.title else "${archived.title} (restored)".take(200),
                        contentFile = newContentName
                    )
                    writeMetadataAtomic(restoredItem)
                    count++
                }
                count
            } finally {
                staging.deleteRecursively()
            }
        }
        if (restored > 0) notifyChanged()
        return restored
    }

    fun saveDraft(itemId: String?, title: String, content: String) {
        synchronized(storeLock) {
            val bytes = utf8Length(content)
            if (bytes > MAX_ITEM_BYTES) return
            val textTmp = File(draftDir, "content.tmp")
            val textFinal = File(draftDir, "content.txt")
            FileOutputStream(textTmp).use { raw ->
                BufferedWriter(OutputStreamWriter(raw, StandardCharsets.UTF_8), 16 * 1024).use { w ->
                    w.write(content); w.flush(); raw.fd.sync()
                }
            }
            moveReplace(textTmp, textFinal)
            val metaTmp = File(draftDir, "draft.tmp")
            DataOutputStream(FileOutputStream(metaTmp)).use { out ->
                out.writeInt(DRAFT_MAGIC)
                out.writeUTF(itemId.orEmpty())
                out.writeUTF(title.take(200))
                out.writeLong(bytes)
                out.flush()
            }
            moveReplace(metaTmp, File(draftDir, "draft.bin"))
        }
    }

    fun loadDraft(): ClipDraft? = synchronized(storeLock) {
        runCatching {
            val meta = File(draftDir, "draft.bin")
            val contentFile = File(draftDir, "content.txt")
            if (!meta.isFile || !contentFile.isFile || contentFile.length() > MAX_ITEM_BYTES) return@synchronized null
            val (id, title) = DataInputStream(FileInputStream(meta)).use { input ->
                require(input.readInt() == DRAFT_MAGIC)
                input.readUTF() to input.readUTF()
            }
            val content = InputStreamReader(FileInputStream(contentFile), StandardCharsets.UTF_8).use { it.readText() }
            ClipDraft(id.ifEmpty { null }, title, content)
        }.getOrNull()
    }

    fun clearDraft() {
        synchronized(storeLock) {
            File(draftDir, "draft.bin").delete()
            File(draftDir, "draft.tmp").delete()
            File(draftDir, "content.txt").delete()
            File(draftDir, "content.tmp").delete()
        }
    }

    private fun writeMetadataAtomic(item: ClipItem) {
        require(safeId(item.id) && safeContentName(item.contentFile)) { "Invalid item metadata." }
        val temp = File(metadataDir, ".${item.id}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { out ->
                    writeMetadata(item, out)
                    out.flush()
                    raw.fd.sync()
                }
            }
            moveReplace(temp, File(metadataDir, "${item.id}.bin"))
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun writeMetadata(item: ClipItem, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(META_MAGIC)
        out.writeInt(META_VERSION)
        out.writeUTF(item.id)
        out.writeUTF(item.title)
        out.writeLong(item.createdAt)
        out.writeLong(item.modifiedAt)
        out.writeInt(item.characterCount)
        out.writeLong(item.sizeBytes)
        out.writeBoolean(item.pinned)
        out.writeUTF(item.contentFile)
        out.writeBoolean(item.isExpiryLocked)
        out.flush()
    }

    private fun readMetadata(file: File): ClipItem {
        require(file.isFile && file.length() <= 4096L) { "Invalid metadata file." }
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == META_MAGIC) { "Unsupported metadata." }
            val version = input.readInt()
            require(version == META_VERSION_V1 || version == META_VERSION) { "Unsupported metadata version." }
            val id = input.readUTF()
            val title = input.readUTF()
            val createdAt = input.readLong()
            val modifiedAt = input.readLong()
            val characterCount = input.readInt()
            val sizeBytes = input.readLong()
            val pinned = input.readBoolean()
            val contentFile = input.readUTF()
            val isExpiryLocked = if (version >= META_VERSION) input.readBoolean() else false
            val item = ClipItem(
                id = id,
                title = title,
                createdAt = createdAt,
                modifiedAt = modifiedAt,
                characterCount = characterCount,
                sizeBytes = sizeBytes,
                pinned = pinned,
                contentFile = contentFile,
                isExpiryLocked = isExpiryLocked
            )
            require(safeId(item.id) && item.title.length <= 200 && item.characterCount >= 0 &&
                item.sizeBytes in 0..MAX_ITEM_BYTES && safeContentName(item.contentFile)) { "Invalid metadata values." }
            item
        }
    }

    private fun safeId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._-]{1,64}"))
    private fun safeContentName(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._-]{1,180}\\.txt")) && !value.startsWith(".")

    private fun moveReplace(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun utf8Length(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code <= 0x7f -> bytes++
                c.code <= 0x7ff -> bytes += 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { bytes += 4; i++ }
                Character.isSurrogate(c) -> bytes++ // Java UTF-8 encoder's replacement byte for malformed pairs.
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyBounded(input, output, maxBytes)
        return output.toByteArray()
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        var total = 0L
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IllegalArgumentException("File exceeds ClipMax's supported size limit.")
            output.write(buf, 0, n)
        }
        output.flush()
        return total
    }
}
