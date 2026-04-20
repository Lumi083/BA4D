package com.potdroid.overlay.nativews

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-side Mimosa subset server.
 *
 * Supported protocol:
 * - Client subscription: [0xA0, 0x01, fps]
 * - Mouse packet output: [type=0x01][x:int32][y:int32][wheel:float32][btn:uint8]
 */
class AndroidMimosaServer(
    private val bindPort: Int = 42891
) : WebSocketServer(InetSocketAddress("127.0.0.1", bindPort)) {

    private data class MouseSubscription(
        val fps: Int,
        var expireAtMs: Long
    )

    private val started = AtomicBoolean(false)
    private val allClients = ConcurrentHashMap.newKeySet<WebSocket>()
    private val mouseSubscribers = ConcurrentHashMap<WebSocket, MouseSubscription>()

    fun startSafe() {
        if (started.compareAndSet(false, true)) {
            start()
        }
    }

    fun stopSafe() {
        if (started.compareAndSet(true, false)) {
            runCatching { stop(250) }
        }
        mouseSubscribers.clear()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        allClients.add(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        allClients.remove(conn)
        mouseSubscribers.remove(conn)
    }

    override fun onMessage(conn: WebSocket, message: String?) {
        // Ignore text payloads for this protocol subset.
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer?) {
        if (message == null || message.remaining() < 3) return

        val op = message.get(0).toInt() and 0xFF
        val device = message.get(1).toInt() and 0xFF
        val fps = (message.get(2).toInt() and 0xFF).coerceIn(1, 240)

        if (op == 0xA0 && device == 0x01) {
            mouseSubscribers[conn] = MouseSubscription(
                fps = fps,
                expireAtMs = System.currentTimeMillis() + 30_000
            )
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        if (conn != null) {
            allClients.remove(conn)
            mouseSubscribers.remove(conn)
        }
    }

    override fun onStart() {
        connectionLostTimeout = 30
    }

    fun publishMouse(x: Int, y: Int, wheel: Float = 0f, btnMask: Int = 0) {
        val now = System.currentTimeMillis()
        val payload = ByteBuffer.allocate(14)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0x01)
            .putInt(x)
            .putInt(y)
            .putFloat(wheel)
            .put((btnMask and 0xFF).toByte())
            .array()

        val iterator = mouseSubscribers.entries.iterator()
        while (iterator.hasNext()) {
            val (ws, sub) = iterator.next()
            if (!ws.isOpen || now > sub.expireAtMs) {
                iterator.remove()
                continue
            }
            runCatching { ws.send(payload) }
        }
    }

    fun publishBackgroundEvent(jsonPayload: String) {
        val iterator = allClients.iterator()
        while (iterator.hasNext()) {
            val ws = iterator.next()
            if (!ws.isOpen) {
                iterator.remove()
                mouseSubscribers.remove(ws)
                continue
            }
            runCatching { ws.send(jsonPayload) }
        }
    }
}
