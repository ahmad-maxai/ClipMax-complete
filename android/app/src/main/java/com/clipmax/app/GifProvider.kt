package com.clipmax.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Modular GIF provider interface and Tenor v2 implementation with robust pure-Kotlin JSON
 * parsing, HTTPS-only security, hostname allowlist enforcement, and explicit configuration/error states.
 */
internal interface GifProvider {
    data class GifItem(
        val id: String,
        val title: String,
        val previewUrl: String,
        val gifUrl: String,
        val width: Int,
        val height: Int
    )

    sealed interface GifFetchResult {
        data class Success(val items: List<GifItem>) : GifFetchResult
        data class Unconfigured(val message: String) : GifFetchResult
        data class NetworkError(val error: String) : GifFetchResult
    }

    fun fetchFeatured(callback: (GifFetchResult) -> Unit)
    fun search(query: String, callback: (GifFetchResult) -> Unit)
}

internal class TenorV2GifProvider(
    private val context: Context,
    private var apiKey: String = "",
    private val clientKey: String = "clipmax_android"
) : GifProvider {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
    }

    private fun isInternetAllowed(): Boolean {
        return try {
            context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    override fun fetchFeatured(callback: (GifProvider.GifFetchResult) -> Unit) {
        val effectiveKey = apiKey.trim()
        if (!isInternetAllowed() || effectiveKey.isBlank()) {
            callback(GifProvider.GifFetchResult.Unconfigured("GIF search requires network permission and Tenor API key configuration."))
            return
        }
        val encodedKey = URLEncoder.encode(effectiveKey, "UTF-8")
        val encodedClient = URLEncoder.encode(clientKey, "UTF-8")
        val endpoint = "https://tenor.googleapis.com/v2/featured?key=$encodedKey&client_key=$encodedClient&limit=24&contentfilter=high&media_filter=gif,tinygif,nanogif,mediumgif"
        executeTenorRequest(endpoint, callback)
    }

    override fun search(query: String, callback: (GifProvider.GifFetchResult) -> Unit) {
        val effectiveKey = apiKey.trim()
        if (!isInternetAllowed() || effectiveKey.isBlank()) {
            callback(GifProvider.GifFetchResult.Unconfigured("GIF search requires network permission and Tenor API key configuration."))
            return
        }
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            fetchFeatured(callback)
            return
        }
        val encodedQ = URLEncoder.encode(trimmedQuery, "UTF-8")
        val encodedKey = URLEncoder.encode(effectiveKey, "UTF-8")
        val encodedClient = URLEncoder.encode(clientKey, "UTF-8")
        val endpoint = "https://tenor.googleapis.com/v2/search?q=$encodedQ&key=$encodedKey&client_key=$encodedClient&limit=24&contentfilter=high&media_filter=gif,tinygif,nanogif,mediumgif"
        executeTenorRequest(endpoint, callback)
    }

    private fun executeTenorRequest(
        endpoint: String,
        callback: (GifProvider.GifFetchResult) -> Unit
    ) {
        val url = validateUrl(endpoint)
        if (url == null) {
            callback(GifProvider.GifFetchResult.NetworkError("Invalid or unauthorized GIF URL endpoint."))
            return
        }
        gifExecutor.execute {
            var conn: HttpsURLConnection? = null
            try {
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code == 200) {
                    val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                    val items = parseTenorJsonResponse(responseBody)
                    mainHandler.post { callback(GifProvider.GifFetchResult.Success(items)) }
                } else {
                    mainHandler.post { callback(GifProvider.GifFetchResult.NetworkError("HTTP error $code")) }
                }
            } catch (e: Throwable) {
                mainHandler.post { callback(GifProvider.GifFetchResult.NetworkError(e.message ?: "Network error")) }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    companion object {
        private val ALLOWED_HOSTS = setOf(
            "tenor.googleapis.com",
            "media.tenor.com",
            "media1.tenor.com",
            "c.tenor.com"
        )

        private val gifExecutor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ClipMax-GIF-Net").apply { isDaemon = true }
        }

        internal val FEATURED_CATEGORIES: List<Pair<String, String>> = listOf(
            "featured" to "Featured",
            "recent" to "Recent",
            "happy" to "Happy",
            "love" to "Love",
            "haha" to "Haha",
            "wow" to "Wow",
            "thumbs up" to "Agree",
            "party" to "Party",
            "sad" to "Sad"
        )

        internal fun validateUrl(urlString: String): URL? {
            return runCatching {
                val url = URL(urlString)
                if (url.protocol.lowercase() != "https") return@runCatching null
                val host = url.host.lowercase()
                if (host !in ALLOWED_HOSTS && !host.endsWith(".tenor.com")) return@runCatching null
                url
            }.getOrNull()
        }

        /**
         * Pure-JVM Tenor API v2 JSON parser handling nested `media_formats` objects
         * (`tinygif`, `nanogif`, `gif`, `mediumgif`) and `dims` arrays without Android `org.json`.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun parseTenorJsonResponse(json: String): List<GifProvider.GifItem> {
            val root = runCatching { MiniJsonReader(json).readValue() as? Map<String, Any?> }.getOrNull()
                ?: return emptyList()
            val resultsArray = root["results"] as? List<Any?> ?: return emptyList()
            val items = ArrayList<GifProvider.GifItem>(resultsArray.size)
            for (entry in resultsArray) {
                val obj = entry as? Map<String, Any?> ?: continue
                val id = (obj["id"] as? String)?.trim().orEmpty()
                if (id.isEmpty()) continue
                val contentDesc = (obj["content_description"] as? String)?.trim().orEmpty()
                val rawTitle = (obj["title"] as? String)?.trim().orEmpty()
                val title = when {
                    rawTitle.isNotEmpty() -> rawTitle
                    contentDesc.isNotEmpty() -> contentDesc
                    else -> "GIF"
                }

                val formats = obj["media_formats"] as? Map<String, Any?>
                val previewFormat = (formats?.get("tinygif") as? Map<String, Any?>)
                    ?: (formats?.get("nanogif") as? Map<String, Any?>)
                    ?: (formats?.get("mediumgif") as? Map<String, Any?>)
                    ?: (formats?.get("gif") as? Map<String, Any?>)
                val fullFormat = (formats?.get("gif") as? Map<String, Any?>)
                    ?: (formats?.get("mediumgif") as? Map<String, Any?>)
                    ?: previewFormat

                val previewUrl = (previewFormat?.get("url") as? String)?.trim().orEmpty()
                val gifUrl = (fullFormat?.get("url") as? String)?.trim().orEmpty()
                if (previewUrl.isEmpty() || gifUrl.isEmpty()) continue
                if (validateUrl(previewUrl) == null || validateUrl(gifUrl) == null) continue

                val dims = (previewFormat?.get("dims") as? List<Any?>) ?: (fullFormat?.get("dims") as? List<Any?>)
                val w = (dims?.getOrNull(0) as? Number)?.toInt()?.takeIf { it > 0 } ?: 320
                val h = (dims?.getOrNull(1) as? Number)?.toInt()?.takeIf { it > 0 } ?: 240

                items.add(
                    GifProvider.GifItem(
                        id = id,
                        title = title,
                        previewUrl = previewUrl,
                        gifUrl = gifUrl,
                        width = w,
                        height = h
                    )
                )
            }
            return items
        }
    }

    private class MiniJsonReader(private val src: String) {
        private var pos = 0

        fun readValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) return null
            return when (val ch = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected JSON char '$ch' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at $pos")
                }
                pos++
                val value = readValue()
                map[key] = value
                skipWhitespace()
                if (pos >= src.length) break
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(readValue())
                skipWhitespace()
                if (pos >= src.length) break
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
            return list
        }

        private fun readString(): String {
            if (src[pos] != '"') throw IllegalArgumentException("Expected '\"' at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) break
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= src.length) {
                                    val hex = src.substring(pos, pos + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun readNumber(): Number {
            val start = pos
            while (pos < src.length) {
                val c = src[pos]
                if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++
                } else {
                    break
                }
            }
            val raw = src.substring(start, pos)
            return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                raw.toDouble()
            } else {
                raw.toLongOrNull() ?: 0L
            }
        }

        private fun readBoolean(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4
                true
            } else if (src.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean at $pos")
            }
        }

        private fun readNull(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at $pos")
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }
    }
}
