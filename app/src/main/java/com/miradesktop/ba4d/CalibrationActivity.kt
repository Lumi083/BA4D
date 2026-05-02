package com.miradesktop.ba4d

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miradesktop.ba4d.databinding.ActivityCalibrationBinding
import com.miradesktop.ba4d.shizuku.ShizukuMimosaCollector
import com.miradesktop.ba4d.root.RootMimosaCollector
import kotlin.math.pow

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private var isCalibrating = false

    private var shizukuCollector: ShizukuMimosaCollector? = null
    private var rootCollector: RootMimosaCollector? = null

    private var currentPrecision = 0.004 // Default: medium

    // Store the range for fine precision mode
    private var fineRangeX: List<Double> = emptyList()
    private var fineRangeY: List<Double> = emptyList()

    companion object {
        private const val PREFS_NAME = "calibration_config"
        private const val KEY_SCALE_X = "scale_x"
        private const val KEY_SCALE_Y = "scale_y"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "坐标校准"

        setupPrecisionButtons()
        setupNumberPickers()
        loadScaleFactors()
        updateInfo()
        startInputCollector()

        binding.calibrationCanvas.onTouchCallback = { x, y ->
            updateInfo("触摸: ($x, $y)")
        }
    }

    private fun setupPrecisionButtons() {
        binding.precisionCoarseButton.setOnClickListener {
            currentPrecision = 0.1
            updatePrecisionButtons()
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val scaleX = prefs.getFloat(KEY_SCALE_X, 1.0f)
            val scaleY = prefs.getFloat(KEY_SCALE_Y, 1.0f)
            setupNumberPickers()
            // Restore the values after switching
            prefs.edit()
                .putFloat(KEY_SCALE_X, scaleX)
                .putFloat(KEY_SCALE_Y, scaleY)
                .apply()
            loadScaleFactors()
        }

        binding.precisionMediumButton.setOnClickListener {
            currentPrecision = 0.004
            updatePrecisionButtons()
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val scaleX = prefs.getFloat(KEY_SCALE_X, 1.0f)
            val scaleY = prefs.getFloat(KEY_SCALE_Y, 1.0f)
            setupNumberPickers()
            // Restore the values after switching
            prefs.edit()
                .putFloat(KEY_SCALE_X, scaleX)
                .putFloat(KEY_SCALE_Y, scaleY)
                .apply()
            loadScaleFactors()
        }

        binding.precisionFineButton.setOnClickListener {
            currentPrecision = 0.0001
            updatePrecisionButtons()
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val scaleX = prefs.getFloat(KEY_SCALE_X, 1.0f)
            val scaleY = prefs.getFloat(KEY_SCALE_Y, 1.0f)
            setupNumberPickers()
            // Restore the values after switching
            prefs.edit()
                .putFloat(KEY_SCALE_X, scaleX)
                .putFloat(KEY_SCALE_Y, scaleY)
                .apply()
            loadScaleFactors()
        }

        updatePrecisionButtons()
    }

    private fun updatePrecisionButtons() {
        binding.precisionCoarseButton.isEnabled = currentPrecision != 0.1
        binding.precisionMediumButton.isEnabled = currentPrecision != 0.004
        binding.precisionFineButton.isEnabled = currentPrecision != 0.0001
    }

    private fun setupNumberPickers() {
        // Exponential scale: 10^x where x ranges from -2 to 2
        // This gives scale from 0.01 to 100

        // For fine precision, use current value as center and limit range to ±0.1
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentScaleX = prefs.getFloat(KEY_SCALE_X, 1.0f)
        val currentScaleY = prefs.getFloat(KEY_SCALE_Y, 1.0f)
        val currentExpX = kotlin.math.log10(currentScaleX.toDouble()).coerceIn(-2.0, 2.0)
        val currentExpY = kotlin.math.log10(currentScaleY.toDouble()).coerceIn(-2.0, 2.0)

        val (rangeX, rangeY) = when (currentPrecision) {
            0.1 -> {
                val r = (-200..200 step 10).map { it / 100.0 }
                Pair(r, r)
            }
            0.004 -> {
                val r = (-2000..2000 step 4).map { it / 1000.0 }
                Pair(r, r)
            }
            else -> {
                // Fine: ±0.1 around current value with 0.0001 step = 2000 steps
                val minExpX = (currentExpX - 0.1).coerceAtLeast(-2.0)
                val maxExpX = (currentExpX + 0.1).coerceAtMost(2.0)
                val stepsX = ((maxExpX - minExpX) / 0.0001).toInt().coerceAtMost(2000)
                val rangeX = (0..stepsX).map { minExpX + it * 0.0001 }

                val minExpY = (currentExpY - 0.1).coerceAtLeast(-2.0)
                val maxExpY = (currentExpY + 0.1).coerceAtMost(2.0)
                val stepsY = ((maxExpY - minExpY) / 0.0001).toInt().coerceAtMost(2000)
                val rangeY = (0..stepsY).map { minExpY + it * 0.0001 }

                fineRangeX = rangeX
                fineRangeY = rangeY
                Pair(rangeX, rangeY)
            }
        }

        val scaleValuesX = rangeX.map {
            val scale = 10.0.pow(it)
            String.format("%.5f", scale)
        }.toTypedArray()

        val scaleValuesY = rangeY.map {
            val scale = 10.0.pow(it)
            String.format("%.5f", scale)
        }.toTypedArray()

        binding.scaleXPicker.apply {
            minValue = 0
            maxValue = scaleValuesX.size - 1
            displayedValues = scaleValuesX
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, _ ->
                saveScaleFactors()
            }
        }

        binding.scaleYPicker.apply {
            minValue = 0
            maxValue = scaleValuesY.size - 1
            displayedValues = scaleValuesY
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, _ ->
                saveScaleFactors()
            }
        }
    }

    private fun loadScaleFactors() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scaleX = prefs.getFloat(KEY_SCALE_X, 1.0f)
        val scaleY = prefs.getFloat(KEY_SCALE_Y, 1.0f)

        // Convert scale to exponent: log10(scale)
        val exponentX = kotlin.math.log10(scaleX.toDouble()).coerceIn(-2.0, 2.0)
        val exponentY = kotlin.math.log10(scaleY.toDouble()).coerceIn(-2.0, 2.0)

        // Find closest index based on precision
        val indexX = when (currentPrecision) {
            0.1 -> {
                val targetIndex = ((exponentX + 2.0) / 0.1).toInt()
                targetIndex.coerceIn(0, 40)
            }
            0.004 -> {
                val targetIndex = ((exponentX + 2.0) / 0.004).toInt()
                targetIndex.coerceIn(0, 1000)
            }
            else -> {
                // Fine mode: find closest value in the dynamic range
                if (fineRangeX.isEmpty()) {
                    0
                } else {
                    fineRangeX.indices.minByOrNull { kotlin.math.abs(fineRangeX[it] - exponentX) } ?: 0
                }
            }
        }

        val indexY = when (currentPrecision) {
            0.1 -> {
                val targetIndex = ((exponentY + 2.0) / 0.1).toInt()
                targetIndex.coerceIn(0, 40)
            }
            0.004 -> {
                val targetIndex = ((exponentY + 2.0) / 0.004).toInt()
                targetIndex.coerceIn(0, 1000)
            }
            else -> {
                // Fine mode: find closest value in the dynamic range
                if (fineRangeY.isEmpty()) {
                    0
                } else {
                    fineRangeY.indices.minByOrNull { kotlin.math.abs(fineRangeY[it] - exponentY) } ?: 0
                }
            }
        }

        binding.scaleXPicker.value = indexX
        binding.scaleYPicker.value = indexY
    }

    private fun saveScaleFactors() {
        // Get exponent from picker
        val exponentX = when (currentPrecision) {
            0.1 -> {
                val index = binding.scaleXPicker.value
                -2.0 + index * 0.1
            }
            0.004 -> {
                val index = binding.scaleXPicker.value
                -2.0 + index * 0.004
            }
            else -> {
                // Fine mode: get value from stored range
                if (fineRangeX.isEmpty() || binding.scaleXPicker.value >= fineRangeX.size) {
                    0.0
                } else {
                    fineRangeX[binding.scaleXPicker.value]
                }
            }
        }

        val exponentY = when (currentPrecision) {
            0.1 -> {
                val index = binding.scaleYPicker.value
                -2.0 + index * 0.1
            }
            0.004 -> {
                val index = binding.scaleYPicker.value
                -2.0 + index * 0.004
            }
            else -> {
                // Fine mode: get value from stored range
                if (fineRangeY.isEmpty() || binding.scaleYPicker.value >= fineRangeY.size) {
                    0.0
                } else {
                    fineRangeY[binding.scaleYPicker.value]
                }
            }
        }

        // Convert to scale: 10^exponent
        val scaleX = 10.0.pow(exponentX).toFloat()
        val scaleY = 10.0.pow(exponentY).toFloat()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_SCALE_X, scaleX)
            .putFloat(KEY_SCALE_Y, scaleY)
            .apply()

        updateInfo()
    }

    private fun startInputCollector() {
        // Check if Shizuku or Root is available
        val hasShizuku = ShizukuMimosaCollector.isShizukuReady() && ShizukuMimosaCollector.hasShizukuPermission()
        val hasRoot = RootMimosaCollector.isRootAvailable()

        if (!hasShizuku && !hasRoot) {
            updateInfo("需要 Shizuku 或 Root 权限")
            return
        }

        // Start input collector
        if (hasShizuku) {
            shizukuCollector = ShizukuMimosaCollector(
                context = this,
                onPointer = { _, x, y, pressed ->
                    if (pressed) {
                        runOnUiThread {
                            updateInfo("Mimosa 报告: ($x, $y)")
                        }
                    }
                },
                onBackgroundLog = { _, _, _, _ -> },
                fpsLimit = 60
            )
            shizukuCollector?.start()
        } else if (hasRoot) {
            rootCollector = RootMimosaCollector(
                context = this,
                onPointer = { _, x, y, pressed ->
                    if (pressed) {
                        runOnUiThread {
                            updateInfo("Mimosa 报告: ($x, $y)")
                        }
                    }
                },
                onBackgroundLog = { _, _, _, _ -> },
                fpsLimit = 60
            )
            rootCollector?.start()
        }
    }

    private fun updateInfo(extraInfo: String = "") {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scaleX = prefs.getFloat(KEY_SCALE_X, 1.0f)
        val scaleY = prefs.getFloat(KEY_SCALE_Y, 1.0f)

        val info = buildString {
            append("当前系数: X=${String.format("%.5f", scaleX)}, Y=${String.format("%.5f", scaleY)}")
            if (extraInfo.isNotEmpty()) {
                append("\n$extraInfo")
            }
        }

        binding.infoTextView.text = info
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        shizukuCollector?.stop()
        shizukuCollector = null
        rootCollector?.stop()
        rootCollector = null
    }
}
