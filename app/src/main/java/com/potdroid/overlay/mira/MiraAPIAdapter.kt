package com.potdroid.overlay.mira

import android.util.Base64
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MiraAPIAdapter(
    private val webView: WebView,
    private val elementId: String = "baspark-overlay"
) {

    fun sendConfig(config: Map<String, Any>) {
        val json = JSONObject().apply {
            put("type", "__MIRAAPI_PROPS__")
            put("id", elementId)
            put("props", JSONObject(config))
        }
        Log.d("MiraAPI", "Sending config: $json")
        postMessage(json)
    }

    fun sendMouseInput(x: Int, y: Int, pressed: Boolean) {
        val btnMask = if (pressed) 1 else 0
        val payload = ByteBuffer.allocate(14)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0x01.toByte())
            .putInt(x)
            .putInt(y)
            .putFloat(0f)
            .put((btnMask and 0xFF).toByte())
            .array()

        val base64 = Base64.encodeToString(payload, Base64.NO_WRAP)

        val json = JSONObject().apply {
            put("type", "__MIRAAPI_INPUT__")
            put("id", elementId)
            put("device", "mouse")
            put("payload", base64)
        }
        Log.d("MiraAPI", "Sending input: x=$x, y=$y, pressed=$pressed")
        postMessage(json)
    }

    fun sendTouchInput(pointerId: Int, x: Int, y: Int, pressed: Boolean) {
        val payload = ByteBuffer.allocate(14)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0x02.toByte())
            .putInt(pointerId)
            .putInt(x)
            .putInt(y)
            .put(if (pressed) 1 else 0)
            .array()

        val base64 = Base64.encodeToString(payload, Base64.NO_WRAP)

        val json = JSONObject().apply {
            put("type", "__MIRAAPI_INPUT__")
            put("id", elementId)
            put("device", "touch")
            put("payload", base64)
        }
        Log.d("MiraAPI", "Sending touch input: pointerId=$pointerId, x=$x, y=$y, pressed=$pressed")
        postMessage(json)
    }

    private fun postMessage(json: JSONObject) {
        val escaped = JSONObject.quote(json.toString())
        val js = """
            (function() {
                try {
                    console.log('[MiraAPI] Received message');
                    var msg = JSON.parse($escaped);
                    if (msg.payload && typeof msg.payload === 'string') {
                        var binary = atob(msg.payload);
                        var bytes = new Uint8Array(binary.length);
                        for (var i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        msg.payload = bytes.buffer;
                    }
                    console.log('[MiraAPI] Posting message:', msg.type);
                    window.postMessage(msg, '*');
                } catch(e) {
                    console.error('[MiraAPI] Error:', e);
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}
