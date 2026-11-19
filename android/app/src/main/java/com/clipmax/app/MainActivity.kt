package com.clipmax.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
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
    private var rootContainer: FrameLayout? = null
    private var viewportContainer: FrameLayout? = null
    private var translatorButton: FrameLayout? = null
    private var translatorCircleView: FrameLayout? = null
    private var translatorGlowView: View? = null
    private var translatorLabelTv: TextView? = null
    private var translatorDragMachine: FloatingButtonSpec.DragStateMachine? = null
    private var pendingNavId: String? = null
    private var isPageLoaded = false
    private var activityDestroyed = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val onboardingRecheckRunnable = Runnable {
        dispatchImeOnboardingState()
    }
    private val storeChangeListener: () -> Unit = {
        mainHandler.post {
            if (!activityDestroyed && isPageLoaded && ::webView.isInitialized) {
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('clipmax:refresh'))", null)
            }
        }
    }
    private val translatorViewportLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        applySavedTranslatorButtonPosition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityDestroyed = false

        store = ClipStore(filesDir)
        ClipStore.addChangeListener(storeChangeListener)

        webView = WebView(this).apply {
            setBackgroundColor(if (resolveCompanionIsDark()) 0xFF202124.toInt() else 0xFFF4F7F5.toInt())
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
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('clipmax:refresh'))", null)
                dispatchImeOnboardingState()
            }
        }

        // Wrap WebView + in-app draggable translator button in a safe-inset viewport FrameLayout.
        // This physically offsets content below the Android status bar and above the navigation bar,
        // keeping both the WebView and translator button strictly inside the app viewport.
        val container = FrameLayout(this).apply {
            setBackgroundColor(if (resolveCompanionIsDark()) 0xFF202124.toInt() else 0xFFF4F7F5.toInt())
        }
        rootContainer = container

        val viewport = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = true
            clipToPadding = true
        }
        viewportContainer = viewport
        val viewportParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        viewport.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val floatingBtn = createTranslatorButton()
        translatorButton = floatingBtn
        val haloPx = dpInt(FloatingButtonSpec.OUTER_HALO_DIAMETER_DP)
        viewport.addView(
            floatingBtn,
            FrameLayout.LayoutParams(haloPx, haloPx).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        viewport.addOnLayoutChangeListener(translatorViewportLayoutListener)
        container.addView(viewport, viewportParams)
        applySystemBarTheme()
        refreshTranslatorButtonUi()
        setContentView(container)

        container.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val sysBars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                viewportParams.setMargins(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            } else {
                @Suppress("DEPRECATION")
                viewportParams.setMargins(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            viewport.layoutParams = viewportParams
            insets
        }

        handleEditIntent(intent)

        // Load production web assets via secure WebViewAssetLoader domain
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun dpInt(value: Float): Int = dp(value).toInt().coerceAtLeast(1)

    private fun currentAppUiLocale(): String {
        val prefs = getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
        val raw = runCatching {
            prefs.getString(SettingsPanelSpec.PREF_APP_UI_LOCALE, SettingsPanelSpec.UI_LOCALE_ENGLISH)
        }.getOrNull()
        return SettingsPanelSpec.normalizeAppUiLocale(raw)
    }

    private fun toggleAppUiLocaleFromButton() {
        val current = currentAppUiLocale()
        val next = FloatingButtonSpec.toggleLocale(current)
        val prefs = getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(SettingsPanelSpec.PREF_APP_UI_LOCALE, next).apply()
        refreshTranslatorButtonUi()
        if (!activityDestroyed && isPageLoaded && ::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('clipmax:uiLocaleChanged', { detail: { locale: '$next' } }))",
                null
            )
        }
    }

    private fun translatorEdgeMarginPx(): Float = dp(FloatingButtonSpec.EDGE_MARGIN_DP - 4f).coerceAtLeast(2f)

    private fun applySavedTranslatorButtonPosition() {
        val viewport = viewportContainer ?: return
        val btn = translatorButton ?: return
        val machine = translatorDragMachine
        if (machine != null && machine.isTracking) return
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        if (vw <= 1f || vh <= 1f) return
        val btnSize = (btn.width.takeIf { it > 0 } ?: dpInt(FloatingButtonSpec.OUTER_HALO_DIAMETER_DP)).toFloat()
        val prefs = getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
        val xFrac = runCatching {
            prefs.getFloat(FloatingButtonSpec.PREF_BUTTON_X_FRACTION, FloatingButtonSpec.DEFAULT_X_FRACTION)
        }.getOrDefault(FloatingButtonSpec.DEFAULT_X_FRACTION)
        val yFrac = runCatching {
            prefs.getFloat(FloatingButtonSpec.PREF_BUTTON_Y_FRACTION, FloatingButtonSpec.DEFAULT_Y_FRACTION)
        }.getOrDefault(FloatingButtonSpec.DEFAULT_Y_FRACTION)
        val pos = FloatingButtonSpec.fromFractions(
            xFraction = xFrac,
            yFraction = yFrac,
            viewportWidth = vw,
            viewportHeight = vh,
            buttonSize = btnSize,
            margin = translatorEdgeMarginPx()
        )
        btn.x = pos.x
        btn.y = pos.y
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTranslatorButton(): FrameLayout {
        val haloPx = dpInt(FloatingButtonSpec.OUTER_HALO_DIAMETER_DP)
        val circlePx = dpInt(FloatingButtonSpec.BUTTON_DIAMETER_DP)
        val touchSlopPx = dp(FloatingButtonSpec.TOUCH_SLOP_DP)
        val machine = FloatingButtonSpec.DragStateMachine(touchSlopPx)
        translatorDragMachine = machine

        val host = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = true
        }

        val glow = View(this)
        translatorGlowView = glow
        host.addView(glow, FrameLayout.LayoutParams(haloPx, haloPx, Gravity.CENTER))

        val circle = FrameLayout(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(6f)
            }
        }
        translatorCircleView = circle

        val tv = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            textDirection = View.TEXT_DIRECTION_LTR
            setSingleLine(true)
        }
        translatorLabelTv = tv
        circle.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        host.addView(circle, FrameLayout.LayoutParams(circlePx, circlePx, Gravity.CENTER))

        host.setOnClickListener {
            toggleAppUiLocaleFromButton()
        }

        host.setOnTouchListener { v, event ->
            val viewport = viewportContainer ?: return@setOnTouchListener false
            val vw = viewport.width.toFloat()
            val vh = viewport.height.toFloat()
            val btnSize = (v.width.takeIf { it > 0 } ?: haloPx).toFloat()
            val margin = translatorEdgeMarginPx()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    machine.onDown(event.rawX, event.rawY, v.x, v.y)
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val pos = machine.onMove(
                        rawX = event.rawX,
                        rawY = event.rawY,
                        viewportWidth = vw,
                        viewportHeight = vh,
                        buttonSize = btnSize,
                        margin = margin
                    )
                    if (machine.hasDragged) {
                        v.x = pos.x
                        v.y = pos.y
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                    val outcome = machine.onUp(
                        viewportWidth = vw,
                        viewportHeight = vh,
                        buttonSize = btnSize,
                        margin = margin
                    )
                    if (outcome.persistPosition) {
                        v.x = outcome.finalPosition.x
                        v.y = outcome.finalPosition.y
                        val fractions = FloatingButtonSpec.toFractions(
                            x = outcome.finalPosition.x,
                            y = outcome.finalPosition.y,
                            viewportWidth = vw,
                            viewportHeight = vh,
                            buttonSize = btnSize,
                            margin = margin
                        )
                        getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
                            .edit()
                            .putFloat(FloatingButtonSpec.PREF_BUTTON_X_FRACTION, fractions.xFraction)
                            .putFloat(FloatingButtonSpec.PREF_BUTTON_Y_FRACTION, fractions.yFraction)
                            .apply()
                    } else if (outcome.triggerTap) {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                    machine.onCancel()
                    applySavedTranslatorButtonPosition()
                    true
                }
                else -> false
            }
        }

        return host
    }

    fun refreshTranslatorButtonUi() {
        val host = translatorButton ?: return
        val circle = translatorCircleView ?: return
        val glow = translatorGlowView ?: return
        val tv = translatorLabelTv ?: return

        val prefs = getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
        val savedTheme = runCatching { prefs.getString(SettingsPanelSpec.PREF_APP_THEME, "system") }.getOrNull()
        val palette = KeyboardLayoutSpec.resolveThemePalette(savedTheme, isSystemNight())
        val style = FloatingButtonSpec.resolveStyle(palette)
        val locale = currentAppUiLocale()

        val label = FloatingButtonSpec.buttonLabelForLocale(locale)
        tv.text = label
        tv.setTextColor(style.textColor)
        tv.textSize = if (AppUiStrings.isArabic(locale)) 11.5f else 12.5f
        host.contentDescription = AppUiStrings.translatorButtonA11y(locale)

        glow.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(FloatingButtonSpec.OUTER_HALO_DIAMETER_DP / 2f)
            colors = intArrayOf(style.glowInnerColor, style.glowMidColor, Color.TRANSPARENT)
        }
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(style.buttonBgColor)
            setStroke(dpInt(1.5f), style.buttonBorderColor)
        }
        host.bringToFront()
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

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun resolveCompanionIsDark(): Boolean {
        val prefs = getSharedPreferences(SettingsPanelSpec.PREF_FILE, Context.MODE_PRIVATE)
        val saved = runCatching { prefs.getString(SettingsPanelSpec.PREF_APP_THEME, "system") }.getOrNull()
        return KeyboardLayoutSpec.resolveThemePalette(saved, isSystemNight()).isNightMode
    }

    fun applySystemBarTheme() {
        val isDark = resolveCompanionIsDark()
        val barColor = if (isDark) 0xFF202124.toInt() else 0xFFF4F7F5.toInt()
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        rootContainer?.setBackgroundColor(barColor)
        if (::webView.isInitialized) {
            webView.setBackgroundColor(barColor)
        }
        refreshTranslatorButtonUi()
        val lightBars = !isDark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (lightBars) mask else 0,
                mask
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            var vis = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            vis = if (lightBars) {
                vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = vis
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarTheme()
        applySavedTranslatorButtonPosition()
        val systemDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (!activityDestroyed && isPageLoaded && ::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('clipmax:systemThemeChanged', { detail: { systemDark: $systemDark } }))",
                null
            )
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarTheme()
        applySavedTranslatorButtonPosition()
        if (isPageLoaded) {
            // Inform the React UI that native state may have been updated (e.g. by IME service)
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('clipmax:refresh'))", null)
            dispatchImeOnboardingState()
            scheduleOnboardingRecheckIfNeeded()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            dispatchImeOnboardingState()
            scheduleOnboardingRecheckIfNeeded()
            ClipboardCaptureCoordinator.captureCurrentClipboard(
                context = this,
                source = CaptureSource.CLIPBOARD_POLL
            ) { outcome ->
                if (!activityDestroyed && outcome != null && outcome.savedCount > 0 && isPageLoaded && ::webView.isInitialized) {
                    webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('clipmax:refresh'))", null)
                }
            }
        }
    }

    private fun scheduleOnboardingRecheckIfNeeded() {
        val state = runCatching { OnboardingStore.getReconciledState(this) }.getOrNull() ?: return
        if (!state.completed) {
            mainHandler.removeCallbacks(onboardingRecheckRunnable)
            mainHandler.postDelayed(onboardingRecheckRunnable, 200L)
        }
    }

    private fun dispatchImeOnboardingState() {
        if (activityDestroyed || !isPageLoaded || !::webView.isInitialized) return
        val json = runCatching {
            OnboardingStore.toJson(OnboardingStore.getReconciledState(this))
        }.getOrNull() ?: return
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('clipmax:imeStatusChanged', { detail: $json }))",
            null
        )
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
        activityDestroyed = true
        mainHandler.removeCallbacks(onboardingRecheckRunnable)
        viewportContainer?.removeOnLayoutChangeListener(translatorViewportLayoutListener)
        ClipStore.removeChangeListener(storeChangeListener)
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
