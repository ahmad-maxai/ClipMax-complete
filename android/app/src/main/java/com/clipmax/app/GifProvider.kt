package com.clipmax.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Modular GIF provider interface and project-managed KLIPY + offline implementation.
 * Zero user API key prompts: online requests route through ClipMax's configured HTTPS proxy
 * (which holds KLIPY credentials server-side) or an optional build-time CI secret, while
 * bundled offline animated GIFs remain available at all times without network access.
 */
internal interface GifProvider {
    data class GifItem(
        val id: String,
        val title: String,
        val previewUrl: String,
        val gifUrl: String,
        val width: Int,
        val height: Int,
        val assetPath: String? = null,
        val keywords: List<String> = emptyList()
    ) {
        val isBundledAsset: Boolean
            get() = !assetPath.isNullOrBlank()
    }

    sealed interface GifFetchResult {
        data class Success(
            val items: List<GifItem>,
            val page: Int = 1,
            val hasNext: Boolean = false,
            val isOfflineCurated: Boolean = false,
            val attribution: String? = null
        ) : GifFetchResult

        data class Offline(val message: String) : GifFetchResult
        data class RateLimited(val message: String) : GifFetchResult
        data class Unconfigured(val message: String) : GifFetchResult
        data class ServerError(val statusCode: Int, val message: String) : GifFetchResult
        data class NetworkError(val error: String) : GifFetchResult
    }

    fun isOnlineServiceConfigured(): Boolean
    fun fetchFeatured(page: Int = 1, callback: (GifFetchResult) -> Unit)
    fun search(query: String, page: Int = 1, callback: (GifFetchResult) -> Unit)
}

