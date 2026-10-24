package com.clipmax.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * MainActivity acts as the host container for the approved ClipMax UI.
 * It packages and loads the React production web application via a local
 * secure WebViewAssetLoader and binds the native [ClipStore] and system
 * capabilities through [AndroidBridge].
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_EDIT_ITEM_ID = "com.clipmax.app.extra.EDIT_ITEM_ID"
    }

    private lateinit var store: ClipStore
    private lateinit var webView: WebView
    private var pendingNavId: String? = null
    private var isPageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ClipStore(filesDir)

        // Set status and navigation bar background styling to match dark charcoal theme (#111916)
        window.statusBarColor = 0xFF111916.toInt()
        window.navigationBarColor = 0xFF111916.toInt()

        webView = WebView(this).apply {
            setBackgroundColor(0xFF111916.toInt())
            fitsSystemWindows = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
        }

        // Apply WindowInsets safe area padding so status and navigation bars never overlap UI
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            webView.setOnApplyWindowInsetsListener { v, insets ->
                val sysBars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            webView.setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }

        val bridge = AndroidBridge(this, store)
        webView.addJavascriptInterface(bridge, "ClipMaxNative")

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/web/", WebViewAssetLoader.AssetsPathHandler(this, "web"))
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this, "web"))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                pendingNavId?.let { id ->
                    dispatchNavEvent(id)
                    pendingNavId = null
                }
            }
        }

        setContentView(webView)

        handleEditIntent(intent)

        // Load production web assets via secure WebViewAssetLoader domain
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEditIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isPageLoaded) {
            // Inform the React UI that native state may have been updated (e.g. by IME service)
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('clipmax:refresh'))", null)
        }
    }

    private fun handleEditIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_EDIT_ITEM_ID) ?: return
        if (isPageLoaded) {
            dispatchNavEvent(id)
        } else {
            pendingNavId = id
        }
    }

    private fun dispatchNavEvent(id: String) {
        val safeId = id.replace("'", "\\'")
        val js = """
            window.dispatchEvent(new CustomEvent('clipmax:nav', {
                detail: { screen: 'editor', id: '$safeId' }
            }));
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
