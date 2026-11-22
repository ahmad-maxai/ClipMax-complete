package com.clipmax.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * App-private media cache manager for stickers and GIFs stored under filesDir/ime_media_cache/.
 * Enforces a 24 MB LRU byte cap and provides size inspection and clearing.
 */
internal class MediaCacheManager(context: Context) {
    private val cacheDir = File(context.filesDir, "ime_media_cache").apply {
        mkdirs()
    }
    private val maxCacheBytes = 24L * 1024L * 1024L

    fun getCacheDir(): File = cacheDir

    fun getCachedFile(targetFileName: String): File? {
        val target = File(cacheDir, targetFileName)
        return if (target.isFile && target.exists() && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            target
        } else {
            null
        }
    }

    fun copyFileToCache(sourceFile: File, targetFileName: String): File? {
        getCachedFile(targetFileName)?.let { return it }
        enforceLruCap()
        val target = File(cacheDir, targetFileName)
        return runCatching {
            sourceFile.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            if (target.length() > 0L) target else null
        }.getOrNull()
    }

    fun ensureAssetCached(assetManager: android.content.res.AssetManager, assetPath: String, targetFileName: String): File? {
        getCachedFile(targetFileName)?.let { return it }
        enforceLruCap()
        val target = File(cacheDir, targetFileName)
        return runCatching {
            assetManager.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            if (target.length() > 0L) target else null
        }.getOrNull()
    }

    fun ensureWebpFromPngCached(pngFile: File, targetWebpFileName: String): File? {
        getCachedFile(targetWebpFileName)?.let { return it }
        enforceLruCap()
        val target = File(cacheDir, targetWebpFileName)
        return runCatching {
            val bmp = android.graphics.BitmapFactory.decodeFile(pngFile.absolutePath) ?: return@runCatching null
            try {
                val fmt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Bitmap.CompressFormat.WEBP
                }
                FileOutputStream(target).use { out ->
                    bmp.compress(fmt, 95, out)
                }
            } finally {
                bmp.recycle()
            }
            if (target.length() > 0L) target else null
        }.getOrNull()
    }

    fun cacheSizeBytes(): Long {
        return runCatching {
            cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun clearCache() {
        runCatching {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    fun downloadAndCache(urlString: String, targetFileName: String): File? {
        val target = File(cacheDir, targetFileName)
        if (target.exists() && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        enforceLruCap()
        return runCatching {
            val url = URL(urlString)
            if (url.protocol.lowercase() != "https") return@runCatching null
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                if (target.length() > 0L) target else null
            } else {
                null
            }
        }.getOrNull()
    }

    private fun enforceLruCap() {
        runCatching {
            val files = cacheDir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toMutableList()
            var currentSize = files.sumOf { it.length() }
            while (currentSize > maxCacheBytes && files.isNotEmpty()) {
                val oldest = files.removeAt(0)
                val len = oldest.length()
                if (oldest.delete()) {
                    currentSize -= len
                }
            }
        }
    }
}
