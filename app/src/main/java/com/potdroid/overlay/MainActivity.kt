package com.potdroid.overlay

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.potdroid.overlay.databinding.ActivityMainBinding
import com.potdroid.overlay.overlay.BASparkConfig
import com.potdroid.overlay.overlay.OverlayService
import com.potdroid.overlay.shizuku.ShizukuMimosaCollector
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = ShizukuMimosaCollector.REQUEST_CODE
        private const val DEFAULT_OVERLAY_URL = "file:///android_asset/ba-spark-replica.mira.html"

        private data class ColorPreset(
            val rgba: String,
            val colorInt: Int
        )

        private val PRESET_COLORS = listOf(
            ColorPreset("rgba(35, 207, 255, 1)", Color.rgb(35, 207, 255)),
            ColorPreset("rgba(122, 0, 255, 1)", Color.rgb(122, 0, 255)),
            ColorPreset("rgba(255, 60, 60, 1)", Color.rgb(255, 60, 60)),
            ColorPreset("rgba(87, 167, 255, 1)", Color.rgb(87, 167, 255)),
            ColorPreset("rgba(255, 162, 40, 1)", Color.rgb(255, 162, 40))
        )
    }

    private lateinit var binding: ActivityMainBinding
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED ||
            ShizukuMimosaCollector.hasShizukuPermission()
        runOnUiThread {
            Toast.makeText(
                this,
                if (granted) R.string.shizuku_permission_granted else R.string.shizuku_permission_missing,
                Toast.LENGTH_LONG
            ).show()
            onResume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindConfigToUi(loadSavedConfig())
        setupPresetColorButtons()

        binding.downloadShizukuButton.setOnClickListener {
            openShizukuDownloadPage()
        }

        binding.openBilibiliButton.setOnClickListener {
            openExternalLink("https://space.bilibili.com/1799636047")
        }

        binding.openStarButton.setOnClickListener {
            openExternalLink("https://github.com/Lumi083/BA4D")
        }

        binding.resetConfigButton.setOnClickListener {
            val defaults = BASparkConfig()
            bindConfigToUi(defaults)
            BASparkConfig.save(getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE), defaults)
            toast("已恢复 BASpark 默认配置")
        }

        binding.openOverlayPermissionButton.setOnClickListener {
            openOverlayPermissionPage()
        }

        binding.openShizukuPermissionButton.setOnClickListener {
            requestShizukuPermission()
        }

        binding.startOverlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
                openOverlayPermissionPage()
                return@setOnClickListener
            }

            if (!ShizukuMimosaCollector.hasShizukuPermission()) {
                Toast.makeText(this, R.string.shizuku_permission_optional_tip, Toast.LENGTH_LONG).show()
            }

            val config = readConfigFromUi()
            BASparkConfig.save(getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE), config)

            startOverlayService()
        }

        binding.stopOverlayButton.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        binding.overlayPermissionStatus.text = getString(
            if (granted) R.string.overlay_permission_granted else R.string.overlay_permission_missing
        )

        val shizukuEnabled = ShizukuMimosaCollector.isShizukuReady() &&
            ShizukuMimosaCollector.hasShizukuPermission()
        binding.shizukuPermissionStatus.text = getString(
            if (shizukuEnabled) R.string.shizuku_permission_granted else R.string.shizuku_permission_missing
        )
    }

    private fun loadSavedConfig(): BASparkConfig {
        val prefs = getSharedPreferences(BASparkConfig.PREFS_NAME, MODE_PRIVATE)
        return BASparkConfig.fromPreferences(prefs)
    }

    private fun bindConfigToUi(config: BASparkConfig) {
        binding.fpsLimitInput.setText(config.fpsLimit.toString())
        binding.colorInput.setText(config.color)
        binding.clickColorInput.setText(config.clickColor)
        binding.scaleInput.setText(config.scale.toString())
        binding.speedInput.setText(config.speed.toString())
        binding.maxTrailInput.setText(config.maxTrail.toString())
        binding.sparkRateInput.setText(config.sparkRate.toString())
        binding.alwaysTrailSwitch.isChecked = config.alwaysTrail
        binding.dprInput.setText(config.dpr.toString())
        binding.opacityMulInput.setText(config.opacityMul.toString())
        binding.portInput.setText(config.port.toString())
    }

    private fun setupPresetColorButtons() {
        val mainButtons = listOf(
            binding.mainColorPreset0,
            binding.mainColorPreset1,
            binding.mainColorPreset2,
            binding.mainColorPreset3,
            binding.mainColorPreset4
        )
        val clickButtons = listOf(
            binding.clickColorPreset0,
            binding.clickColorPreset1,
            binding.clickColorPreset2,
            binding.clickColorPreset3,
            binding.clickColorPreset4
        )

        mainButtons.forEachIndexed { index, button ->
            configureColorPresetButton(button, PRESET_COLORS[index]) {
                binding.colorInput.setText(PRESET_COLORS[index].rgba)
            }
        }

        clickButtons.forEachIndexed { index, button ->
            configureColorPresetButton(button, PRESET_COLORS[index]) {
                binding.clickColorInput.setText(PRESET_COLORS[index].rgba)
            }
        }
    }

    private fun configureColorPresetButton(button: com.google.android.material.button.MaterialButton, preset: ColorPreset, onClick: () -> Unit) {
        button.backgroundTintList = ColorStateList.valueOf(preset.colorInt)
        button.contentDescription = preset.rgba
        button.setOnClickListener { onClick() }
    }

    private fun readConfigFromUi(): BASparkConfig {
        fun readInt(text: CharSequence?, defaultValue: Int, min: Int, max: Int): Int {
            val value = text?.toString()?.trim()?.toIntOrNull() ?: defaultValue
            return value.coerceIn(min, max)
        }

        fun readFloat(text: CharSequence?, defaultValue: Float, min: Float, max: Float): Float {
            val value = text?.toString()?.trim()?.toFloatOrNull() ?: defaultValue
            return value.coerceIn(min, max)
        }

        val defaults = BASparkConfig()
        return BASparkConfig(
            fpsLimit = readInt(binding.fpsLimitInput.text, defaults.fpsLimit, 15, 240),
            color = binding.colorInput.text?.toString().orEmpty().ifBlank { defaults.color },
            clickColor = binding.clickColorInput.text?.toString().orEmpty().ifBlank { defaults.clickColor },
            scale = readFloat(binding.scaleInput.text, defaults.scale, 0.5f, 3.0f),
            speed = readFloat(binding.speedInput.text, defaults.speed, 0.2f, 3.0f),
            maxTrail = readInt(binding.maxTrailInput.text, defaults.maxTrail, 0, 64),
            sparkRate = readFloat(binding.sparkRateInput.text, defaults.sparkRate, 0f, 1f),
            alwaysTrail = binding.alwaysTrailSwitch.isChecked,
            dpr = readInt(binding.dprInput.text, defaults.dpr, 0, 500),
            opacityMul = readFloat(binding.opacityMulInput.text, defaults.opacityMul, 0f, 1f),
            port = readInt(binding.portInput.text, defaults.port, 1, 65535)
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openOverlayPermissionPage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openShizukuDownloadPage() {
        openExternalLink("https://github.com/RikkaApps/Shizuku/releases")
    }

    private fun openExternalLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show() }
    }

    private fun requestShizukuPermission() {
        if (!ShizukuMimosaCollector.isShizukuReady()) {
            Toast.makeText(this, "Shizuku 服务未运行", Toast.LENGTH_LONG).show()
            return
        }

        if (ShizukuMimosaCollector.hasShizukuPermission()) {
            Toast.makeText(this, R.string.shizuku_permission_granted, Toast.LENGTH_SHORT).show()
            return
        }

        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_URL, DEFAULT_OVERLAY_URL)
            putExtra(OverlayService.EXTRA_BLOCK_REGIONS, "")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
