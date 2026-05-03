package com.miradesktop.ba4d

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.miradesktop.ba4d.databinding.FragmentContentBinding
import com.miradesktop.ba4d.overlay.BASparkConfig
import com.miradesktop.ba4d.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContentFragment : Fragment() {
    private var _binding: FragmentContentBinding? = null
    private val binding get() = _binding!!
    private val files = mutableListOf<String>()
    private var selectedFile: String? = null
    private lateinit var adapter: FileAdapter

    private val assetFiles = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FileAdapter(files, ::onFileClick, ::getStartupFile)
        binding.fileListRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.fileListRecyclerView.adapter = adapter

        refreshFiles()

        binding.setDefaultButton.setOnClickListener { setAsStartup() }
        binding.deleteButton.setOnClickListener { deleteFile() }
        binding.copyButton.setOnClickListener { copyToClipboard() }
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.saveButton.setOnClickListener { saveFile() }

        binding.filenameInput.addTextChangedListener { updateSaveButtonVisibility() }
        binding.contentInput.addTextChangedListener {
            updateSaveButtonVisibility()
            updateClipboardButtons()
        }
        binding.contentInput.setOnFocusChangeListener { _, _ -> updateClipboardButtons() }
    }

    private fun refreshFiles() {
        files.clear()
        assetFiles.clear()

        // Load from assets (builtin files)
        requireContext().assets.list("")?.filter { it.endsWith(".html") }?.let {
            assetFiles.addAll(it)
            files.addAll(it)
        }

        // Load from filesDir (user-created files)
        requireContext().filesDir.listFiles()?.filter { it.name.endsWith(".html") }?.forEach {
            if (!files.contains(it.name)) {
                files.add(it.name)
            }
        }

        files.sort()
        adapter.notifyDataSetChanged()
    }

    private fun onFileClick(file: String) {
        selectedFile = file
        binding.actionButtons.visibility = View.VISIBLE
        binding.setDefaultButton.isEnabled = true
        binding.deleteButton.isEnabled = true

        // Load file content asynchronously to avoid blocking UI
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val fileSize = if (assetFiles.contains(file)) {
                        // Get asset file size - try openFd first, fallback to reading stream
                        try {
                            requireContext().assets.openFd(file).use { it.length }
                        } catch (e: Exception) {
                            // For compressed assets, read the stream to get size
                            requireContext().assets.open(file).use { it.available().toLong() }
                        }
                    } else {
                        // Get user file size
                        File(requireContext().filesDir, file).length()
                    }

                    // Check if file is too large (> 20KB)
                    if (fileSize > 20 * 1024) {
                        return@withContext Pair(null, "too_large")
                    }

                    val content = if (assetFiles.contains(file)) {
                        // Builtin files from assets
                        requireContext().assets.open(file).bufferedReader().use { it.readText() }
                    } else {
                        // User-created files from filesDir
                        File(requireContext().filesDir, file).readText()
                    }
                    Pair(content, "success")
                } catch (e: Exception) {
                    Pair(null, "error: ${e.message}")
                }
            }

            when {
                result.second == "success" -> {
                    binding.filenameInput.setText(file)
                    binding.contentInput.setText(result.first)
                    updateSaveButtonVisibility()
                }
                result.second == "too_large" -> {
                    binding.filenameInput.setText(file)
                    binding.contentInput.setText("")
                    Toast.makeText(requireContext(), "文件过大 (>20KB)，不显示内容", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(requireContext(), "加载失败: ${result.second}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun viewSource() {
        val file = selectedFile ?: return
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    // Try filesDir first (user-created files)
                    val userFile = File(requireContext().filesDir, file)
                    if (userFile.exists()) {
                        userFile.readText()
                    } else {
                        // Fall back to assets (built-in files)
                        requireContext().assets.open(file).bufferedReader().use { it.readText() }
                    }
                } catch (e: Exception) {
                    null
                }
            }

            if (content != null) {
                binding.filenameInput.setText(file)
                binding.contentInput.setText(content)
            } else {
                Toast.makeText(requireContext(), "加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteFile() {
        val file = selectedFile ?: return
        if (assetFiles.contains(file)) {
            Toast.makeText(requireContext(), "不允许删除内置文件", Toast.LENGTH_SHORT).show()
            return
        }
        File(requireContext().filesDir, file).delete()
        selectedFile = null
        binding.actionButtons.visibility = View.GONE
        binding.filenameInput.setText("")
        binding.contentInput.setText("")
        binding.setDefaultButton.isEnabled = false
        binding.deleteButton.isEnabled = false
        refreshFiles()
    }

    private fun saveFile() {
        val name = binding.filenameInput.text.toString().trim()
        val content = binding.contentInput.text.toString()
        if (name.isEmpty() || !name.endsWith(".html")) {
            Toast.makeText(requireContext(), "请输入有效的HTML文件名", Toast.LENGTH_SHORT).show()
            return
        }
        if (assetFiles.contains(name)) {
            Toast.makeText(requireContext(), "不允许覆盖内置文件", Toast.LENGTH_SHORT).show()
            return
        }
        File(requireContext().filesDir, name).writeText(content)
        refreshFiles()
        Toast.makeText(requireContext(), "已保存 $name", Toast.LENGTH_SHORT).show()
    }

    private fun setAsStartup() {
        val file = selectedFile ?: return
        requireContext().getSharedPreferences("app_prefs", 0).edit().putString("startup_file", file).apply()
        adapter.notifyDataSetChanged()

        if (isServiceRunning(OverlayService::class.java)) {
            restartOverlay()
            Toast.makeText(requireContext(), "已启用", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "已启用，请在主页开启 $file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    private fun restartOverlay() {
        requireContext().stopService(Intent(requireContext(), OverlayService::class.java))

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

        val config = BASparkConfig.fromPreferences(requireContext().getSharedPreferences(BASparkConfig.PREFS_NAME, 0))
        val projectionResultCode = requireActivity().intent.getIntExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, -1)
        val projectionData = requireActivity().intent.getParcelableExtra<Intent>(OverlayService.EXTRA_PROJECTION_DATA)

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

    private fun getStartupFile() = requireContext().getSharedPreferences("app_prefs", 0).getString("startup_file", null)

    private fun updateSaveButtonVisibility() {
        binding.saveButton.isEnabled = binding.filenameInput.text?.isNotEmpty() == true && binding.contentInput.text?.isNotEmpty() == true
    }

    private fun updateClipboardButtons() {
        val hasContent = binding.contentInput.text?.isNotEmpty() == true
        binding.copyButton.isEnabled = hasContent
        binding.pasteButton.isEnabled = hasContent
    }

    private fun copyToClipboard() {
        val text = binding.contentInput.text?.toString() ?: return
        if (text.isEmpty()) return

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("code", text))
        Toast.makeText(requireContext(), "已复制全部内容", Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: return
            val start = binding.contentInput.selectionStart
            val end = binding.contentInput.selectionEnd
            val currentText = binding.contentInput.text ?: return
            currentText.replace(start, end, text)
            Toast.makeText(requireContext(), "已粘贴", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class FileAdapter(
    private val files: MutableList<String>,
    private val onClick: (String) -> Unit,
    private val getStartupFile: () -> String?
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private var selectedPos = -1

    class ViewHolder(val card: MaterialCardView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val card = MaterialCardView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 8, 0, 8)
            }
            cardElevation = 4f
            radius = 12f
            setContentPadding(16, 16, 16, 16)
        }
        val text = android.widget.TextView(parent.context).apply {
            textSize = 14f
        }
        card.addView(text)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val isStartup = file == getStartupFile()
        val text = holder.card.getChildAt(0) as android.widget.TextView
        text.text = if (isStartup) "$file [默认]" else file
        holder.card.setCardBackgroundColor(if (position == selectedPos) 0xFFE3F2FD.toInt() else 0xFFFFFFFF.toInt())
        holder.card.setOnClickListener {
            val oldPos = selectedPos
            selectedPos = position
            notifyItemChanged(oldPos)
            notifyItemChanged(selectedPos)
            onClick(file)
        }
    }

    override fun getItemCount() = files.size
}
