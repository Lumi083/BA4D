package com.miradesktop.ba4d.overlay

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class OverlayWebViewClient(
    private val context: Context,
    private val onPageFinishedCallback: (WebView?, String?) -> Unit
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val safeRequest = request ?: return super.shouldInterceptRequest(view, request)
        return OverlayContentUrl.shouldIntercept(context, safeRequest)
            ?: super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback(view, url)
    }
}
