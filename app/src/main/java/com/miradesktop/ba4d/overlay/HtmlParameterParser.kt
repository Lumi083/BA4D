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
        val alwaysTrail: Boolean = false,
        // Default values from HTML
        val colorDefault: String? = null,
        val trailColorDefault: String? = null,
        val scaleDefault: Float? = null,
        val speedDefault: Float? = null,
        val maxTrailDefault: Int? = null,
        val sparkRateDefault: Float? = null,
        val opacityMulDefault: Float? = null,
        val fpsLimitDefault: Int? = null
    )

    /**
     * Parse HTML file to detect which parameters are supported
     * Looks for patterns like: $rgba | color | ... : defaultValue or $num | scale | ... : defaultValue
     */
    fun parseHtmlFile(context: Context, filename: String): SupportedParams {
        val content = readFileContent(context, filename) ?: return SupportedParams()

        val params = mutableSetOf<String>()
        val defaults = mutableMapOf<String, String>()

        // Parse HTML comment header (first 50 lines should be enough)
        val lines = content.lines().take(50)
        var inComment = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("<!--")) {
                inComment = true
            }

            if (inComment) {
                // Match patterns like: $rgba | color | ... : rgba(255, 255, 180, 1)
                // or $num | scale | ... : 1.0
                // Use lastIndexOf to find the last colon
                val paramMatch = Regex("""\$\w+\s*\|\s*(\w+)\s*\|""").find(trimmed)
                if (paramMatch != null) {
                    val paramName = paramMatch.groupValues[1]
                    params.add(paramName)

                    // Find the last colon to extract default value
                    val lastColonIndex = trimmed.lastIndexOf(':')
                    if (lastColonIndex != -1 && lastColonIndex < trimmed.length - 1) {
                        val defaultValue = trimmed.substring(lastColonIndex + 1).trim()
                        defaults[paramName] = defaultValue
                    }
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
            alwaysTrail = params.contains("alwaysTrail"),
            colorDefault = defaults["color"],
            trailColorDefault = defaults["trailColor"],
            scaleDefault = defaults["scale"]?.toFloatOrNull(),
            speedDefault = defaults["speed"]?.toFloatOrNull(),
            maxTrailDefault = defaults["maxTrail"]?.toIntOrNull(),
            sparkRateDefault = defaults["sparkRate"]?.toFloatOrNull(),
            opacityMulDefault = defaults["opacityMul"]?.toFloatOrNull(),
            fpsLimitDefault = defaults["fpsLimit"]?.toIntOrNull()
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
