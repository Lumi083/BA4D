package com.miradesktop.ba4d.overlay

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.net.URLConnection

object OverlayContentUrl {
    private const val HOST = "appassets.androidplatform.net"
    private const val SCHEME = "https"
    private const val ASSET_PREFIX = "/assets/"
    private const val FILES_PREFIX = "/files/"

    fun fromStartupFile(context: Context, startupFile: String?): String {
        val fileName = startupFile ?: "ba-spark-lite.mira.html"
        val userFile = File(context.filesDir, fileName)
        return if (userFile.exists()) {
            "$SCHEME://$HOST$FILES_PREFIX${Uri.encode(fileName)}"
        } else {
            "$SCHEME://$HOST$ASSET_PREFIX${Uri.encode(fileName)}"
        }
    }

    fun defaultOverlayUrl(): String = "$SCHEME://$HOST$ASSET_PREFIX" + "ba-spark-lite.mira.html"

    fun shouldIntercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme != SCHEME || uri.host != HOST) {
            return null
        }

        val path = uri.encodedPath ?: return null
        return when {
            path.startsWith(ASSET_PREFIX) -> {
                val relativePath = Uri.decode(path.removePrefix(ASSET_PREFIX))
                openAsset(context, relativePath)
            }
            path.startsWith(FILES_PREFIX) -> {
                val relativePath = Uri.decode(path.removePrefix(FILES_PREFIX))
                openUserFile(context, relativePath)
            }
            else -> null
        }
    }

    private fun openAsset(context: Context, relativePath: String): WebResourceResponse? {
        if (relativePath.isBlank()) {
            return null
        }

        val stream = runCatching { context.assets.open(relativePath) }.getOrNull() ?: return null
        return WebResourceResponse(guessMimeType(relativePath), null, stream)
    }

    private fun openUserFile(context: Context, relativePath: String): WebResourceResponse? {
        if (relativePath.isBlank()) {
            return null
        }

        val baseDir = context.filesDir.canonicalFile
        val targetFile = runCatching { File(baseDir, relativePath).canonicalFile }.getOrNull() ?: return null
        if (!targetFile.path.startsWith(baseDir.path + File.separator)) {
            return null
        }
        if (!targetFile.isFile) {
            return null
        }

        val stream = runCatching { targetFile.inputStream() }.getOrNull() ?: return null
        return WebResourceResponse(guessMimeType(targetFile.name), null, stream)
    }

    private fun guessMimeType(name: String): String {
        return URLConnection.guessContentTypeFromName(name) ?: "text/plain"
    }
}
