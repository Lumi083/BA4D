package com.miradesktop.ba4d

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.miradesktop.ba4d.databinding.FragmentAboutBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class AboutFragment : Fragment() {
    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.checkUpdateButton.setOnClickListener {
            binding.updateInfoTextView.text = "检查更新中..."
            binding.downloadUpdateButton.visibility = View.GONE
            CoroutineScope(Dispatchers.Main).launch {
                val current = requireContext().assets.open("version.txt").bufferedReader().use { it.readText().trim() }
                val result = withContext(Dispatchers.IO) {
                    try {
                        val conn = URL("https://api.github.com/repos/Lumi083/BA4D/releases/latest").openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", "BA4D-Android")
                        conn.instanceFollowRedirects = true
                        val code = conn.responseCode
                        if (code != 200) "HTTP 错误: $code" else {
                            val response = conn.inputStream.bufferedReader().use { it.readText() }
                            val start = response.indexOf("\"tag_name\":\"")
                            if (start == -1) "解析失败: 未找到版本号" else {
                                val tag = response.substring(start + 12).substringBefore("\"")
                                "ok:$tag"
                            }
                        }
                    } catch (e: java.net.UnknownHostException) { "网络错误: 无法访问 GitHub" }
                    catch (e: java.net.SocketTimeoutException) { "网络错误: 连接超时" }
                    catch (e: Exception) { "检查失败: ${e.javaClass.simpleName}" }
                }
                if (result.startsWith("ok:")) {
                    val latest = result.removePrefix("ok:")
                    val hasUpdate = current != latest.removePrefix("v")
                    binding.updateInfoTextView.text = "当前版本: v$current\n最新版本: $latest\n${if (hasUpdate) "有新版本可用" else "已是最新版本"}"
                    if (hasUpdate) {
                        binding.downloadUpdateButton.visibility = View.VISIBLE
                        binding.downloadUpdateButton.setOnClickListener {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Lumi083/BA4D/releases/latest")))
                        }
                    }
                } else {
                    binding.updateInfoTextView.text = result
                }
            }
        }
        binding.openBilibiliButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/1799636047")))
        }
        binding.openStarButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Lumi083/BA4D")))
        }
        binding.copyQQGroupButton.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("QQ群号", "214791959")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
        binding.openSATmtrButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/3493116084488813")))
        }
        binding.openDebugButton.setOnClickListener {
            (activity as? MainActivity)?.openDebugPage()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