internal class KlipyGifProvider(
    private val context: Context,
    private val proxyBaseUrl: String = BuildConfig.GIF_PROXY_URL,
    private val directApiKey: String = BuildConfig.KLIPY_API_KEY,
    private val clientKey: String = "clipmax_android"
) : GifProvider {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun isOnlineServiceConfigured(): Boolean =
        normalizedProxyBaseUrl() != null || directApiKey.trim().isNotEmpty()

    private fun normalizedProxyBaseUrl(): String? {
        val raw = proxyBaseUrl.trim().trimEnd('/')
        if (raw.isEmpty()) return null
        val url = runCatching { URL(raw) }.getOrNull() ?: return null
        if (url.protocol.lowercase() != "https" || url.host.isNullOrBlank()) return null
        return raw
    }

    private fun extraAllowedHost(): String? {
        val proxy = normalizedProxyBaseUrl() ?: return null
        return runCatching { URL(proxy).host.lowercase() }.getOrNull()
    }

    private fun isInternetPermitted(): Boolean {
        return try {
            context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }

    override fun fetchFeatured(page: Int, callback: (GifProvider.GifFetchResult) -> Unit) {
        val safePage = page.coerceIn(1, 50)
        if (!isInternetPermitted()) {
            callback(GifProvider.GifFetchResult.Unconfigured("Network permission is unavailable."))
            return
        }
        if (!isOnlineServiceConfigured()) {
            callback(GifProvider.GifFetchResult.Unconfigured("Online GIF search service is not deployed for this build."))
            return
        }
        if (!isNetworkConnected()) {
            callback(GifProvider.GifFetchResult.Offline("You appear to be offline."))
            return
        }

        val proxy = normalizedProxyBaseUrl()
        val endpoint = if (proxy != null) {
            "$proxy/api/gifs/trending?page=$safePage&per_page=20&locale=en"
        } else {
            val encodedKey = URLEncoder.encode(directApiKey.trim(), "UTF-8")
            val encodedClient = URLEncoder.encode(clientKey, "UTF-8")
            "https://api.klipy.com/api/v1/$encodedKey/gifs/trending?page=$safePage&per_page=20&locale=en&content_filter=high&customer_id=$encodedClient"
        }
        executeRequest(endpoint, safePage, callback)
    }

    override fun search(query: String, page: Int, callback: (GifProvider.GifFetchResult) -> Unit) {
        val safePage = page.coerceIn(1, 50)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            fetchFeatured(safePage, callback)
            return
        }
        if (!isInternetPermitted()) {
            callback(GifProvider.GifFetchResult.Unconfigured("Network permission is unavailable."))
            return
        }
        if (!isOnlineServiceConfigured()) {
            callback(GifProvider.GifFetchResult.Unconfigured("Online GIF search service is not deployed for this build."))
            return
        }
        if (!isNetworkConnected()) {
            callback(GifProvider.GifFetchResult.Offline("You appear to be offline."))
            return
        }

        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val proxy = normalizedProxyBaseUrl()
        val endpoint = if (proxy != null) {
            "$proxy/api/gifs/search?q=$encodedQ&page=$safePage&per_page=20&locale=en"
        } else {
            val encodedKey = URLEncoder.encode(directApiKey.trim(), "UTF-8")
            val encodedClient = URLEncoder.encode(clientKey, "UTF-8")
            "https://api.klipy.com/api/v1/$encodedKey/gifs/search?q=$encodedQ&page=$safePage&per_page=20&locale=en&content_filter=high&customer_id=$encodedClient"
        }
        executeRequest(endpoint, safePage, callback)
    }

    private fun executeRequest(
        endpoint: String,
        requestedPage: Int,
        callback: (GifProvider.GifFetchResult) -> Unit
    ) {
        val extraHost = extraAllowedHost()
        val url = validateUrl(endpoint, extraHost)
        if (url == null) {
            callback(GifProvider.GifFetchResult.NetworkError("Unauthorized GIF service endpoint."))
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
                val result: GifProvider.GifFetchResult = when (code) {
                    200 -> {
                        val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                        val parsed = parseResponsePayload(responseBody, requestedPage)
                        parsed
                    }
                    429 -> GifProvider.GifFetchResult.RateLimited("GIF service rate limit reached. Try again soon.")
                    503 -> {
                        val errText = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                        if (errText.contains("service_unconfigured")) {
                            GifProvider.GifFetchResult.Unconfigured("Online GIF search proxy has no KLIPY key configured.")
                        } else {
                            GifProvider.GifFetchResult.ServerError(code, "GIF service temporarily unavailable ($code).")
                        }
                    }
                    in 500..599 -> GifProvider.GifFetchResult.ServerError(code, "GIF server error ($code).")
                    else -> GifProvider.GifFetchResult.NetworkError("HTTP $code")
                }
                mainHandler.post { callback(result) }
            } catch (_: UnknownHostException) {
                mainHandler.post { callback(GifProvider.GifFetchResult.Offline("No internet connection.")) }
            } catch (e: IOException) {
                val msg = e.message ?: "Network error"
                mainHandler.post {
                    if (!isNetworkConnected()) {
                        callback(GifProvider.GifFetchResult.Offline("You appear to be offline."))
                    } else {
                        callback(GifProvider.GifFetchResult.NetworkError(msg))
                    }
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    callback(GifProvider.GifFetchResult.NetworkError(e.message ?: "Network error"))
                }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    companion object {
        private val ALLOWED_KLIPY_HOSTS = setOf(
            "api.klipy.com",
            "static.klipy.com",
            "media.klipy.com",
            "cdn.klipy.com"
        )

        private val gifExecutor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ClipMax-GIF-Net").apply { isDaemon = true }
        }

        internal val FEATURED_CATEGORIES: List<Pair<String, String>> = listOf(
            "featured" to "Featured",
            "offline" to "Offline",
            "recent" to "Recent",
            "happy" to "Happy",
            "love" to "Love",
            "haha" to "Haha",
            "wow" to "Wow",
            "thumbs up" to "Agree",
            "party" to "Party",
            "sad" to "Sad"
        )

        internal fun validateUrl(urlString: String, extraAllowedHost: String? = null): URL? {
            return runCatching {
                val url = URL(urlString)
                if (url.protocol.lowercase() != "https") return@runCatching null
                val host = url.host.lowercase()
                val matchesExtra = !extraAllowedHost.isNullOrBlank() && host == extraAllowedHost.lowercase()
                if (host !in ALLOWED_KLIPY_HOSTS && !host.endsWith(".klipy.com") && !matchesExtra) {
                    return@runCatching null
                }
                url
            }.getOrNull()
        }

        /**
         * Parses both normalized ClipMax `/api/gifs/*` proxy payloads and raw KLIPY API v1
         * responses into a unified [GifFetchResult.Success] with pagination state.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun parseResponsePayload(json: String, fallbackPage: Int = 1): GifProvider.GifFetchResult.Success {
            val root = runCatching { MiniJsonReader(json).readValue() as? Map<String, Any?> }.getOrNull()
                ?: return GifProvider.GifFetchResult.Success(emptyList(), page = fallbackPage, hasNext = false)

            // 1. Proxy normalized schema: { items: [...], page, hasNext, attribution }
            val proxyItems = root["items"] as? List<Any?>
            if (proxyItems != null) {
                val items = ArrayList<GifProvider.GifItem>(proxyItems.size)
                for (entry in proxyItems) {
                    val obj = entry as? Map<String, Any?> ?: continue
                    val id = (obj["id"] as? String)?.trim().orEmpty()
                    val title = (obj["title"] as? String)?.trim().orEmpty().ifEmpty { "GIF" }
                    val prev = (obj["previewUrl"] as? String)?.trim().orEmpty()
                    val full = (obj["gifUrl"] as? String)?.trim().orEmpty()
                    if (id.isEmpty() || prev.isEmpty() || full.isEmpty()) continue
                    if (validateUrl(prev) == null || validateUrl(full) == null) continue
                    val w = (obj["width"] as? Number)?.toInt()?.takeIf { it > 0 } ?: 320
                    val h = (obj["height"] as? Number)?.toInt()?.takeIf { it > 0 } ?: 240
                    items.add(GifProvider.GifItem(id = id, title = title, previewUrl = prev, gifUrl = full, width = w, height = h))
                }
                val page = (root["page"] as? Number)?.toInt()?.coerceAtLeast(1) ?: fallbackPage
                val hasNext = (root["hasNext"] as? Boolean) ?: false
                val attr = (root["attribution"] as? String)?.trim().orEmpty().ifEmpty { "KLIPY" }
                return GifProvider.GifFetchResult.Success(
                    items = items,
                    page = page,
                    hasNext = hasNext,
                    isOfflineCurated = false,
                    attribution = attr
                )
            }

            // 2. Direct KLIPY API v1 schema: { result: true, data: { data: [...], current_page, has_next } }
            val dataContainer = (root["data"] as? Map<String, Any?>) ?: root
            val entries = (dataContainer["data"] as? List<Any?>)
                ?: (root["data"] as? List<Any?>)
                ?: emptyList()
            val items = ArrayList<GifProvider.GifItem>(entries.size)
            for (entry in entries) {
                val obj = entry as? Map<String, Any?> ?: continue
                val rawId = obj["id"]?.toString()?.trim().orEmpty()
                val slug = (obj["slug"] as? String)?.trim().orEmpty()
                val id = rawId.ifEmpty { slug }
                if (id.isEmpty()) continue
                val title = (obj["title"] as? String)?.trim().orEmpty().ifEmpty { "GIF" }
                val fileObj = obj["file"] as? Map<String, Any?> ?: continue
                val smGif = ((fileObj["sm"] as? Map<String, Any?>)?.get("gif") as? Map<String, Any?>)
                val xsGif = ((fileObj["xs"] as? Map<String, Any?>)?.get("gif") as? Map<String, Any?>)
                val mdGif = ((fileObj["md"] as? Map<String, Any?>)?.get("gif") as? Map<String, Any?>)
                val hdGif = ((fileObj["hd"] as? Map<String, Any?>)?.get("gif") as? Map<String, Any?>)

                val previewFmt = smGif ?: xsGif ?: mdGif ?: hdGif
                val fullFmt = mdGif ?: hdGif ?: smGif ?: xsGif
                val prev = (previewFmt?.get("url") as? String)?.trim().orEmpty()
                val full = (fullFmt?.get("url") as? String)?.trim().orEmpty()
                if (prev.isEmpty() || full.isEmpty()) continue
                if (validateUrl(prev) == null || validateUrl(full) == null) continue

                val w = (previewFmt?.get("width") as? Number)?.toInt()?.takeIf { it > 0 } ?: 320
                val h = (previewFmt?.get("height") as? Number)?.toInt()?.takeIf { it > 0 } ?: 240
                items.add(GifProvider.GifItem(id = id, title = title, previewUrl = prev, gifUrl = full, width = w, height = h))
            }
            val currentPage = (dataContainer["current_page"] as? Number)?.toInt()?.coerceAtLeast(1) ?: fallbackPage
            val hasNext = (dataContainer["has_next"] as? Boolean) ?: false
            return GifProvider.GifFetchResult.Success(
                items = items,
                page = currentPage,
                hasNext = hasNext,
                isOfflineCurated = false,
                attribution = "KLIPY"
            )
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
            pos++
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
            pos++
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
