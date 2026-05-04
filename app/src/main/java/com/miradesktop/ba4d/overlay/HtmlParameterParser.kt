package com.miradesktop.ba4d.overlay

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Parses HTML file headers to extract supported parameter names
 */
object HtmlParameterParser {

    data class SupportedParams(
        val color: Boolean = false,
        val trailColor: Boolean = false,
        val scale: Boolean = false,
        val speed: Boolean = false,
        val maxTrail: Boolean = false,
        val sparkRate: Boolean = false,
        val opacityMul: Boolean = false,
        val fpsLimit: Boolean = false,
        val alwaysTrail: Boolean = false
    )

    /**
     * Parse HTML file to detect which parameters are supported
     * Looks for patterns like: $rgba | color | ... or $num | scale | ...
     */
    fun parseHtmlFile(context: Context, filename: String): SupportedParams {
        val content = readFileContent(context, filename) ?: return SupportedParams()

        val params = mutableSetOf<String>()

        // Parse HTML comment header (first 50 lines should be enough)
        val lines = content.lines().take(50)
        var inComment = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("<!--")) {
                inComment = true
            }

            if (inComment) {
                // Match patterns like: $rgba | color | ... or $num | scale | ...
                val paramMatch = Regex("""\$\w+\s*\|\s*(\w+)""").find(trimmed)
                if (paramMatch != null) {
                    params.add(paramMatch.groupValues[1])
                }

                if (trimmed.contains("-->")) {
                    inComment = false
                    break
                }
            }
        }

        return SupportedParams(
            color = params.contains("color"),
            trailColor = params.contains("trailColor"),
            scale = params.contains("scale"),
            speed = params.contains("speed"),
            maxTrail = params.contains("maxTrail"),
            sparkRate = params.contains("sparkRate"),
            opacityMul = params.contains("opacityMul"),
            fpsLimit = params.contains("fpsLimit"),
            alwaysTrail = params.contains("alwaysTrail")
        )
    }

    private fun readFileContent(context: Context, filename: String): String? {
        return try {
            // Try to read from filesDir first (user-created files)
            val userFile = File(context.filesDir, filename)
            if (userFile.exists()) {
                userFile.readText()
            } else {
                // Fall back to assets (builtin files)
                context.assets.open(filename).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.readText()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HtmlParameterParser", "Failed to read file: $filename", e)
            null
        }
    }
}
