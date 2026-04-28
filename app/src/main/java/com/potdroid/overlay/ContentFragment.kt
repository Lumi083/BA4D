package com.potdroid.overlay

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.potdroid.overlay.databinding.FragmentContentBinding
import java.io.File

class ContentFragment : Fragment() {
    private var _binding: FragmentContentBinding? = null
    private val binding get() = _binding!!
    private val files = mutableListOf<String>()
    private var selectedFile: String? = null
    private lateinit var adapter: FileAdapter

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
        binding.viewSourceButton.setOnClickListener { viewSource() }
        binding.deleteButton.setOnClickListener { deleteFile() }
        binding.saveButton.setOnClickListener { saveFile() }

        binding.filenameInput.addTextChangedListener { updateSaveButtonVisibility() }
        binding.contentInput.addTextChangedListener { updateSaveButtonVisibility() }
    }

    private fun refreshFiles() {
        files.clear()
        requireContext().assets.list("")?.filter { it.endsWith(".html") }?.let { files.addAll(it) }
        adapter.notifyDataSetChanged()
    }

    private fun onFileClick(file: String) {
        selectedFile = file
        binding.actionButtons.visibility = View.VISIBLE
        binding.filenameInput.setText("")
        binding.contentInput.setText("")
        updateSaveButtonVisibility()
    }

    private fun viewSource() {
        val file = selectedFile ?: return
        val content = try {
            requireContext().assets.open(file).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        binding.filenameInput.setText(file)
        binding.contentInput.setText(content)
    }

    private fun deleteFile() {
        val file = selectedFile ?: return
        if (file.startsWith("ba-spark")) {
            Toast.makeText(requireContext(), "不允许删除内置文件", Toast.LENGTH_SHORT).show()
            return
        }
        File(requireContext().filesDir, file).delete()
        selectedFile = null
        binding.actionButtons.visibility = View.GONE
        binding.filenameInput.setText("")
        binding.contentInput.setText("")
        refreshFiles()
    }

    private fun saveFile() {
        val name = binding.filenameInput.text.toString().trim()
        val content = binding.contentInput.text.toString()
        if (name.isEmpty() || !name.endsWith(".html")) {
            Toast.makeText(requireContext(), "请输入有效的文件名", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(requireContext(), "在主页重启以使用 $file", Toast.LENGTH_SHORT).show()
    }

    private fun getStartupFile() = requireContext().getSharedPreferences("app_prefs", 0).getString("startup_file", null)

    private fun updateSaveButtonVisibility() {
        binding.saveButton.visibility = if (binding.filenameInput.text?.isNotEmpty() == true && binding.contentInput.text?.isNotEmpty() == true) View.VISIBLE else View.GONE
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
