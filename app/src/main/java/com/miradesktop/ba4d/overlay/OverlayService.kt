package com.miradesktop.ba4d.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import com.miradesktop.ba4d.MainActivity
import com.miradesktop.ba4d.nativews.AndroidMimosaServer
import com.miradesktop.ba4d.R
import com.miradesktop.ba4d.shizuku.ShizukuMimosaCollector
import com.miradesktop.ba4d.root.RootMimosaCollector
import com.miradesktop.ba4d.mira.MiraAPIAdapter
import org.json.JSONObject
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val MIMOSA_TOUCH_WS_PORT = 48291

        private const val NOTIFICATION_CHANNEL_ID = "overlay_runner"
        private const val NOTIFICATION_ID = 7
    }

    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        config = loadBasparkConfig()
        ensureMimosaServer(config!!.port)
        startInputCollectorIfPossible()

        if (config!!.adaptiveColor) {
            val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, -1) ?: -1
            val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            android.util.Log.d("OverlayService", "adaptiveColor check: resultCode=$resultCode, data=$data, resultCode!=-1=${resultCode != -1}, data!=null=${data != null}")
            if (data != null) {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val metrics = resources.displayMetrics
                screenSampler = ScreenSampler(this).apply {
                    start(resultCode, data, metrics.widthPixels, metrics.heightPixels)
                }
                getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit().putString("current_adaptive_color", "等待触摸检测").apply()
            } else {
                getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit().putString("current_adaptive_color", "未授权屏幕捕获").apply()
            }
        } else {
            getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit().putString("current_adaptive_color", "已禁用").apply()
        }

        removeOverlay()
        createOverlay(
            inputUrl = intent?.getStringExtra(EXTRA_URL),
            config = config!!
        )

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
            mediaProjectionManager?.getMediaProjection(
                android.app.Activity.RESULT_OK,
                android.content.Intent()
            )?.stop()
        }
    }

    private fun createOverlay(inputUrl: String?, config: BASparkConfig) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

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
                    android.util.Log.d("OverlayService", "Page loaded: $url")

                    // Set element ID for MiraAPI
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
    }

    private fun ensureMimosaServer(port: Int) {
        // Disabled: using MiraAPI instead of WebSocket
        // if (mimosaServer != null && mimosaServerPort == port) return
        // mimosaServer?.stopSafe()
        // mimosaServer = AndroidMimosaServer(port).also { it.startSafe() }
        // mimosaServerPort = port
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
        // Priority: Root > Shizuku
        // If root is available, use root directly instead of Shizuku
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
                    getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE).edit().putString("current_adaptive_color", trailColor).apply()
                }
            }
        }
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.overlay_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openMainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openMainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)   // 点击通知打开 MainActivity
            .setOngoing(true)
            .build()
    }
}
