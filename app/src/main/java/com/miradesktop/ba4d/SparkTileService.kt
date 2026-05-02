package com.miradesktop.ba4d

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.miradesktop.ba4d.overlay.OverlayAccessibilityService
import com.miradesktop.ba4d.overlay.OverlayService

class SparkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val status = HomeFragment.getStatus(this)
        if (status) {
            // Stop overlay
            val stopIntent = Intent(this, OverlayAccessibilityService::class.java).apply {
                action = OverlayAccessibilityService.ACTION_STOP_OVERLAY
            }
            startService(stopIntent)
            stopService(Intent(this, OverlayService::class.java))
        } else {
            // Start overlay
            if (Settings.canDrawOverlays(this)) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_URL, "file:///android_asset/ba-spark-lite.mira.html")
                    putExtra(OverlayService.EXTRA_BLOCK_REGIONS, "")
                    // Add projection if available, but for simplicity, skip
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                // Open app to grant permission
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val status = HomeFragment.getStatus(this)
        qsTile?.let {
            it.state = if (status) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "运行中".takeIf { status } ?: "BA Spark"
            it.updateTile()
        }
    }

}