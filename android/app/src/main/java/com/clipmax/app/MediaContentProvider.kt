package com.clipmax.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Secure read-only ContentProvider for sharing local sticker and GIF files with target editors
 * via InputConnection.commitContent() (API 26+).
 * Enforces strict canonical path confinement within filesDir/ime_media_cache/.
 */
class MediaContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFileFromUri(uri) ?: throw FileNotFoundException("File not found for URI: $uri")
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = arrayOfNulls<Any>(cols.size)
        val resolvedMime = getType(uri)
        for (i in cols.indices) {
            row[i] = when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                "mime_type" -> resolvedMime
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String {
        val file = resolveFileFromUri(uri) ?: return "application/octet-stream"
        return when {
            file.name.endsWith(".png", ignoreCase = true) -> "image/png"
            file.name.endsWith(".gif", ignoreCase = true) -> "image/gif"
            file.name.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/*"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolveFileFromUri(uri) ?: throw FileNotFoundException("File not found for URI: $uri")
        if (!mode.contains("r")) {
            throw SecurityException("MediaContentProvider is strictly read-only.")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolveFileFromUri(uri: Uri): File? {
        val ctx = context ?: return null
        val cacheDir = File(ctx.filesDir, "ime_media_cache")
        val baseCanonical = runCatching { cacheDir.canonicalPath }.getOrNull() ?: return null

        val pathSegment = uri.lastPathSegment ?: return null
        val resolved = File(cacheDir, pathSegment)
        val targetCanonical = runCatching { resolved.canonicalPath }.getOrNull() ?: return null

        if (!targetCanonical.startsWith(baseCanonical + File.separator) && targetCanonical != baseCanonical) {
            return null
        }
        return if (resolved.isFile && resolved.exists()) resolved else null
    }

    companion object {
        const val AUTHORITY = "com.clipmax.app.mediaprovider"

        fun uriForFile(fileName: String): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(fileName)
                .build()
        }
    }
}
