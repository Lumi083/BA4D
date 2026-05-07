package com.miradesktop.ba4d

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.miradesktop.ba4d.databinding.FragmentDebugBinding
import com.miradesktop.ba4d.overlay.BASparkConfig
import com.miradesktop.ba4d.overlay.OverlayService
import com.miradesktop.ba4d.shizuku.ShizukuMimosaCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugFragment : Fragment() {
    private var _binding: FragmentDebugBinding? = null
    private val binding get() = _binding!!
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDebug()
            handler.postDelayed(this, 67)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDebugBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.refreshDebugButton.setOnClickListener { refreshDebug() }
        binding.openCalibrationButton.setOnClickListener {
            val intent = android.content.Intent(requireContext(), CalibrationActivity::class.java)
            startActivity(intent)
        }
        binding.captureLogcatButton.setOnClickListener { captureLogcat() }
        refreshDebug()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refreshDebug() {
        val isServiceRunning = isServiceRunning(OverlayService::class.java)
        val hasOverlayPerm = Settings.canDrawOverlays(requireContext())
        val shizukuReady = ShizukuMimosaCollector.isShizukuReady()
        val shizukuPerm = ShizukuMimosaCollector.hasShizukuPermission()

        val prefs = requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val config = BASparkConfig.fromPreferences(prefs)
        val currentColor = prefs.getString("current_adaptive_color", "未检测")

        val files = listOf("ba-spark-simple.html", "ba-spark-lite.mira.html")
        val fileStatus = files.joinToString("\n") { file ->
            val exists = try {
                requireContext().assets.open(file).use { true }
            } catch (e: Exception) {
                false
            }
            "${if (exists) "✓" else "✗"} $file"
        }

        binding.overlayStatusTextView.text = """
悬浮窗服务: ${if (isServiceRunning) "✓ 运行中" else "✗ 未启动"}
悬浮窗权限: ${if (hasOverlayPerm) "✓ 已授权" else "✗ 未授权"}
设备像素比: ${resources.displayMetrics.density}

配置参数:
FPS限制: ${config.fpsLimit}
颜色: ${config.color}
自适应颜色: ${if (config.adaptiveColor) "✓ 启用" else "✗ 禁用"}
当前颜色: $currentColor
缩放: ${config.scale} 
最大轨迹: ${config.maxTrail}

Assets 文件:
$fileStatus

问题排查:
${if (!isServiceRunning) "• 悬浮窗服务未启动\n" else ""}${if (!hasOverlayPerm) "• 缺少悬浮窗权限\n" else ""}${if (!shizukuReady) "• Shizuku 未运行（可选）\n" else ""}${if (shizukuReady && !shizukuPerm) "• Shizuku 未授权（可选）\n" else ""}${if (isServiceRunning && hasOverlayPerm) "✓ 基本配置正常" else ""}
        """.trimIndent()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    private fun captureLogcat() {
        binding.captureLogcatButton.isEnabled = false
        Toast.makeText(requireContext(), "正在捕获日志...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val logFile = File(requireContext().getExternalFilesDir(null), "ba4d_logs_$timestamp.txt")

                logFile.bufferedWriter().use { writer ->
                    writer.write("=== BA4D Complete Log Export ===\n")
                    writer.write("Export Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")

                    val crashFiles = requireContext().getExternalFilesDir(null)?.listFiles { file ->
                        file.name.startsWith("crash_") && file.name.endsWith(".txt")
                    }?.sortedByDescending { it.lastModified() }

                    if (!crashFiles.isNullOrEmpty()) {
                        writer.write("=== Crash Reports (${crashFiles.size}) ===\n\n")
                        crashFiles.forEach { crashFile ->
                            writer.write("--- ${crashFile.name} ---\n")
                            crashFile.bufferedReader().use { reader ->
                                reader.copyTo(writer)
                            }
                            writer.write("\n\n")
                        }
                    } else {
                        writer.write("=== No Crash Reports Found ===\n\n")
                    }

                    writer.write("=== Logcat Output ===\n\n")
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime"))
                    process.inputStream.bufferedReader().use { reader ->
                        reader.copyTo(writer)
                    }
                    process.waitFor()
                }

                withContext(Dispatchers.Main) {
                    binding.captureLogcatButton.isEnabled = true
                    shareLogFile(logFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.captureLogcatButton.isEnabled = true
                    Toast.makeText(requireContext(), "捕获日志失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareLogFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BA4D Logcat 日志")
                putExtra(Intent.EXTRA_TEXT, "日志文件: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "分享日志文件"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "分享日志失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
