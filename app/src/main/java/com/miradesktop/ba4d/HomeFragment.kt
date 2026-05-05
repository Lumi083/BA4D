package com.miradesktop.ba4d

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
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import com.miradesktop.ba4d.databinding.FragmentHomeBinding
import com.miradesktop.ba4d.overlay.BASparkConfig
import com.miradesktop.ba4d.overlay.HtmlParameterParser
import com.miradesktop.ba4d.overlay.OverlayAccessibilityService
import com.miradesktop.ba4d.overlay.OverlayService
import com.miradesktop.ba4d.shizuku.ShizukuMimosaCollector
import com.miradesktop.ba4d.root.RootMimosaCollector
import rikka.shizuku.Shizuku
import java.io.File

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var projectionResultCode: Int = -1
    private var projectionData: Intent? = null
    private var pendingStartOverlay = false
    private var pendingUseAccessibility = false
    private var overlay_visible = false

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            projectionResultCode = result.resultCode
            projectionData = result.data
            Toast.makeText(requireContext(), "屏幕捕获权限已授权", Toast.LENGTH_SHORT).show()
            if (pendingStartOverlay) {
                pendingStartOverlay = false
                startOverlay(pendingUseAccessibility)
                updateStartButtonState()
            }
        } else if (pendingStartOverlay) {
            pendingStartOverlay = false
            Toast.makeText(requireContext(), "需要屏幕捕获权限才能使用自适应颜色", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val config = loadConfig()
        bindConfig(config)
        setupListeners()
        loadMimosaDataSource()
        updateParameterVisibility()
    }

    fun onStartupFileChanged() {
        updateParameterVisibility()
    }

    override fun onResume() {
        super.onResume()
        // Check accessibility service status
        binding.accessibilityPermissionStatus.text = getString(
            if (isAccessibilityServiceEnabled()) R.string.accessibility_permission_granted else R.string.accessibility_permission_missing
        )

        // Check overlay permission status
        binding.overlayPermissionStatus.text = getString(
            if (Settings.canDrawOverlays(requireContext())) R.string.overlay_permission_granted else R.string.overlay_permission_missing
        )

        // Check Shizuku/Root permission status
        val hasRoot = RootMimosaCollector.isRootAvailable()
        val hasShizuku = ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission()

        binding.shizukuPermissionStatus.text = getString(
            when {
                hasRoot -> R.string.root_permission_granted
                hasShizuku -> R.string.shizuku_permission_granted
                else -> R.string.shizuku_permission_missing
            }
        )

        if (hasShizuku) {
            binding.openShizukuPermissionButton.visibility = View.GONE
            binding.downloadShizukuButton.visibility = View.GONE
        }

        if (isNotificationEnabled(requireContext())) {
            binding.notificationPermissionButton.visibility = View.GONE
        }

        updateStartButtonState()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val colonSplitter = enabledServices?.split(":")?.map { it.trim() } ?: emptyList()
        val targetService = "${requireContext().packageName}/${OverlayAccessibilityService::class.java.name}"

        android.util.Log.d("HomeFragment", "Enabled services string: $enabledServices")
        android.util.Log.d("HomeFragment", "Target service: $targetService")
        android.util.Log.d("HomeFragment", "Service list: $colonSplitter")
        android.util.Log.d("HomeFragment", "Contains target: ${colonSplitter.contains(targetService)}")

        return colonSplitter.contains(targetService)
    }

    private fun updateStartButtonState() {
        val isAccessibilityRunning = isAccessibilityServiceEnabled() && overlay_visible
        val isOverlayRunning = isServiceRunning(OverlayService::class.java)
        val isRunning = isAccessibilityRunning || isOverlayRunning

        binding.startOverlayButton.text = getString(if (isRunning) R.string.restart_overlay else R.string.start_overlay)
        binding.stopOverlayButton.visibility = if (isRunning) View.VISIBLE else View.GONE
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
            val config = readConfig()
            BASparkConfig.save(requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, 0), config)

            android.util.Log.d("HomeFragment", "Start button clicked")

            // Prefer accessibility service if enabled
            val useAccessibility = isAccessibilityServiceEnabled()
            val useOverlay = !useAccessibility && Settings.canDrawOverlays(requireContext())

            android.util.Log.d("HomeFragment", "useAccessibility=$useAccessibility, useOverlay=$useOverlay")

            if (!useAccessibility && !useOverlay) {
                Toast.makeText(requireContext(), "需要无障碍服务或悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Check if selected Mimosa data source has permission
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val selectedSource = prefs.getString("mimosa_data_source", "shizuku") ?: "shizuku"

            val hasRoot = RootMimosaCollector.isRootAvailable()
            val hasShizuku = ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission()

            val sourceHasPermission = when (selectedSource) {
                "root" -> hasRoot
                "shizuku" -> hasShizuku
                "direct" -> true // Direct capture doesn't require special permission
                else -> false
            }

            if (!sourceHasPermission) {
                val errorMessage = when (selectedSource) {
                    "root" -> "Root 权限不足，请选择其他数据源"
                    "shizuku" -> "Shizuku 权限不足，请选择其他数据源"
                    else -> "所选数据源权限不足"
                }
                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Stop any running service first
            if (useAccessibility) {
                val stopIntent = Intent(requireContext(), OverlayAccessibilityService::class.java).apply {
                    action = OverlayAccessibilityService.ACTION_STOP_OVERLAY
                }
                requireContext().startService(stopIntent)
            }
            if (isServiceRunning(OverlayService::class.java)) {
                requireContext().stopService(Intent(requireContext(), OverlayService::class.java))
            }

            // Request screen capture permission if adaptive color is enabled and we don't have it yet
            if (config.adaptiveColor && projectionData == null) {
                pendingStartOverlay = true
                pendingUseAccessibility = useAccessibility
                val manager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
            } else {
                android.util.Log.d("HomeFragment", "Calling startOverlay(useAccessibility=$useAccessibility)")
                startOverlay(useAccessibility)
                updateStartButtonState()
            }
        }
        binding.stopOverlayButton.setOnClickListener {
            // Stop both services
            val stopIntent = Intent(requireContext(), OverlayAccessibilityService::class.java).apply {
                action = OverlayAccessibilityService.ACTION_STOP_OVERLAY
            }
            requireContext().startService(stopIntent)
            requireContext().stopService(Intent(requireContext(), OverlayService::class.java))
            projectionResultCode = -1
            projectionData = null
            overlay_visible = false;
            updateStartButtonState()
        }
        binding.notificationPermissionButton.setOnClickListener {
            Toast.makeText(requireContext(), "请开启通知权限", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            }
            startActivity(intent)
        }
        binding.openAccessibilityPermissionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
            val startupFile = requireContext().getSharedPreferences("app_prefs", 0)
                .getString("startup_file", null) ?: "ba-spark-lite.mira.html"
            val supportedParams = HtmlParameterParser.parseHtmlFile(requireContext(), startupFile)

            // Use HTML defaults if available, otherwise use BASparkConfig defaults
            val defaults = BASparkConfig()

            supportedParams.colorDefault?.let { binding.colorInput.setText(it) } ?: binding.colorInput.setText(defaults.color)
            supportedParams.trailColorDefault?.let { binding.trailColorInput.setText(it) } ?: binding.trailColorInput.setText(defaults.trailColor)
            supportedParams.scaleDefault?.let { binding.scaleInput.setText(it.toString()) } ?: binding.scaleInput.setText(defaults.scale.toString())
            supportedParams.speedDefault?.let { binding.speedInput.setText(it.toString()) } ?: binding.speedInput.setText(defaults.speed.toString())
            supportedParams.maxTrailDefault?.let { binding.maxTrailInput.setText(it.toString()) } ?: binding.maxTrailInput.setText(defaults.maxTrail.toString())
            supportedParams.sparkRateDefault?.let { binding.sparkRateInput.setText(it.toString()) } ?: binding.sparkRateInput.setText(defaults.sparkRate.toString())
            supportedParams.opacityMulDefault?.let { binding.opacityMulInput.setText(it.toString()) } ?: binding.opacityMulInput.setText(defaults.opacityMul.toString())
            supportedParams.fpsLimitDefault?.let { binding.fpsLimitInput.setText(it.toString()) } ?: binding.fpsLimitInput.setText(defaults.fpsLimit.toString())

            BASparkConfig.save(requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, 0), readConfig())
        }
        binding.mainColorPreset0.setOnClickListener { binding.colorInput.setText("rgba(35, 207, 255, 1)") }
        binding.mainColorPreset1.setOnClickListener { binding.colorInput.setText("rgba(122, 0, 255, 1)") }
        binding.mainColorPreset2.setOnClickListener { binding.colorInput.setText("rgba(255, 60, 60, 1)") }
        binding.mainColorPreset3.setOnClickListener { binding.colorInput.setText("rgba(87, 167, 255, 1)") }
        binding.mainColorPreset4.setOnClickListener { binding.colorInput.setText("rgba(255, 162, 40, 1)") }
        binding.mainColorPreset5.setOnClickListener { binding.colorInput.setText("rgba(178, 235, 254, 1)") }
        binding.trailColorPreset0.setOnClickListener { binding.trailColorInput.setText("rgba(0, 200, 255, 1)") }
        binding.trailColorPreset1.setOnClickListener { binding.trailColorInput.setText("rgba(150, 100, 255, 1)") }
        binding.trailColorPreset2.setOnClickListener { binding.trailColorInput.setText("rgba(255, 100, 100, 1)") }
        binding.trailColorPreset3.setOnClickListener { binding.trailColorInput.setText("rgba(100, 200, 255, 1)") }
        binding.trailColorPreset4.setOnClickListener { binding.trailColorInput.setText("rgba(255, 200, 100, 1)") }

        binding.mimosaDataSourceRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val source = when (checkedId) {
                R.id.radioShizuku -> "shizuku"
                R.id.radioRoot -> "root"
                R.id.radioDirect -> "direct"
                else -> "shizuku"
            }
            saveMimosaDataSource(source)
        }

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

    private fun updateParameterVisibility() {
        val startupFile = requireContext().getSharedPreferences("app_prefs", 0)
            .getString("startup_file", null) ?: "ba-spark-lite.mira.html"

        val supportedParams = HtmlParameterParser.parseHtmlFile(requireContext(), startupFile)

        android.util.Log.d("HomeFragment", "updateParameterVisibility: file=$startupFile, params=$supportedParams")

        // Show/hide input fields based on supported parameters
        binding.scaleInputLayout.visibility = if (supportedParams.scale) View.VISIBLE else View.GONE
        binding.speedInputLayout.visibility = if (supportedParams.speed) View.VISIBLE else View.GONE
        binding.colorInputLayout.visibility = if (supportedParams.color) View.VISIBLE else View.GONE
        binding.trailColorInputLayout.visibility = if (supportedParams.trailColor) View.VISIBLE else View.GONE
        binding.maxTrailInputLayout.visibility = if (supportedParams.maxTrail) View.VISIBLE else View.GONE
        binding.sparkRateInputLayout.visibility = if (supportedParams.sparkRate) View.VISIBLE else View.GONE
        binding.opacityMulInputLayout.visibility = if (supportedParams.opacityMul) View.VISIBLE else View.GONE
        binding.fpsLimitInputLayout.visibility = if (supportedParams.fpsLimit) View.VISIBLE else View.GONE

        // Hide color preset labels and containers
        val mainColorPresetsVisible = if (supportedParams.color) View.VISIBLE else View.GONE
        binding.mainColorPresetsLabel.visibility = mainColorPresetsVisible
        binding.mainColorPresetsContainer.visibility = mainColorPresetsVisible

        val trailColorPresetsVisible = if (supportedParams.trailColor) View.VISIBLE else View.GONE
        android.util.Log.d("HomeFragment", "trailColorPresetsVisible=$trailColorPresetsVisible (${if (trailColorPresetsVisible == View.VISIBLE) "VISIBLE" else "GONE"})")
        binding.trailColorPresetsLabel.visibility = trailColorPresetsVisible
        binding.trailColorPresetsContainer.visibility = trailColorPresetsVisible
    }

    private fun startOverlay(useAccessibility: Boolean = false) {
        android.util.Log.d("HomeFragment", "startOverlay called with useAccessibility=$useAccessibility")

        val startupFile = requireContext().getSharedPreferences("app_prefs", 0).getString("startup_file", null)
        val url = if (startupFile != null) {
            // Check if it's a user-created file in filesDir
            val userFile = File(requireContext().filesDir, startupFile)
            if (userFile.exists()) {
                "file://${userFile.absolutePath}"
            } else {
                // Fall back to assets (builtin files)
                "file:///android_asset/$startupFile"
            }
        } else {
            "file:///android_asset/ba-spark-lite.mira.html"
        }

        android.util.Log.d("HomeFragment", "Loading URL: $url")

        if (useAccessibility) {
            android.util.Log.d("HomeFragment", "Starting OverlayAccessibilityService")
            // Use accessibility service
            val intent = Intent(requireContext(), OverlayAccessibilityService::class.java).apply {
                action = OverlayAccessibilityService.ACTION_START_OVERLAY
                putExtra(OverlayAccessibilityService.EXTRA_URL, url)
                if (projectionData != null) {
                    putExtra(OverlayAccessibilityService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
                    putExtra(OverlayAccessibilityService.EXTRA_PROJECTION_DATA, projectionData)
                }
            }
            requireContext().startService(intent)
            overlay_visible = true;
        } else {
            android.util.Log.d("HomeFragment", "Starting OverlayService")
            // Use regular overlay service
            val intent = Intent(requireContext(), OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_URL, url)
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadMimosaDataSource() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val source = prefs.getString("mimosa_data_source", "shizuku") ?: "shizuku"
        when (source) {
            "shizuku" -> binding.radioShizuku.isChecked = true
            "root" -> binding.radioRoot.isChecked = true
            "direct" -> binding.radioDirect.isChecked = true
            else -> binding.radioShizuku.isChecked = true
        }
    }

    private fun saveMimosaDataSource(source: String) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("mimosa_data_source", source).apply()
    }

    fun isNotificationEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
