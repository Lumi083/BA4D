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
    private var fineRangeScaleX: List<Double> = emptyList()
    private var fineRangeScaleY: List<Double> = emptyList()
    private var fineRangeOffsetX: List<Double> = emptyList()
    private var fineRangeOffsetY: List<Double> = emptyList()

    // Current rotation mode (0, 90, 180, 270)
    private var currentRotation = 0

    companion object {
        private const val PREFS_NAME = "calibration_config"
        private const val KEY_SCALE_X = "scale_x"
        private const val KEY_SCALE_Y = "scale_y"
        private const val KEY_OFFSET_X = "offset_x"
        private const val KEY_OFFSET_Y = "offset_y"
        private const val KEY_ROTATION_SPECIFIC = "rotation_specific"

        // Keys for rotation-specific calibration (0, 90, 180, 270 degrees)
        private const val KEY_SCALE_X_R0 = "scale_x_r0"
        private const val KEY_SCALE_Y_R0 = "scale_y_r0"
        private const val KEY_OFFSET_X_R0 = "offset_x_r0"
        private const val KEY_OFFSET_Y_R0 = "offset_y_r0"

        private const val KEY_SCALE_X_R90 = "scale_x_r90"
        private const val KEY_SCALE_Y_R90 = "scale_y_r90"
        private const val KEY_OFFSET_X_R90 = "offset_x_r90"
        private const val KEY_OFFSET_Y_R90 = "offset_y_r90"

        private const val KEY_SCALE_X_R180 = "scale_x_r180"
        private const val KEY_SCALE_Y_R180 = "scale_y_r180"
        private const val KEY_OFFSET_X_R180 = "offset_x_r180"
        private const val KEY_OFFSET_Y_R180 = "offset_y_r180"

        private const val KEY_SCALE_X_R270 = "scale_x_r270"
        private const val KEY_SCALE_Y_R270 = "scale_y_r270"
        private const val KEY_OFFSET_X_R270 = "offset_x_r270"
        private const val KEY_OFFSET_Y_R270 = "offset_y_r270"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "坐标校准"

        setupRotationSpecificCheckbox()
        setupRotationButtons()
        setupPrecisionButtons()
        setupNumberPickers()
        loadCalibrationValues()
        updateInfo()
        startInputCollector()

        binding.calibrationCanvas.onTouchCallback = { x, y ->
            updateInfo("触摸: ($x, $y)")
        }
    }

    private fun setupRotationSpecificCheckbox() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)

        binding.rotationSpecificCheckbox.isChecked = rotationSpecific

        binding.rotationSpecificCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ROTATION_SPECIFIC, isChecked).apply()
            updateRotationButtons()
            updateInfo()
        }
    }

    private fun setupRotationButtons() {
        binding.rotation0Button.setOnClickListener {
            currentRotation = 0
            updateRotationButtons()
            reloadCalibrationValues()
        }

        binding.rotation90Button.setOnClickListener {
            currentRotation = 90
            updateRotationButtons()
            reloadCalibrationValues()
        }

        binding.rotation180Button.setOnClickListener {
            currentRotation = 180
            updateRotationButtons()
            reloadCalibrationValues()
        }

        binding.rotation270Button.setOnClickListener {
            currentRotation = 270
            updateRotationButtons()
            reloadCalibrationValues()
        }

        updateRotationButtons()
    }

    private fun updateRotationButtons() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)

        // Show/hide rotation buttons based on rotation-specific mode
        val visibility = if (rotationSpecific) android.view.View.VISIBLE else android.view.View.GONE
        binding.rotationButtonsLayout.visibility = visibility

        if (rotationSpecific) {
            binding.rotation0Button.isEnabled = currentRotation != 0
            binding.rotation90Button.isEnabled = currentRotation != 90
            binding.rotation180Button.isEnabled = currentRotation != 180
            binding.rotation270Button.isEnabled = currentRotation != 270
        }
    }

    private fun setupPrecisionButtons() {
        binding.precisionCoarseButton.setOnClickListener {
            currentPrecision = 0.1
            updatePrecisionButtons()
            setupNumberPickers()
            reloadCalibrationValues()
        }

        binding.precisionMediumButton.setOnClickListener {
            currentPrecision = 0.004
            updatePrecisionButtons()
            setupNumberPickers()
            reloadCalibrationValues()
        }

        binding.precisionFineButton.setOnClickListener {
            currentPrecision = 0.0001
            updatePrecisionButtons()
            setupNumberPickers()
            reloadCalibrationValues()
        }

        updatePrecisionButtons()
    }

    private fun updatePrecisionButtons() {
        binding.precisionCoarseButton.isEnabled = currentPrecision != 0.1
        binding.precisionMediumButton.isEnabled = currentPrecision != 0.004
        binding.precisionFineButton.isEnabled = currentPrecision != 0.0001
    }

    private fun setupNumberPickers() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Get current values for scale
        val currentScaleX = getCurrentScaleX()
        val currentScaleY = getCurrentScaleY()
        val currentOffsetX = getCurrentOffsetX()
        val currentOffsetY = getCurrentOffsetY()

        val currentExpX = kotlin.math.log10(currentScaleX.toDouble()).coerceIn(-2.0, 2.0)
        val currentExpY = kotlin.math.log10(currentScaleY.toDouble()).coerceIn(-2.0, 2.0)

        // Setup scale pickers (exponential scale: 10^x where x ranges from -2 to 2)
        val (rangeScaleX, rangeScaleY) = when (currentPrecision) {
            0.1 -> {
                val r = (-200..200 step 10).map { it / 100.0 }
                Pair(r, r)
            }
            0.004 -> {
                val r = (-2000..2000 step 4).map { it / 1000.0 }
                Pair(r, r)
            }
            else -> {
                val minExpX = (currentExpX - 0.1).coerceAtLeast(-2.0)
                val maxExpX = (currentExpX + 0.1).coerceAtMost(2.0)
                val stepsX = ((maxExpX - minExpX) / 0.0001).toInt().coerceAtMost(2000)
                val rangeX = (0..stepsX).map { minExpX + it * 0.0001 }

                val minExpY = (currentExpY - 0.1).coerceAtLeast(-2.0)
                val maxExpY = (currentExpY + 0.1).coerceAtMost(2.0)
                val stepsY = ((maxExpY - minExpY) / 0.0001).toInt().coerceAtMost(2000)
                val rangeY = (0..stepsY).map { minExpY + it * 0.0001 }

                fineRangeScaleX = rangeX
                fineRangeScaleY = rangeY
                Pair(rangeX, rangeY)
            }
        }

        val scaleValuesX = rangeScaleX.map {
            val scale = 10.0.pow(it)
            String.format("%.5f", scale)
        }.toTypedArray()

        val scaleValuesY = rangeScaleY.map {
            val scale = 10.0.pow(it)
            String.format("%.5f", scale)
        }.toTypedArray()

        binding.scaleXPicker.apply {
            minValue = 0
            maxValue = scaleValuesX.size - 1
            displayedValues = scaleValuesX
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, _ ->
                saveCalibrationValues()
            }
        }

        binding.scaleYPicker.apply {
            minValue = 0
            maxValue = scaleValuesY.size - 1
            displayedValues = scaleValuesY
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, _ ->
                saveCalibrationValues()
            }
        }

        // Setup offset pickers (range: -1000% to 1000%, i.e., -10.0 to 10.0)
        val (rangeOffsetX, rangeOffsetY) = when (currentPrecision) {
            0.1 -> {
                val r = (-10000..10000 step 100).map { it / 1000.0 }
                Pair(r, r)
            }
            0.004 -> {
                val r = (-10000..10000 step 4).map { it / 1000.0 }
                Pair(r, r)
            }
            else -> {
                val minOffX = (currentOffsetX - 0.1).coerceAtLeast(-10.0)
                val maxOffX = (currentOffsetX + 0.1).coerceAtMost(10.0)
                val stepsX = ((maxOffX - minOffX) / 0.0001).toInt().coerceAtMost(2000)
                val rangeX = (0..stepsX).map { minOffX + it * 0.0001 }

                val minOffY = (currentOffsetY - 0.1).coerceAtLeast(-10.0)
                val maxOffY = (currentOffsetY + 0.1).coerceAtMost(10.0)
                val stepsY = ((maxOffY - minOffY) / 0.0001).toInt().coerceAtMost(2000)
                val rangeY = (0..stepsY).map { minOffY + it * 0.0001 }

                fineRangeOffsetX = rangeX
                fineRangeOffsetY = rangeY
                Pair(rangeX, rangeY)
            }
        }

        val offsetValuesX = rangeOffsetX.map {
            String.format("%.4f", it)
        }.toTypedArray()

        val offsetValuesY = rangeOffsetY.map {
            String.format("%.4f", it)
        }.toTypedArray()

        binding.offsetXPicker.apply {
            minValue = 0
            maxValue = offsetValuesX.size - 1
            displayedValues = offsetValuesX
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, _ ->
                saveCalibrationValues()
            }
        }

        binding.offsetYPicker.apply {
            minValue = 0
            maxValue = offsetValuesY.size - 1
            displayedValues = offsetValuesY
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, _ ->
                saveCalibrationValues()
            }
        }
    }

    private fun getCurrentScaleX(): Float {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)
        return if (rotationSpecific) {
            when (currentRotation) {
                0 -> prefs.getFloat(KEY_SCALE_X_R0, 1.0f)
                90 -> prefs.getFloat(KEY_SCALE_X_R90, 1.0f)
                180 -> prefs.getFloat(KEY_SCALE_X_R180, 1.0f)
                270 -> prefs.getFloat(KEY_SCALE_X_R270, 1.0f)
                else -> prefs.getFloat(KEY_SCALE_X, 1.0f)
            }
        } else {
            prefs.getFloat(KEY_SCALE_X, 1.0f)
        }
    }

    private fun getCurrentScaleY(): Float {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)
        return if (rotationSpecific) {
            when (currentRotation) {
                0 -> prefs.getFloat(KEY_SCALE_Y_R0, 1.0f)
                90 -> prefs.getFloat(KEY_SCALE_Y_R90, 1.0f)
                180 -> prefs.getFloat(KEY_SCALE_Y_R180, 1.0f)
                270 -> prefs.getFloat(KEY_SCALE_Y_R270, 1.0f)
                else -> prefs.getFloat(KEY_SCALE_Y, 1.0f)
            }
        } else {
            prefs.getFloat(KEY_SCALE_Y, 1.0f)
        }
    }

    private fun getCurrentOffsetX(): Float {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)
        return if (rotationSpecific) {
            when (currentRotation) {
                0 -> prefs.getFloat(KEY_OFFSET_X_R0, 0.0f)
                90 -> prefs.getFloat(KEY_OFFSET_X_R90, 0.0f)
                180 -> prefs.getFloat(KEY_OFFSET_X_R180, 0.0f)
                270 -> prefs.getFloat(KEY_OFFSET_X_R270, 0.0f)
                else -> prefs.getFloat(KEY_OFFSET_X, 0.0f)
            }
        } else {
            prefs.getFloat(KEY_OFFSET_X, 0.0f)
        }
    }

    private fun getCurrentOffsetY(): Float {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)
        return if (rotationSpecific) {
            when (currentRotation) {
                0 -> prefs.getFloat(KEY_OFFSET_Y_R0, 0.0f)
                90 -> prefs.getFloat(KEY_OFFSET_Y_R90, 0.0f)
                180 -> prefs.getFloat(KEY_OFFSET_Y_R180, 0.0f)
                270 -> prefs.getFloat(KEY_OFFSET_Y_R270, 0.0f)
                else -> prefs.getFloat(KEY_OFFSET_Y, 0.0f)
            }
        } else {
            prefs.getFloat(KEY_OFFSET_Y, 0.0f)
        }
    }

    private fun loadCalibrationValues() {
        val scaleX = getCurrentScaleX()
        val scaleY = getCurrentScaleY()
        val offsetX = getCurrentOffsetX()
        val offsetY = getCurrentOffsetY()

        // Convert scale to exponent: log10(scale)
        val exponentX = kotlin.math.log10(scaleX.toDouble()).coerceIn(-2.0, 2.0)
        val exponentY = kotlin.math.log10(scaleY.toDouble()).coerceIn(-2.0, 2.0)

        // Find closest index for scale based on precision
        val indexScaleX = when (currentPrecision) {
            0.1 -> {
                val targetIndex = ((exponentX + 2.0) / 0.1).toInt()
                targetIndex.coerceIn(0, 40)
            }
            0.004 -> {
                val targetIndex = ((exponentX + 2.0) / 0.004).toInt()
                targetIndex.coerceIn(0, 1000)
            }
            else -> {
                if (fineRangeScaleX.isEmpty()) {
                    0
                } else {
                    fineRangeScaleX.indices.minByOrNull { kotlin.math.abs(fineRangeScaleX[it] - exponentX) } ?: 0
                }
            }
        }

        val indexScaleY = when (currentPrecision) {
            0.1 -> {
                val targetIndex = ((exponentY + 2.0) / 0.1).toInt()
                targetIndex.coerceIn(0, 40)
            }
            0.004 -> {
                val targetIndex = ((exponentY + 2.0) / 0.004).toInt()
                targetIndex.coerceIn(0, 1000)
            }
            else -> {
                if (fineRangeScaleY.isEmpty()) {
                    0
                } else {
                    fineRangeScaleY.indices.minByOrNull { kotlin.math.abs(fineRangeScaleY[it] - exponentY) } ?: 0
                }
            }
        }

        // Find closest index for offset based on precision
        val indexOffsetX = when (currentPrecision) {
            0.1 -> {
                val targetIndex = ((offsetX + 10.0) / 0.1).toInt()
                targetIndex.coerceIn(0, 200)
            }
            0.004 -> {
                val targetIndex = ((offsetX + 10.0) / 0.004).toInt()
                targetIndex.coerceIn(0, 5000)
            }
            else -> {
                if (fineRangeOffsetX.isEmpty()) {
                    0
                } else {
                    fineRangeOffsetX.indices.minByOrNull { kotlin.math.abs(fineRangeOffsetX[it] - offsetX) } ?: 0
                }
            }
        }

        val indexOffsetY = when (currentPrecision) {
            0.1 -> {
                val targetIndex = ((offsetY + 10.0) / 0.1).toInt()
                targetIndex.coerceIn(0, 200)
            }
            0.004 -> {
                val targetIndex = ((offsetY + 10.0) / 0.004).toInt()
                targetIndex.coerceIn(0, 5000)
            }
            else -> {
                if (fineRangeOffsetY.isEmpty()) {
                    0
                } else {
                    fineRangeOffsetY.indices.minByOrNull { kotlin.math.abs(fineRangeOffsetY[it] - offsetY) } ?: 0
                }
            }
        }

        binding.scaleXPicker.value = indexScaleX
        binding.scaleYPicker.value = indexScaleY
        binding.offsetXPicker.value = indexOffsetX
        binding.offsetYPicker.value = indexOffsetY
    }

    private fun reloadCalibrationValues() {
        setupNumberPickers()
        loadCalibrationValues()
        updateInfo()
    }

    private fun saveCalibrationValues() {
        // Get exponent from scale picker
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
                if (fineRangeScaleX.isEmpty() || binding.scaleXPicker.value >= fineRangeScaleX.size) {
                    0.0
                } else {
                    fineRangeScaleX[binding.scaleXPicker.value]
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
                if (fineRangeScaleY.isEmpty() || binding.scaleYPicker.value >= fineRangeScaleY.size) {
                    0.0
                } else {
                    fineRangeScaleY[binding.scaleYPicker.value]
                }
            }
        }

        // Convert to scale: 10^exponent
        val scaleX = 10.0.pow(exponentX).toFloat()
        val scaleY = 10.0.pow(exponentY).toFloat()

        // Get offset from offset picker
        val offsetX = when (currentPrecision) {
            0.1 -> {
                val index = binding.offsetXPicker.value
                -10.0 + index * 0.1
            }
            0.004 -> {
                val index = binding.offsetXPicker.value
                -10.0 + index * 0.004
            }
            else -> {
                if (fineRangeOffsetX.isEmpty() || binding.offsetXPicker.value >= fineRangeOffsetX.size) {
                    0.0
                } else {
                    fineRangeOffsetX[binding.offsetXPicker.value]
                }
            }
        }.toFloat()

        val offsetY = when (currentPrecision) {
            0.1 -> {
                val index = binding.offsetYPicker.value
                -10.0 + index * 0.1
            }
            0.004 -> {
                val index = binding.offsetYPicker.value
                -10.0 + index * 0.004
            }
            else -> {
                if (fineRangeOffsetY.isEmpty() || binding.offsetYPicker.value >= fineRangeOffsetY.size) {
                    0.0
                } else {
                    fineRangeOffsetY[binding.offsetYPicker.value]
                }
            }
        }.toFloat()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)

        val editor = prefs.edit()

        if (rotationSpecific) {
            when (currentRotation) {
                0 -> {
                    editor.putFloat(KEY_SCALE_X_R0, scaleX)
                    editor.putFloat(KEY_SCALE_Y_R0, scaleY)
                    editor.putFloat(KEY_OFFSET_X_R0, offsetX)
                    editor.putFloat(KEY_OFFSET_Y_R0, offsetY)
                }
                90 -> {
                    editor.putFloat(KEY_SCALE_X_R90, scaleX)
                    editor.putFloat(KEY_SCALE_Y_R90, scaleY)
                    editor.putFloat(KEY_OFFSET_X_R90, offsetX)
                    editor.putFloat(KEY_OFFSET_Y_R90, offsetY)
                }
                180 -> {
                    editor.putFloat(KEY_SCALE_X_R180, scaleX)
                    editor.putFloat(KEY_SCALE_Y_R180, scaleY)
                    editor.putFloat(KEY_OFFSET_X_R180, offsetX)
                    editor.putFloat(KEY_OFFSET_Y_R180, offsetY)
                }
                270 -> {
                    editor.putFloat(KEY_SCALE_X_R270, scaleX)
                    editor.putFloat(KEY_SCALE_Y_R270, scaleY)
                    editor.putFloat(KEY_OFFSET_X_R270, offsetX)
                    editor.putFloat(KEY_OFFSET_Y_R270, offsetY)
                }
            }
        } else {
            editor.putFloat(KEY_SCALE_X, scaleX)
            editor.putFloat(KEY_SCALE_Y, scaleY)
            editor.putFloat(KEY_OFFSET_X, offsetX)
            editor.putFloat(KEY_OFFSET_Y, offsetY)
        }

        editor.apply()
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
        val scaleX = getCurrentScaleX()
        val scaleY = getCurrentScaleY()
        val offsetX = getCurrentOffsetX()
        val offsetY = getCurrentOffsetY()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rotationSpecific = prefs.getBoolean(KEY_ROTATION_SPECIFIC, false)

        val info = buildString {
            if (rotationSpecific) {
                append("旋转: ${currentRotation}°\n")
            }
            append("系数: X=${String.format("%.5f", scaleX)}, Y=${String.format("%.5f", scaleY)}\n")
            append("偏移: X=${String.format("%.4f", offsetX)}, Y=${String.format("%.4f", offsetY)}")
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
