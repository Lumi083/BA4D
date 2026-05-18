package com.miradesktop.ba4d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.miradesktop.ba4d.overlay.BASparkConfig
import com.miradesktop.ba4d.overlay.OverlayAccessibilityService
import com.miradesktop.ba4d.overlay.OverlayContentUrl
import com.miradesktop.ba4d.overlay.OverlayService
import com.miradesktop.ba4d.shizuku.ShizukuMimosaCollector
import com.miradesktop.ba4d.root.RootMimosaCollector

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.d("BootReceiver", "Boot completed, checking auto-start preference")

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", false)

        if (!autoStartEnabled) {
            Log.d("BootReceiver", "Auto-start disabled by user")
            return
        }

        Log.d("BootReceiver", "Auto-start enabled, checking permissions")

        // Check if we have necessary permissions
        val hasAccessibility = isAccessibilityServiceEnabled(context)
        val hasOverlay = Settings.canDrawOverlays(context)

        if (!hasAccessibility && !hasOverlay) {
            Log.w("BootReceiver", "No accessibility or overlay permission, cannot auto-start")
            return
        }

        // Check if selected data source has permission
        val selectedSource = prefs.getString("mimosa_data_source", "shizuku") ?: "shizuku"
        val hasRoot = RootMimosaCollector.isRootAvailable()
        val hasShizuku = ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission()

        val sourceHasPermission = when (selectedSource) {
            "root" -> hasRoot
            "shizuku" -> hasShizuku
            "direct" -> true
            else -> false
        }

        if (!sourceHasPermission) {
            Log.w("BootReceiver", "Selected data source ($selectedSource) has no permission")
            return
        }

        Log.i("BootReceiver", "Starting overlay service on boot")

        // Load startup file and config
        val startupFile = prefs.getString("startup_file", null)
        val url = OverlayContentUrl.fromStartupFile(context, startupFile)

        // Prefer accessibility service if enabled
        if (hasAccessibility) {
            val intent = Intent(context, OverlayAccessibilityService::class.java).apply {
                action = OverlayAccessibilityService.ACTION_START_OVERLAY
                putExtra(OverlayAccessibilityService.EXTRA_URL, url)
            }
            context.startService(intent)
            Log.d("BootReceiver", "Started OverlayAccessibilityService")
        } else if (hasOverlay) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_URL, url)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d("BootReceiver", "Started OverlayService")
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val colonSplitter = enabledServices?.split(":")?.map { it.trim() } ?: emptyList()
        val targetService = "${context.packageName}/${OverlayAccessibilityService::class.java.name}"
        return colonSplitter.contains(targetService)
    }
}
