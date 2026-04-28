package com.potdroid.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.potdroid.overlay.databinding.FragmentHomeBinding
import com.potdroid.overlay.overlay.BASparkConfig
import com.potdroid.overlay.overlay.OverlayService
import com.potdroid.overlay.shizuku.ShizukuMimosaCollector
import rikka.shizuku.Shizuku

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var projectionResultCode: Int = -1
    private var projectionData: Intent? = null

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            projectionResultCode = result.resultCode
            projectionData = result.data
            Toast.makeText(requireContext(), "屏幕捕获权限已授权", Toast.LENGTH_SHORT).show()
            if (pendingStartOverlay) {
                pendingStartOverlay = false
                startOverlay()
                binding.stopOverlayButton.visibility = View.VISIBLE
            }
        } else if (pendingStartOverlay) {
            pendingStartOverlay = false
            Toast.makeText(requireContext(), "需要屏幕捕获权限才能使用自适应颜色", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingStartOverlay = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val config = loadConfig()
        bindConfig(config)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        binding.overlayPermissionStatus.text = getString(
            if (Settings.canDrawOverlays(requireContext())) R.string.overlay_permission_granted else R.string.overlay_permission_missing
        )
        binding.shizukuPermissionStatus.text = getString(
            if (ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission())
                R.string.shizuku_permission_granted else R.string.shizuku_permission_missing
        )
        binding.stopOverlayButton.visibility = if (isServiceRunning(OverlayService::class.java)) View.VISIBLE else View.GONE
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    private fun loadConfig() = BASparkConfig.fromPreferences(
        requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, 0)
    )

    private fun bindConfig(c: BASparkConfig) {
        binding.fpsLimitInput.setText(c.fpsLimit.toString())
        binding.colorInput.setText(c.color)
        binding.trailColorInput.setText(c.trailColor)
        binding.scaleInput.setText(c.scale.toString())
        binding.speedInput.setText(c.speed.toString())
        binding.maxTrailInput.setText(c.maxTrail.toString())
        binding.sparkRateInput.setText(c.sparkRate.toString())
        binding.opacityMulInput.setText(c.opacityMul.toString())
        binding.adaptiveColorSwitch.isChecked = c.adaptiveColor
    }

    private fun setupListeners() {
        binding.startOverlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val config = readConfig()
            BASparkConfig.save(requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, 0), config)
            if (config.adaptiveColor) {
                pendingStartOverlay = true
                val manager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            } else {
                startOverlay()
                binding.stopOverlayButton.visibility = View.VISIBLE
            }
        }
        binding.stopOverlayButton.setOnClickListener {
            requireContext().stopService(Intent(requireContext(), OverlayService::class.java))
            binding.stopOverlayButton.visibility = View.GONE
        }
        binding.openOverlayPermissionButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}")))
            }
        }
        binding.openShizukuPermissionButton.setOnClickListener {
            if (!ShizukuMimosaCollector.isShizukuReady()) {
                Toast.makeText(requireContext(), "Shizuku 服务未运行", Toast.LENGTH_LONG).show()
            } else if (!ShizukuMimosaCollector.hasShizukuPermission()) {
                Shizuku.requestPermission(ShizukuMimosaCollector.REQUEST_CODE)
            }
        }
        binding.downloadShizukuButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
        }
        binding.resetConfigButton.setOnClickListener {
            val defaults = BASparkConfig()
            bindConfig(defaults)
            BASparkConfig.save(requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, 0), defaults)
        }
        binding.mainColorPreset0.setOnClickListener { binding.colorInput.setText("rgba(35, 207, 255, 1)") }
        binding.mainColorPreset1.setOnClickListener { binding.colorInput.setText("rgba(122, 0, 255, 1)") }
        binding.mainColorPreset2.setOnClickListener { binding.colorInput.setText("rgba(255, 60, 60, 1)") }
        binding.mainColorPreset3.setOnClickListener { binding.colorInput.setText("rgba(87, 167, 255, 1)") }
        binding.mainColorPreset4.setOnClickListener { binding.colorInput.setText("rgba(255, 162, 40, 1)") }
        binding.trailColorPreset0.setOnClickListener { binding.trailColorInput.setText("rgba(0, 200, 255, 1)") }
        binding.trailColorPreset1.setOnClickListener { binding.trailColorInput.setText("rgba(150, 100, 255, 1)") }
        binding.trailColorPreset2.setOnClickListener { binding.trailColorInput.setText("rgba(255, 100, 100, 1)") }
        binding.trailColorPreset3.setOnClickListener { binding.trailColorInput.setText("rgba(100, 200, 255, 1)") }
        binding.trailColorPreset4.setOnClickListener { binding.trailColorInput.setText("rgba(255, 200, 100, 1)") }

        val autoSave = { saveConfig() }
        binding.fpsLimitInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.colorInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.trailColorInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.scaleInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.speedInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.maxTrailInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.sparkRateInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.opacityMulInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = autoSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.adaptiveColorSwitch.setOnCheckedChangeListener { _, _ -> autoSave() }
    }

    private fun saveConfig() {
        BASparkConfig.save(requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, 0), readConfig())
    }

    private fun readConfig(): BASparkConfig {
        fun readInt(text: CharSequence?, default: Int, min: Int, max: Int) =
            text?.toString()?.toIntOrNull()?.coerceIn(min, max) ?: default
        fun readFloat(text: CharSequence?, default: Float, min: Float, max: Float) =
            text?.toString()?.toFloatOrNull()?.coerceIn(min, max) ?: default
        val d = BASparkConfig()
        return BASparkConfig(
            fpsLimit = readInt(binding.fpsLimitInput.text, d.fpsLimit, 15, 240),
            color = binding.colorInput.text?.toString().orEmpty().ifBlank { d.color },
            trailColor = binding.trailColorInput.text?.toString().orEmpty().ifBlank { d.trailColor },
            scale = readFloat(binding.scaleInput.text, d.scale, 0.5f, 3.0f),
            speed = readFloat(binding.speedInput.text, d.speed, 0.2f, 3.0f),
            maxTrail = readInt(binding.maxTrailInput.text, d.maxTrail, 0, 64),
            sparkRate = readFloat(binding.sparkRateInput.text, d.sparkRate, 0f, 1f),
            alwaysTrail = d.alwaysTrail,
            adaptiveColor = binding.adaptiveColorSwitch.isChecked,
            dpr = d.dpr,
            opacityMul = readFloat(binding.opacityMulInput.text, d.opacityMul, 0f, 1f),
            port = d.port
        )
    }

    private fun startOverlay() {
        val startupFile = requireContext().getSharedPreferences("app_prefs", 0).getString("startup_file", null)
        val url = if (startupFile != null) "file:///android_asset/$startupFile" else "file:///android_asset/ba-spark-lite.mira.html"
        val intent = Intent(requireContext(), OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_URL, url)
            putExtra(OverlayService.EXTRA_BLOCK_REGIONS, "")
            if (projectionData != null) {
                putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
                putExtra(OverlayService.EXTRA_PROJECTION_DATA, projectionData)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
