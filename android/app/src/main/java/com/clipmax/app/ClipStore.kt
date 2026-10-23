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
    val contentFile: String
)

data class ClipDraft(val itemId: String?, val title: String, val content: String)

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
        private const val META_VERSION = 1
        private const val DRAFT_MAGIC = 0x434D4452 // "CMDR"
    }

    private val base = File(root, "clipmax_store")
    private val metadataDir = File(base, "metadata")
    private val contentDir = File(base, "content")
    private val draftDir = File(base, "draft")

    init {
        check(metadataDir.mkdirs() || metadataDir.isDirectory) { "Cannot create ClipMax metadata storage" }
        check(contentDir.mkdirs() || contentDir.isDirectory) { "Cannot create ClipMax content storage" }
        check(draftDir.mkdirs() || draftDir.isDirectory) { "Cannot create ClipMax draft storage" }
    }

    @Synchronized
    fun listItems(): List<ClipItem> = metadataDir.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.name.endsWith(".bin") }
        .mapNotNull { runCatching { readMetadata(it) }.getOrNull() }
        .filter { safeContentName(it.contentFile) }
        .filter { File(contentDir, it.contentFile).isFile }
        .toList()

    @Synchronized
    fun getItem(id: String): ClipItem? {
        if (!safeId(id)) return null
        val file = File(metadataDir, "$id.bin")
        return runCatching { readMetadata(file) }.getOrNull()
            ?.takeIf { safeContentName(it.contentFile) && File(contentDir, it.contentFile).isFile }
    }

    @Synchronized
    fun save(id: String?, title: String, content: String, pinned: Boolean? = null, savedAt: Long? = null): ClipItem {
        return saveFromReader(id, title, StringReader(content), pinned, savedAt)
    }

    /** Saves a selected text Reader incrementally, without assembling a second full String in ClipMax. */
    @Synchronized
    fun saveFromReader(id: String?, title: String, reader: Reader, pinned: Boolean? = null, savedAt: Long? = null): ClipItem {
        val cleanTitle = title.trim().ifEmpty { "Untitled" }
        require(cleanTitle.length <= 200) { "Title must be 200 characters or fewer." }
        val prior = id?.let { getItem(it) ?: throw IllegalArgumentException("The item no longer exists.") }
        val stableId = prior?.id ?: UUID.randomUUID().toString()
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
            contentFile = contentName
        )
        try {
            writeMetadataAtomic(item)
        } catch (e: Exception) {
            // Keep the old metadata/content valid; the newly written version is an orphan.
            throw e
        }
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

    @Synchronized
    fun rename(id: String, title: String): ClipItem {
        val old = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
        val cleanTitle = title.trim().ifEmpty { "Untitled" }
        require(cleanTitle.length <= 200) { "Title must be 200 characters or fewer." }
        return old.copy(title = cleanTitle, modifiedAt = System.currentTimeMillis()).also(::writeMetadataAtomic)
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean): ClipItem {
        val old = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
        return old.copy(pinned = pinned, modifiedAt = System.currentTimeMillis()).also(::writeMetadataAtomic)
    }

    @Synchronized
    fun duplicate(id: String): ClipItem {
        val old = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
        val text = readContent(old)
        return save(null, "${old.title} (copy)".take(200), text, false)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val item = getItem(id) ?: return false
        val metaFile = File(metadataDir, "$id.bin")
        if (!metaFile.delete()) throw IllegalStateException("Could not delete item metadata.")
        File(contentDir, item.contentFile).delete()
        return true
    }

    @Synchronized
    fun deleteAll() {
        metadataDir.listFiles().orEmpty().forEach { if (!it.delete()) throw IllegalStateException("Could not clear saved-item metadata.") }
        contentDir.listFiles().orEmpty().forEach { it.delete() }
        clearDraft()
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

    @Synchronized
    fun search(query: String): List<ClipItem> {
        val q = query.trim()
        if (q.isEmpty()) return listItems()
        return listItems().filter { item ->
            item.title.contains(q, ignoreCase = true) || containsText(File(contentDir, item.contentFile), q)
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

    @Synchronized
    fun totalBytes(): Long = listItems().sumOf { it.sizeBytes }

    /** Opens a saved item's UTF-8 text for bounded-memory consumers such as the insert keyboard. */
    @Synchronized
    fun openTextReader(id: String): BufferedReader {
        val item = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
        val file = File(contentDir, item.contentFile)
        require(file.length() <= MAX_ITEM_BYTES) { "The saved text exceeds ClipMax's supported size." }
        return BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8), 16 * 1024)
    }

    @Synchronized
    fun writeTextTo(id: String, output: OutputStream) {
        val item = getItem(id) ?: throw IllegalArgumentException("The item no longer exists.")
        FileInputStream(File(contentDir, item.contentFile)).use { input -> input.copyTo(output, 32 * 1024) }
    }

    fun importText(input: InputStream, title: String): ClipItem {
        val data = readBounded(input, MAX_ITEM_BYTES)
        return save(null, title, String(data, StandardCharsets.UTF_8))
    }

    @Synchronized
    fun backup(output: OutputStream) {
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

    /** Merge a ClipMax ZIP backup, assigning a new stable ID if one already exists. */
    @Synchronized
    fun restore(input: InputStream): Int {
        val staging = File(base, "restore-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Cannot create temporary restore storage." }
        var unpacked = 0L
        try {
            ZipInputStream(BufferedInputStream(input, 32 * 1024)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val safePath = entry.name
                    val valid = safePath.matches(Regex("metadata/[a-fA-F0-9-]{20,40}\\.bin")) ||
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
            var restored = 0
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
                restored++
            }
            return restored
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun saveDraft(itemId: String?, title: String, content: String) {
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

    @Synchronized
    fun loadDraft(): ClipDraft? = runCatching {
        val meta = File(draftDir, "draft.bin")
        val contentFile = File(draftDir, "content.txt")
        if (!meta.isFile || !contentFile.isFile || contentFile.length() > MAX_ITEM_BYTES) return null
        val (id, title) = DataInputStream(FileInputStream(meta)).use { input ->
            require(input.readInt() == DRAFT_MAGIC)
            input.readUTF() to input.readUTF()
        }
        val content = InputStreamReader(FileInputStream(contentFile), StandardCharsets.UTF_8).use { it.readText() }
        ClipDraft(id.ifEmpty { null }, title, content)
    }.getOrNull()

    @Synchronized
    fun clearDraft() {
        File(draftDir, "draft.bin").delete()
        File(draftDir, "draft.tmp").delete()
        File(draftDir, "content.txt").delete()
        File(draftDir, "content.tmp").delete()
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
        out.flush()
    }

    private fun readMetadata(file: File): ClipItem {
        require(file.isFile && file.length() <= 4096L) { "Invalid metadata file." }
        return DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == META_MAGIC && input.readInt() == META_VERSION) { "Unsupported metadata." }
            val item = ClipItem(
                id = input.readUTF(), title = input.readUTF(), createdAt = input.readLong(),
                modifiedAt = input.readLong(), characterCount = input.readInt(), sizeBytes = input.readLong(),
                pinned = input.readBoolean(), contentFile = input.readUTF()
            )
            require(safeId(item.id) && item.title.length <= 200 && item.characterCount >= 0 &&
                item.sizeBytes in 0..MAX_ITEM_BYTES && safeContentName(item.contentFile)) { "Invalid metadata values." }
            item
        }
    }

    private fun safeId(value: String): Boolean = value.matches(Regex("[a-fA-F0-9-]{20,40}"))
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
