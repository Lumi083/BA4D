package com.miradesktop.ba4d.overlay

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
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
import com.miradesktop.ba4d.R
import com.miradesktop.ba4d.mira.MiraAPIAdapter
import com.miradesktop.ba4d.nativews.AndroidMimosaServer
import com.miradesktop.ba4d.shizuku.ShizukuMimosaCollector
import com.miradesktop.ba4d.root.RootMimosaCollector
import org.json.JSONObject
import kotlin.math.max

class OverlayAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_START_OVERLAY = "com.miradesktop.ba4d.START_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.miradesktop.ba4d.STOP_OVERLAY"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_BLOCK_REGIONS = "extra_block_regions"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }

    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
    private val blockViews = mutableListOf<View>()
    private var mimosaServer: AndroidMimosaServer? = null
    private var mimosaServerPort: Int = -1
    private var shizukuCollector: ShizukuMimosaCollector? = null
    private var rootCollector: RootMimosaCollector? = null
    private var miraAdapter: MiraAPIAdapter? = null
    private var screenSampler: ScreenSampler? = null
    private var config: BASparkConfig? = null
    private var currentR = 0f
    private var currentG = 0f
    private var currentB = 0f
    private var targetColor = ""

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
                ensureMimosaServer(config!!.port)
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
                    blockRegionsSpec = intent.getStringExtra(EXTRA_BLOCK_REGIONS),
                    config = config!!
                )
            }
            ACTION_STOP_OVERLAY -> {
                removeOverlay()
                shizukuCollector?.stop()
                shizukuCollector = null
                rootCollector?.stop()
                rootCollector = null
                screenSampler?.stop()
                screenSampler = null
                mimosaServer?.stopSafe()
                mimosaServer = null
                mimosaServerPort = -1
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        shizukuCollector?.stop()
        shizukuCollector = null
        rootCollector?.stop()
        rootCollector = null
        screenSampler?.stop()
        screenSampler = null
        mimosaServer?.stopSafe()
        mimosaServer = null
        mimosaServerPort = -1

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startForegroundForMediaProjection() {
        val channelId = "baspark_media_projection"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BA Spark 屏幕捕获",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("BA Spark")
                .setContentText("自适应颜色正在运行")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("BA Spark")
                .setContentText("自适应颜色正在运行")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createOverlay(inputUrl: String?, blockRegionsSpec: String?, config: BASparkConfig) {
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
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

        addBlockingRegions(type, parseBlockRegions(blockRegionsSpec))
    }

    private fun ensureMimosaServer(port: Int) {
        // Disabled: using MiraAPI instead of WebSocket
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

    private fun addBlockingRegions(type: Int, regions: List<BlockRegionDp>) {
        val wm = windowManager ?: return
        regions.forEach { region ->
            val blockView = View(this).apply {
                setBackgroundColor(Color.argb(28, 255, 96, 96))
                setOnTouchListener { _, event ->
                    onBlockRegionTouch(event)
                    true
                }
            }

            val params = WindowManager.LayoutParams(
                dpToPx(region.widthDp),
                dpToPx(region.heightDp),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(region.xDp)
                y = dpToPx(region.yDp)
            }

            wm.addView(blockView, params)
            blockViews.add(blockView)
        }
    }

    private fun onBlockRegionTouch(event: MotionEvent?) {
        if (event == null) return

        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        val action = event.actionMasked
        val pressed = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL

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
                val colorMatch = Regex("rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)").find(baseColor)
                val (r, g, b) = if (colorMatch != null) {
                    Triple(colorMatch.groupValues[1].toInt(), colorMatch.groupValues[2].toInt(), colorMatch.groupValues[3].toInt())
                } else {
                    Triple(87, 164, 255)
                }

                val newR = (r + currentR * 255).toInt().coerceIn(0, 255)
                val newG = (g + currentG * 255).toInt().coerceIn(0, 255)
                val newB = (b + currentB * 255).toInt().coerceIn(0, 255)
                val newColor = "rgba($newR, $newG, $newB, 1)"

                if (newColor != targetColor) {
                    targetColor = newColor
                    miraAdapter?.sendConfig(mapOf("color" to newColor))
                    getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit()
                        .putString("current_adaptive_color", newColor).apply()
                }
            }
        }

        miraAdapter?.sendMouseInput(x = x, y = y, pressed = pressed)
    }

    private fun startInputCollectorIfPossible() {
        // Priority: Root > Shizuku
        if (RootMimosaCollector.isRootAvailable()) {
            startRootCollector()
        } else if (ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission()) {
            startShizukuCollector()
        }
    }

    private fun startRootCollector() {
        if (rootCollector != null) return

        rootCollector = RootMimosaCollector(
            context = this,
            fpsLimit = config?.fpsLimit ?: 60,
            onPointer = { pointerId, x, y, pressed ->
                val btnMask = if (pressed) 1 else 0
                mimosaServer?.publishMouse(x = x, y = y, btnMask = btnMask)
                miraAdapter?.sendTouchInput(pointerId = pointerId, x = x, y = y, pressed = pressed)
                handleAdaptiveColor(x, y)
            },
            onBackgroundLog = { eventName, detail, x, y ->
                val json = JSONObject()
                    .put("type", "bg")
                    .put("event", eventName)
                    .put("package", "root-shell")
                    .put("class", detail)
                    .put("text", "")
                    .put("x", x)
                    .put("y", y)
                    .put("ts", System.currentTimeMillis())
                    .toString()
                mimosaServer?.publishBackgroundEvent(json)
            }
        ).also { it.start() }
    }

    private fun startShizukuCollector() {
        if (shizukuCollector != null) return

        shizukuCollector = ShizukuMimosaCollector(
            context = this,
            fpsLimit = config?.fpsLimit ?: 60,
            onPointer = { pointerId, x, y, pressed ->
                val btnMask = if (pressed) 1 else 0
                mimosaServer?.publishMouse(x = x, y = y, btnMask = btnMask)
                miraAdapter?.sendTouchInput(pointerId = pointerId, x = x, y = y, pressed = pressed)
                handleAdaptiveColor(x, y)
            },
            onBackgroundLog = { eventName, detail, x, y ->
                val json = JSONObject()
                    .put("type", "bg")
                    .put("event", eventName)
                    .put("package", "shizuku-shell")
                    .put("class", detail)
                    .put("text", "")
                    .put("x", x)
                    .put("y", y)
                    .put("ts", System.currentTimeMillis())
                    .toString()
                mimosaServer?.publishBackgroundEvent(json)
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
        blockViews.forEach { blocker ->
            runCatching { wm?.removeView(blocker) }
        }
        blockViews.clear()

        webView?.let { current ->
            runCatching { wm?.removeView(current) }
            current.destroy()
        }
        webView = null
        windowManager = null
    }

    private fun parseBlockRegions(raw: String?): List<BlockRegionDp> {
        if (raw.isNullOrBlank()) return emptyList()

        return raw.split(";")
            .mapNotNull { entry ->
                val parts = entry.trim().split(",")
                if (parts.size != 4) return@mapNotNull null

                val x = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val y = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                val w = parts[2].trim().toIntOrNull() ?: return@mapNotNull null
                val h = parts[3].trim().toIntOrNull() ?: return@mapNotNull null
                if (w <= 0 || h <= 0) return@mapNotNull null

                BlockRegionDp(xDp = x, yDp = y, widthDp = w, heightDp = h)
            }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    data class BlockRegionDp(
        val xDp: Int,
        val yDp: Int,
        val widthDp: Int,
        val heightDp: Int
    )
}
