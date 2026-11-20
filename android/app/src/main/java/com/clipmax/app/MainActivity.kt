package com.clipmax.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
        private const val INITIAL_SHINE_DELAY_MS = 1600L
    }

    private lateinit var store: ClipStore
    private lateinit var webView: WebView
    private var rootContainer: FrameLayout? = null
    private var viewportContainer: FrameLayout? = null
    private var translatorButton: FrameLayout? = null
    private var translatorCircleView: FrameLayout? = null
    private var translatorGlowView: View? = null
    private var translatorSpecularView: View? = null
    private var translatorInnerRimView: View? = null
    private var translatorShineView: View? = null
    private var translatorLabelTv: TextView? = null
    private var translatorDragMachine: FloatingButtonSpec.DragStateMachine? = null
    private var translatorPositionInitialized = false
    private var activeShineAnimator: AnimatorSet? = null
    private var activeTapAnimator: AnimatorSet? = null
    private var pendingNavId: String? = null
    private var isPageLoaded = false
    private var isForegroundResumed = false
    private var activityDestroyed = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val onboardingRecheckRunnable = Runnable {
        dispatchImeOnboardingState()
    }
    private val translatorShineRunnable = object : Runnable {
        override fun run() {
            if (activityDestroyed || !isForegroundResumed) return
            playTranslatorLivingShine()
            mainHandler.postDelayed(this, FloatingButtonSpec.SHINE_SWEEP_INTERVAL_MS)
        }
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
        updateTranslatorButtonVisibility()
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
                updateTranslatorButtonVisibility()
            }
        }

        // Wrap WebView + in-app draggable translator button in a safe-inset viewport FrameLayout.
        // This physically offsets content below the Android status bar / display cutout and above
        // the navigation bar, keeping both the WebView and translator button strictly inside the app viewport.
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
        val floatingBtn = createTranslatorButton().apply {
            // Avoid a transient (0,0) top-left flash before first viewport measure completes.
            alpha = 0f
            visibility = View.VISIBLE
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sysBars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                viewportParams.setMargins(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            } else {
                @Suppress("DEPRECATION")
                val topCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insets.displayCutout?.safeInsetTop ?: 0
                } else 0
                @Suppress("DEPRECATION")
                val bottomCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insets.displayCutout?.safeInsetBottom ?: 0
                } else 0
                @Suppress("DEPRECATION")
                val leftCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insets.displayCutout?.safeInsetLeft ?: 0
                } else 0
                @Suppress("DEPRECATION")
                val rightCutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insets.displayCutout?.safeInsetRight ?: 0
                } else 0
                @Suppress("DEPRECATION")
                viewportParams.setMargins(
                    maxOf(insets.systemWindowInsetLeft, leftCutout),
                    maxOf(insets.systemWindowInsetTop, topCutout),
                    maxOf(insets.systemWindowInsetRight, rightCutout),
                    maxOf(insets.systemWindowInsetBottom, bottomCutout)
                )
            }
            viewport.layoutParams = viewportParams
            viewport.requestLayout()
            insets
        }
        container.requestApplyInsets()

        handleEditIntent(intent)
        updateTranslatorButtonVisibility()

        // Load production web assets via secure WebViewAssetLoader domain
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun updateTranslatorButtonVisibility() {
        val btn = translatorButton ?: return
        if (btn.visibility != View.VISIBLE) {
            btn.visibility = View.VISIBLE
        }
        if (translatorPositionInitialized && btn.alpha < 1f) {
            btn.alpha = 1f
        }
        btn.bringToFront()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun dpInt(value: Float): Int = dp(value).toInt().coerceAtLeast(1)

    private fun areMotionAnimationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            return false
        }
        val scale = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return scale > 0f
    }

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
        if (!translatorPositionInitialized) {
            translatorPositionInitialized = true
            btn.alpha = 1f
        }
    }

    private fun startTranslatorShineSchedule() {
        mainHandler.removeCallbacks(translatorShineRunnable)
        if (!activityDestroyed && isForegroundResumed && areMotionAnimationsEnabled()) {
            mainHandler.postDelayed(translatorShineRunnable, INITIAL_SHINE_DELAY_MS)
        }
    }

    private fun stopTranslatorShineSchedule() {
        mainHandler.removeCallbacks(translatorShineRunnable)
        activeShineAnimator?.cancel()
        activeShineAnimator = null
        translatorShineView?.let { shine ->
            shine.alpha = 0f
            shine.translationX = -dp(FloatingButtonSpec.BUTTON_DIAMETER_DP * 1.2f)
        }
        translatorGlowView?.let { glow ->
            glow.alpha = 0.85f
            glow.scaleX = 1f
            glow.scaleY = 1f
        }
    }

    private fun playTranslatorLivingShine() {
        val shine = translatorShineView ?: return
        val glow = translatorGlowView
        val machine = translatorDragMachine
        if (activityDestroyed || !isForegroundResumed || (machine != null && machine.isTracking)) return
        if (!areMotionAnimationsEnabled()) return

        activeShineAnimator?.cancel()
        val travel = dp(FloatingButtonSpec.BUTTON_DIAMETER_DP * 1.25f)
        shine.translationX = -travel
        shine.alpha = 0f

        val sweepX = ObjectAnimator.ofFloat(shine, View.TRANSLATION_X, -travel, travel).apply {
            duration = FloatingButtonSpec.SHINE_SWEEP_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
        }
        val sweepAlpha = ObjectAnimator.ofFloat(shine, View.ALPHA, 0f, 0.92f, 0.92f, 0f).apply {
            duration = FloatingButtonSpec.SHINE_SWEEP_DURATION_MS
        }

        val anims = mutableListOf<android.animation.Animator>(sweepX, sweepAlpha)
        if (glow != null) {
            anims += ObjectAnimator.ofFloat(glow, View.ALPHA, 0.80f, 1f, 0.80f).apply {
                duration = 1400L
                interpolator = AccelerateDecelerateInterpolator()
            }
            anims += ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.97f, 1.05f, 0.97f).apply {
                duration = 1400L
                interpolator = AccelerateDecelerateInterpolator()
            }
            anims += ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.97f, 1.05f, 0.97f).apply {
                duration = 1400L
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        activeShineAnimator = AnimatorSet().apply {
            playTogether(anims)
            start()
        }
    }

    private fun animateTranslatorButtonTap() {
        val host = translatorButton ?: return
        if (!areMotionAnimationsEnabled()) {
            host.scaleX = 1f
            host.scaleY = 1f
            return
        }
        activeTapAnimator?.cancel()
        val peak = FloatingButtonSpec.TAP_SCALE_PEAK
        val expand = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(host, View.SCALE_X, host.scaleX, peak),
                ObjectAnimator.ofFloat(host, View.SCALE_Y, host.scaleY, peak)
            )
            duration = 110L
            interpolator = DecelerateInterpolator()
        }
        val settle = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(host, View.SCALE_X, peak, 1f),
                ObjectAnimator.ofFloat(host, View.SCALE_Y, peak, 1f)
            )
            duration = 185L
            interpolator = DecelerateInterpolator()
        }
        activeTapAnimator = AnimatorSet().apply {
            playSequentially(expand, settle)
            start()
        }
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

        val glow = View(this).apply {
            alpha = 0.85f
        }
        translatorGlowView = glow
        host.addView(glow, FrameLayout.LayoutParams(haloPx, haloPx, Gravity.CENTER))

        val circle = FrameLayout(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(8f)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
        }
        translatorCircleView = circle

        val specularInset = dpInt(2f)
        val specular = View(this)
        translatorSpecularView = specular
        circle.addView(
            specular,
            FrameLayout.LayoutParams(
                circlePx - specularInset * 2,
                (circlePx * 0.62f).toInt().coerceAtLeast(1),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = specularInset
            }
        )

        val innerRim = View(this)
        translatorInnerRimView = innerRim
        circle.addView(
            innerRim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                val rimMargin = dpInt(1f)
                setMargins(rimMargin, rimMargin, rimMargin, rimMargin)
            }
        )

        val shine = View(this).apply {
            rotation = 26f
            alpha = 0f
            translationX = -dp(FloatingButtonSpec.BUTTON_DIAMETER_DP * 1.2f)
        }
        translatorShineView = shine
        circle.addView(
            shine,
            FrameLayout.LayoutParams(
                (circlePx * 0.56f).toInt().coerceAtLeast(1),
                (circlePx * 1.75f).toInt().coerceAtLeast(1),
                Gravity.CENTER
            )
        )

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
                    activeTapAnimator?.cancel()
                    machine.onDown(event.rawX, event.rawY, v.x, v.y)
                    if (areMotionAnimationsEnabled()) {
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(75L).start()
                    }
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
                        if (v.scaleX != 1f || v.scaleY != 1f) {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                        }
                        v.x = pos.x
                        v.y = pos.y
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val outcome = machine.onUp(
                        viewportWidth = vw,
                        viewportHeight = vh,
                        buttonSize = btnSize,
                        margin = margin
                    )
                    if (outcome.persistPosition) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
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
                        v.animate().cancel()
                        animateTranslatorButtonTap()
                        v.performClick()
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
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
        val specular = translatorSpecularView
        val innerRim = translatorInnerRimView
        val shine = translatorShineView
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
        tv.setShadowLayer(
            dp(1.5f),
            0f,
            dp(0.5f),
            if (palette.isNightMode) 0x80000000.toInt() else 0x40FFFFFF.toInt()
        )
        host.contentDescription = AppUiStrings.translatorButtonA11y(locale)

        glow.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(FloatingButtonSpec.OUTER_HALO_DIAMETER_DP / 2f)
            colors = intArrayOf(style.glowInnerColor, style.glowMidColor, Color.TRANSPARENT)
        }
        circle.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(style.glassTopColor, style.glassMidColor, style.glassBottomColor)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dpInt(1.35f), style.buttonBorderColor)
        }
        specular?.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(style.specularTopColor, 0x14FFFFFF, Color.TRANSPARENT)
        ).apply {
            shape = GradientDrawable.OVAL
        }
        innerRim?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dpInt(1f), style.rimHighlightColor)
        }
        shine?.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, style.shineColor, Color.TRANSPARENT)
        )
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
        updateTranslatorButtonVisibility()
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
        isForegroundResumed = true
        applySystemBarTheme()
        applySavedTranslatorButtonPosition()
        updateTranslatorButtonVisibility()
        startTranslatorShineSchedule()
        if (isPageLoaded) {
            // Inform the React UI that native state may have been updated (e.g. by IME service)
            webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('clipmax:refresh'))", null)
            dispatchImeOnboardingState()
            scheduleOnboardingRecheckIfNeeded()
        }
    }

    override fun onPause() {
        isForegroundResumed = false
        stopTranslatorShineSchedule()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateTranslatorButtonVisibility()
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
        updateTranslatorButtonVisibility()
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
        isForegroundResumed = false
        mainHandler.removeCallbacks(onboardingRecheckRunnable)
        stopTranslatorShineSchedule()
        activeTapAnimator?.cancel()
        activeTapAnimator = null
        viewportContainer?.removeOnLayoutChangeListener(translatorViewportLayoutListener)
        ClipStore.removeChangeListener(storeChangeListener)
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
