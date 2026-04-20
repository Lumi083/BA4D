package com.potdroid.overlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.potdroid.overlay.nativews.AndroidMimosaServer
import com.potdroid.overlay.R
import com.potdroid.overlay.shizuku.ShizukuMimosaCollector
import org.json.JSONObject

class OverlayService : Service() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_BLOCK_REGIONS = "extra_block_regions"
        const val MIMOSA_TOUCH_WS_PORT = 42891

        private const val NOTIFICATION_CHANNEL_ID = "overlay_runner"
        private const val NOTIFICATION_ID = 7
    }

    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
    private val blockViews = mutableListOf<View>()
    private var mimosaServer: AndroidMimosaServer? = null
    private var mimosaServerPort: Int = -1
    private var shizukuCollector: ShizukuMimosaCollector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val config = loadBasparkConfig()
        ensureMimosaServer(config.port)
        startShizukuCollectorIfPossible()

        removeOverlay()
        createOverlay(
            inputUrl = intent?.getStringExtra(EXTRA_URL),
            blockRegionsSpec = intent?.getStringExtra(EXTRA_BLOCK_REGIONS),
            config = config
        )

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        shizukuCollector?.stop()
        shizukuCollector = null
        mimosaServer?.stopSafe()
        mimosaServer = null
        mimosaServerPort = -1
    }

    private fun createOverlay(inputUrl: String?, blockRegionsSpec: String?, config: BASparkConfig) {
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 0
        }

        val overlayWebView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 0.78f
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pushConfigToDemo(config)
                }
            }

            loadUrl(inputUrl ?: "file:///android_asset/ba-spark-replica.mira.html")
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayWebView, params)
        webView = overlayWebView

        pushConfigToDemo(config)

        addBlockingRegions(type, parseBlockRegions(blockRegionsSpec))
    }

    private fun ensureMimosaServer(port: Int) {
        if (mimosaServer != null && mimosaServerPort == port) return

        mimosaServer?.stopSafe()
        mimosaServer = AndroidMimosaServer(port).also { it.startSafe() }
        mimosaServerPort = port
    }

    private fun loadBasparkConfig(): BASparkConfig {
        val prefs = getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE)
        return BASparkConfig.fromPreferences(prefs)
    }

    private fun pushConfigToDemo(config: BASparkConfig) {
        val escaped = JSONObject.quote(config.toJsonObject().toString())
        val js = "window.__POTDROID_SET_CFG__ && window.__POTDROID_SET_CFG__(JSON.parse($escaped));"
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

    private fun addBlockingRegions(type: Int, regions: List<BlockRegionDp>) {
        val wm = windowManager ?: return
        regions.forEach { region ->
            val blockView = View(this).apply {
                // Keep a light tint so experimenters can verify non-through rectangles visually.
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

        val action = event.actionMasked
        val btnMask = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0 else 1
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()

        mimosaServer?.publishMouse(x = x, y = y, btnMask = btnMask)
        pushTouchPointToDemo(x = x, y = y, btnMask = btnMask)
    }

    private fun pushTouchPointToDemo(x: Int, y: Int, btnMask: Int) {
        val js = "window.__POTDROID_TOUCH__ && window.__POTDROID_TOUCH__($x,$y,$btnMask);"
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

//    private fun onBackgroundEvent(event: BackgroundEventHub.BackgroundEvent) {
//        // Replaced by Shizuku-driven background collection path.
//    }

    private fun startShizukuCollectorIfPossible() {
        if (shizukuCollector != null) return
        if (!ShizukuMimosaCollector.isShizukuReady()) return
        if (!ShizukuMimosaCollector.hasShizukuPermission()) return

        shizukuCollector = ShizukuMimosaCollector(
            context = this,
            onPoint = { x, y, pressed ->
                val btnMask = if (pressed) 1 else 0
                mimosaServer?.publishMouse(x = x, y = y, btnMask = btnMask)
                pushTouchPointToDemo(x = x, y = y, btnMask = btnMask)
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
                pushBackgroundEventToDemo(json)
            }
        ).also { it.start() }
    }

    private fun pushBackgroundEventToDemo(json: String) {
        val escaped = JSONObject.quote(json)
        val js = "window.__POTDROID_BG__ && window.__POTDROID_BG__($escaped);"
        webView?.post { webView?.evaluateJavascript(js, null) }
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
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .build()
    }

    data class BlockRegionDp(
        val xDp: Int,
        val yDp: Int,
        val widthDp: Int,
        val heightDp: Int
    )
}
