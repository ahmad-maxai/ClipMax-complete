package com.clipmax.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
        private const val REQUEST_SELECT_FILE = 2001
    }

    private lateinit var store: ClipStore
    private lateinit var webView: WebView
    private var pendingNavId: String? = null
    private var isPageLoaded = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ClipStore(filesDir)

        // Set status and navigation bar background styling to match dark charcoal theme (#111916)
        window.statusBarColor = 0xFF111916.toInt()
        window.navigationBarColor = 0xFF111916.toInt()

        webView = WebView(this).apply {
            setBackgroundColor(0xFF111916.toInt())
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

        // Handle HTML <input type="file"> via Android Storage Access Framework
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                } catch (e: Throwable) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                }

                return try {
                    startActivityForResult(intent, REQUEST_SELECT_FILE)
                    true
                } catch (e: Throwable) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        val bridge = AndroidBridge(this, store)
        webView.addJavascriptInterface(bridge, "ClipMaxNative")

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
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

        // Wrap WebView in a FrameLayout container that applies WindowInsets margins.
        // This physically offsets the WebView below the Android status bar (clock, battery)
        // and above the system navigation bar, preventing ANY overlap or touch interference.
        val container = FrameLayout(this).apply {
            setBackgroundColor(0xFF111916.toInt())
        }
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.addView(webView, layoutParams)
        setContentView(container)

        container.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val sysBars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                layoutParams.setMargins(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            } else {
                @Suppress("DEPRECATION")
                layoutParams.setMargins(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            webView.layoutParams = layoutParams
            insets
        }

        handleEditIntent(intent)

        // Load production web assets via secure WebViewAssetLoader domain
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEditIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SELECT_FILE) {
            val callback = filePathCallback ?: return
            val results: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                val clipData = data.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else {
                null
            }
            callback.onReceiveValue(results)
            filePathCallback = null
        }
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
