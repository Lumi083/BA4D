package com.miradesktop.ba4d.overlay

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.miradesktop.ba4d.MainActivity
import com.miradesktop.ba4d.R
import com.miradesktop.ba4d.mira.MiraAPIAdapter
import com.miradesktop.ba4d.shizuku.ShizukuMimosaCollector
import com.miradesktop.ba4d.root.RootMimosaCollector
import com.miradesktop.ba4d.direct.DirectMimosaCollector
import kotlin.math.max

class OverlayAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_START_OVERLAY = "com.miradesktop.ba4d.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.miradesktop.ba4d.STOP_OVERLAY"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }

    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
    private var shizukuCollector: ShizukuMimosaCollector? = null
    private var rootCollector: RootMimosaCollector? = null
    private var directCollector: DirectMimosaCollector? = null
    private var miraAdapter: MiraAPIAdapter? = null
    private var screenSampler: ScreenSampler? = null
    private var config: BASparkConfig? = null
    private var currentR = 0f
    private var currentG = 0f
    private var currentB = 0f
    private var targetColor = ""
    private var isDirectCapture = false
    private var isBA4DInForeground = false

    private val appStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.miradesktop.ba4d.APP_FOREGROUND" -> {
                    android.util.Log.d("OverlayAccessibilityService", "Received APP_FOREGROUND, isDirectCapture=$isDirectCapture")
                    isBA4DInForeground = true
                    if (isDirectCapture) {
                        android.util.Log.d("OverlayAccessibilityService", "Setting direct collector non-touchable (pass-through)")
                        directCollector?.setTouchable(false)
                    }
                }
                "com.miradesktop.ba4d.APP_BACKGROUND" -> {
                    android.util.Log.d("OverlayAccessibilityService", "Received APP_BACKGROUND, isDirectCapture=$isDirectCapture")
                    isBA4DInForeground = false
                    if (isDirectCapture) {
                        android.util.Log.d("OverlayAccessibilityService", "Setting direct collector touchable (capture input)")
                        directCollector?.setTouchable(true)
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No need to handle accessibility events for overlay rendering
    }

    override fun onInterrupt() {
        // Handle interruption if needed
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OVERLAY -> {
                config = loadBasparkConfig()

                // Check if using direct capture mode
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val source = prefs.getString("mimosa_data_source", "shizuku") ?: "shizuku"
                isDirectCapture = (source == "direct")

                // Register broadcast receiver for app state changes
                if (isDirectCapture) {
                    val filter = IntentFilter().apply {
                        addAction("com.miradesktop.ba4d.APP_FOREGROUND")
                        addAction("com.miradesktop.ba4d.APP_BACKGROUND")
                    }
                    registerReceiver(appStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                }

                startInputCollectorIfPossible()

                if (config!!.adaptiveColor) {
                    val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, -1)
                    val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                    android.util.Log.d("OverlayAccessibilityService", "adaptiveColor check: resultCode=$resultCode, data=$data")
                    if (data != null) {
                        startForegroundForMediaProjection()
                        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                        val metrics = resources.displayMetrics
                        screenSampler = ScreenSampler(this).apply {
                            start(resultCode, data, metrics.widthPixels, metrics.heightPixels)
                        }
                        getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit()
                            .putString("current_adaptive_color", "等待触摸检测").apply()
                    } else {
                        getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit()
                            .putString("current_adaptive_color", "未授权屏幕捕获").apply()
                    }
                } else {
                    getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit()
                        .putString("current_adaptive_color", "已禁用").apply()
                }

                removeOverlay()
                createOverlay(
                    inputUrl = intent.getStringExtra(EXTRA_URL),
                    config = config!!
                )
            }
            ACTION_STOP_OVERLAY -> {
                if (isDirectCapture) {
                    runCatching { unregisterReceiver(appStateReceiver) }
                }
                removeOverlay()
                shizukuCollector?.stop()
                shizukuCollector = null
                rootCollector?.stop()
                rootCollector = null
                directCollector?.stop()
                directCollector = null
                screenSampler?.stop()
                screenSampler = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDirectCapture) {
            runCatching { unregisterReceiver(appStateReceiver) }
        }
        removeOverlay()
        shizukuCollector?.stop()
        shizukuCollector = null
        rootCollector?.stop()
        rootCollector = null
        directCollector?.stop()
        directCollector = null
        screenSampler?.stop()
        screenSampler = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startForegroundForMediaProjection() {
        val channelId = "baspark_media_projection"
        val channel = NotificationChannel(
            channelId,
            "BA Spark 屏幕捕获",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)

        val openMainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openMainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
            Notification.Builder(this, channelId)
                .setContentTitle(getString(R.string.overlay_notification_title))
                .setContentText(getString(R.string.accessibility_overlay_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pendingIntent)   // 点击通知打开 MainActivity
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createOverlay(inputUrl: String?, config: BASparkConfig) {
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (isDirectCapture) 0 else WindowManager.LayoutParams.FLAG_FULLSCREEN) or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val overlayWebView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("OverlayAccessibilityService", "Page loaded: $url")

                    evaluateJavascript("window.__MIRAAPI_ELEMENT_ID__ = 'baspark-overlay';", null)

                    miraAdapter = MiraAPIAdapter(this@apply, "baspark-overlay")
                    pushConfigViaMira(config)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }

            loadUrl(inputUrl ?: "file:///android_asset/ba-spark-replica.mira.html")
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayWebView, params)
        webView = overlayWebView

        // Set initial touchable state for direct capture overlay
        if (isDirectCapture && isBA4DInForeground) {
            directCollector?.setTouchable(false)
        }
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        val view = webView ?: run {
            android.util.Log.w("OverlayAccessibilityService", "setOverlayTouchable: webView is null")
            return
        }
        val wm = windowManager ?: run {
            android.util.Log.w("OverlayAccessibilityService", "setOverlayTouchable: windowManager is null")
            return
        }

        val params = view.layoutParams as? WindowManager.LayoutParams ?: run {
            android.util.Log.w("OverlayAccessibilityService", "setOverlayTouchable: layoutParams is not WindowManager.LayoutParams")
            return
        }

        android.util.Log.d("OverlayAccessibilityService", "setOverlayTouchable($touchable) - current flags: ${params.flags}")

        if (touchable) {
            // Remove FLAG_NOT_TOUCHABLE to allow direct capture to intercept touches
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            // Add FLAG_NOT_TOUCHABLE to let touches pass through
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        android.util.Log.d("OverlayAccessibilityService", "setOverlayTouchable($touchable) - new flags: ${params.flags}")

        try {
            wm.updateViewLayout(view, params)
            android.util.Log.d("OverlayAccessibilityService", "updateViewLayout succeeded")
        } catch (e: Exception) {
            android.util.Log.e("OverlayAccessibilityService", "updateViewLayout failed", e)
        }
    }

    private fun loadBasparkConfig(): BASparkConfig {
        val prefs = getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE)
        return BASparkConfig.fromPreferences(prefs)
    }

    private fun pushConfigViaMira(config: BASparkConfig) {
        val configMap = mapOf(
            "fpsLimit" to config.fpsLimit,
            "color" to config.color,
            "trailColor" to config.trailColor,
            "scale" to config.scale,
            "speed" to config.speed,
            "maxTrail" to config.maxTrail,
            "sparkRate" to config.sparkRate,
            "alwaysTrail" to config.alwaysTrail,
            "dpr" to config.dpr,
            "opacityMul" to config.opacityMul,
            "port" to config.port
        )
        miraAdapter?.sendConfig(configMap)
    }

    private fun startInputCollectorIfPossible() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val source = prefs.getString("mimosa_data_source", "shizuku") ?: "shizuku"

        android.util.Log.d("OverlayAccessibilityService", "Selected mimosa data source: $source")
        android.util.Log.d("OverlayAccessibilityService", "Root available: ${RootMimosaCollector.isRootAvailable()}")
        android.util.Log.d("OverlayAccessibilityService", "Shizuku ready: ${ShizukuMimosaCollector.isShizukuReady()}, permission: ${ShizukuMimosaCollector.hasShizukuPermission()}")

        when (source) {
            "root" -> {
                if (RootMimosaCollector.isRootAvailable()) {
                    android.util.Log.i("OverlayAccessibilityService", "Starting Root collector")
                    startRootCollector()
                } else {
                    android.util.Log.w("OverlayAccessibilityService", "Root source selected but root not available")
                }
            }
            "shizuku" -> {
                if (ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission()) {
                    android.util.Log.i("OverlayAccessibilityService", "Starting Shizuku collector")
                    startShizukuCollector()
                } else {
                    android.util.Log.w("OverlayAccessibilityService", "Shizuku source selected but Shizuku not ready or not authorized")
                }
            }
            "direct" -> {
                android.util.Log.i("OverlayAccessibilityService", "Starting Direct collector")
                startDirectCollector()
            }
            else -> {
                android.util.Log.d("OverlayAccessibilityService", "Unknown source, using auto-detection")
                // Fallback to auto-detection: Root > Shizuku
                if (RootMimosaCollector.isRootAvailable()) {
                    android.util.Log.i("OverlayAccessibilityService", "Auto-detected: Starting Root collector")
                    startRootCollector()
                } else if (ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission()) {
                    android.util.Log.i("OverlayAccessibilityService", "Auto-detected: Starting Shizuku collector")
                    startShizukuCollector()
                } else {
                    android.util.Log.w("OverlayAccessibilityService", "No input collector available")
                }
            }
        }
    }

    private fun startRootCollector() {
        if (rootCollector != null) return

        rootCollector = RootMimosaCollector(
            context = this,
            fpsLimit = config?.fpsLimit ?: 60,
            onPointer = { pointerId, x, y, pressed ->
                miraAdapter?.sendTouchInput(pointerId = pointerId, x = x, y = y, pressed = pressed)
                handleAdaptiveColor(x, y)
            },
            onBackgroundLog = { eventName, detail, x, y ->
                // Background logging disabled (no WebSocket server)
            }
        ).also { it.start() }
    }

    private fun startShizukuCollector() {
        if (shizukuCollector != null) return

        shizukuCollector = ShizukuMimosaCollector(
            context = this,
            fpsLimit = config?.fpsLimit ?: 60,
            onPointer = { pointerId, x, y, pressed ->
                miraAdapter?.sendTouchInput(pointerId = pointerId, x = x, y = y, pressed = pressed)
                handleAdaptiveColor(x, y)
            },
            onBackgroundLog = { eventName, detail, x, y ->
                // Background logging disabled (no WebSocket server)
            }
        ).also { it.start() }
    }

    private fun startDirectCollector() {
        if (directCollector != null) return

        directCollector = DirectMimosaCollector(
            context = this,
            fpsLimit = config?.fpsLimit ?: 60,
            onPointer = { pointerId, x, y, pressed ->
                miraAdapter?.sendTouchInput(pointerId = pointerId, x = x, y = y, pressed = pressed)
                handleAdaptiveColor(x, y)
            },
            onBackgroundLog = { eventName, detail, x, y ->
                // Background logging disabled (no WebSocket server)
            }
        ).also { it.start() }
    }

    private fun handleAdaptiveColor(x: Int, y: Int) {
        if (config?.adaptiveColor == true && screenSampler != null) {
            val samples = listOf(
                screenSampler?.sampleAt(x, y),
                screenSampler?.sampleAt(x - 50, y - 50),
                screenSampler?.sampleAt(x + 50, y - 50),
                screenSampler?.sampleAt(x - 50, y + 50),
                screenSampler?.sampleAt(x + 50, y + 50)
            ).filterNotNull()

            if (samples.isNotEmpty()) {
                val avgR = samples.map { it.first }.average().toFloat()
                val avgG = samples.map { it.second }.average().toFloat()
                val avgB = samples.map { it.third }.average().toFloat()

                currentR = currentR * 0.7f + avgR * 0.3f
                currentG = currentG * 0.7f + avgG * 0.3f
                currentB = currentB * 0.7f + avgB * 0.3f

                val baseColor = config?.color ?: "rgba(87, 164, 255, 1)"
                val initialTrailColor = config?.trailColor ?: "rgba(0, 200, 255, 1)"

                val trailColorMatch = Regex("rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)").find(initialTrailColor)
                val (r, g, b) = if (trailColorMatch != null) {
                    Triple(trailColorMatch.groupValues[1].toInt(), trailColorMatch.groupValues[2].toInt(), trailColorMatch.groupValues[3].toInt())
                } else {
                    Triple(0, 200, 255)
                }

                fun hardLight(blend: Float, base: Float): Float {
                    return max(max(1f - 2f * (1f - base) * (1f - blend), base), blend) + base * 0.15f
                }

                val newR = (hardLight(currentR, r / 255f) * 255).toInt().coerceIn(0, 255)
                val newG = (hardLight(currentG, g / 255f) * 255).toInt().coerceIn(0, 255)
                val newB = (hardLight(currentB, b / 255f) * 255).toInt().coerceIn(0, 255)
                val trailColor = "rgba($newR, $newG, $newB, 1)"

                if (trailColor != targetColor) {
                    targetColor = trailColor
                    miraAdapter?.sendConfig(mapOf("color" to baseColor, "trailColor" to trailColor))
                    getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit()
                        .putString("current_adaptive_color", trailColor).apply()
                }
            }
        }
    }

    private fun startShizukuCollectorIfPossible() {
        if (shizukuCollector != null) return
        if (!ShizukuMimosaCollector.isShizukuReady()) return
        if (!ShizukuMimosaCollector.hasShizukuPermission()) return

        startShizukuCollector()
    }

    private fun removeOverlay() {
        val wm = windowManager

        webView?.let { current ->
            runCatching { wm?.removeView(current) }
            current.destroy()
        }
        webView = null
        windowManager = null
    }
}
