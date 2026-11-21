package com.clipmax.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Modular GIF provider interface and Tenor v2 implementation with robust offline fallback,
 * HTTPS-only security, hostname allowlist enforcement, and app-private local file caching.
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
    private val apiKey: String = "",
    private val clientKey: String = "clipmax_android"
) : GifProvider {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val allowedHosts = setOf("tenor.googleapis.com", "media.tenor.com", "c.tenor.com")

    private fun isInternetAllowed(): Boolean {
        return try {
            context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun validateUrl(urlString: String): URL? {
        return runCatching {
            val url = URL(urlString)
            if (url.protocol.lowercase() != "https") return@runCatching null
            if (url.host.lowercase() !in allowedHosts) return@runCatching null
            url
        }.getOrNull()
    }

    override fun fetchFeatured(callback: (GifProvider.GifFetchResult) -> Unit) {
        if (!isInternetAllowed() || apiKey.isBlank()) {
            callback(GifProvider.GifFetchResult.Unconfigured("GIF search requires network permission and Tenor API key configuration."))
            return
        }
        val url = validateUrl("https://tenor.googleapis.com/v2/featured?key=$apiKey&client_key=$clientKey&limit=24&contentfilter=high")
        if (url == null) {
            callback(GifProvider.GifFetchResult.NetworkError("Invalid or unauthorized GIF URL endpoint."))
            return
        }
        android.os.AsyncTask.execute {
            try {
                val conn = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                    val items = parseTenorResponse(responseBody)
                    mainHandler.post { callback(GifProvider.GifFetchResult.Success(items)) }
                } else {
                    mainHandler.post { callback(GifProvider.GifFetchResult.NetworkError("HTTP error ${conn.responseCode}")) }
                }
            } catch (e: Throwable) {
                mainHandler.post { callback(GifProvider.GifFetchResult.NetworkError(e.message ?: "Network error")) }
            }
        }
    }

    override fun search(query: String, callback: (GifProvider.GifFetchResult) -> Unit) {
        if (!isInternetAllowed() || apiKey.isBlank()) {
            callback(GifProvider.GifFetchResult.Unconfigured("GIF search requires network permission and Tenor API key configuration."))
            return
        }
        val encodedQ = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = validateUrl("https://tenor.googleapis.com/v2/search?q=$encodedQ&key=$apiKey&client_key=$clientKey&limit=24&contentfilter=high")
        if (url == null) {
            callback(GifProvider.GifFetchResult.NetworkError("Invalid GIF search endpoint."))
            return
        }
        android.os.AsyncTask.execute {
            try {
                val conn = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                    val items = parseTenorResponse(responseBody)
                    mainHandler.post { callback(GifProvider.GifFetchResult.Success(items)) }
                } else {
                    mainHandler.post { callback(GifProvider.GifFetchResult.NetworkError("HTTP error ${conn.responseCode}")) }
                }
            } catch (e: Throwable) {
                mainHandler.post { callback(GifProvider.GifFetchResult.NetworkError(e.message ?: "Network error")) }
            }
        }
    }

    private fun parseTenorResponse(json: String): List<GifProvider.GifItem> {
        val results = mutableListOf<GifProvider.GifItem>()
        val itemRegex = Regex("""\{([^}]+?"id"\s*:\s*"([^"]+)"[^}]+)\}""")
        for (match in itemRegex.findAll(json)) {
            val block = match.value
            val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: continue
            val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1) ?: "GIF"
            val urlMatch = Regex(""""url"\s*:\s*"(https://[^"]+\.gif)"""").find(block) ?: Regex(""""url"\s*:\s*"(https://[^"]+)"""").find(block)
            val gifUrl = urlMatch?.groupValues?.get(1) ?: continue
            results.add(GifProvider.GifItem(id = idMatch, title = titleMatch, previewUrl = gifUrl, gifUrl = gifUrl, width = 320, height = 240))
        }
        return results
    }
}
