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
    private val maxCacheBytes = 24L * 1024L * 1024L // 24 MB

    fun getCacheDir(): File = cacheDir

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
