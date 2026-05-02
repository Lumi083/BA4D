package com.miradesktop.ba4d.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku    
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import android.view.Surface
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Collect touch-like input events with shell privilege by running getevent via Shizuku.
 */
class ShizukuMimosaCollector(
    private val context: Context,
    private val onPointer: (pointerId: Int, x: Int, y: Int, pressed: Boolean) -> Unit,
    private val onBackgroundLog: (eventName: String, detail: String, x: Int, y: Int) -> Unit,
    private val fpsLimit: Int = 60
) {

    private data class AxisRange(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    private data class ScreenGeometry(
        val widthPx: Int,
        val heightPx: Int,
        val realWidthPx: Int,
        val realHeightPx: Int,
        val rotation: Int,
        val leftInset: Int,
        val topInset: Int
    )

    private data class SlotState(
        var x: Int = -1,
        var y: Int = -1,
        var pressed: Boolean = false,
        var dirty: Boolean = false,
        var lastUpdateMs: Long = 0,
        var lastEmittedX: Int = -1,
        var lastEmittedY: Int = -1,
        var lastEmittedPressed: Boolean = false
    )

    private data class DeviceState(
        var currentSlot: Int = 0,
        var lastUpdateMs: Long = 0,
        val slots: MutableMap<Int, SlotState> = mutableMapOf()
    )

    companion object {
        const val REQUEST_CODE = 9003

        private val EVENT_LINE_REGEX =
            Regex(".*(/dev/input/event\\d+):\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)$")
        private val ADD_DEVICE_REGEX =
            Regex("add device\\s+\\d+:\\s+(/dev/input/event\\d+)")
        private val ABS_X_REGEX =
            Regex("ABS_(?:MT_)?POSITION_X.*min\\s+(-?\\d+),\\s+max\\s+(-?\\d+)", RegexOption.IGNORE_CASE)
        private val ABS_Y_REGEX =
            Regex("ABS_(?:MT_)?POSITION_Y.*min\\s+(-?\\d+),\\s+max\\s+(-?\\d+)", RegexOption.IGNORE_CASE)
        private val HEX_LONG_REGEX = Regex("^[0-9a-fA-F]{6,8}$")

        private const val DEVICE_STALE_MS = 180L
        private const val MOVE_LOG_INTERVAL_MS = 80L

        fun isShizukuReady(): Boolean {
            return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        }

        fun hasShizukuPermission(): Boolean {
            return runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        }
    }

    private val active = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var remoteProcess: IRemoteProcess? = null

    @Volatile
    private var axisCalibrations: Map<String, AxisRange> = emptyMap()

    private val stateByDevice = mutableMapOf<String, DeviceState>()
    private var preferredDevicePath: String? = null
    private var lastMoveLogMs = 0L
    private var lastEmitMs = 0L
    private val minEmitIntervalMs = (1000.0 / fpsLimit).toLong()

    fun start() {
        if (!isShizukuReady() || !hasShizukuPermission()) return
        if (!active.compareAndSet(false, true)) return

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope?.launch {
            runCatching {
                val binder = Shizuku.getBinder() ?: error("Shizuku binder unavailable")
                val service = IShizukuService.Stub.asInterface(binder) ?: error("IShizukuService unavailable")
                axisCalibrations = queryAxisCalibrations(service)
                remoteProcess = service.newProcess(
                    arrayOf("sh", "-c", "getevent -lt"),
                    null,
                    null
                )

                val input = ParcelFileDescriptor.AutoCloseInputStream(remoteProcess!!.getInputStream())
                val reader = BufferedReader(InputStreamReader(input))
                while (active.get()) {
                    val line = reader.readLine() ?: break
                    parseLine(line)
                }
            }.onFailure {
                onBackgroundLog("SHIZUKU_ERROR", it.message ?: "unknown", 0, 0)
            }
        }
    }

    fun stop() {
        active.set(false)
        runCatching { remoteProcess?.destroy() }
        remoteProcess = null
        scope?.cancel()
        scope = null
    }

    private fun parseLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        val eventMatch = EVENT_LINE_REGEX.find(trimmed) ?: return
        val devicePath = eventMatch.groupValues[1]
        val eventType = eventMatch.groupValues[2]
        val code = eventMatch.groupValues[3]
        val valueHexOrWord = eventMatch.groupValues[4]

        val now = SystemClock.elapsedRealtime()
        val deviceState = stateByDevice.getOrPut(devicePath) { DeviceState() }

        when (eventType) {
            "EV_ABS" -> {
                when (code) {
                    "ABS_MT_SLOT" -> {
                        parseInt(valueHexOrWord)?.let { slot ->
                            deviceState.currentSlot = slot.coerceAtLeast(0)
                        }
                    }

                    "ABS_MT_TRACKING_ID" -> {
                        val slot = currentSlotState(deviceState)
                        parseInt(valueHexOrWord)?.let { trackingId ->
                            slot.pressed = trackingId >= 0
                            slot.dirty = true
                            slot.lastUpdateMs = now
                            deviceState.lastUpdateMs = now
                            maybeSwitchPreferredDevice(devicePath, slot, now)
                        }
                    }

                    "ABS_MT_POSITION_X", "ABS_X" -> {
                        parseInt(valueHexOrWord)?.let { x ->
                            val slot = currentSlotState(deviceState)
                            slot.x = x
                            slot.dirty = true
                            slot.lastUpdateMs = now
                            deviceState.lastUpdateMs = now
                            maybeSwitchPreferredDevice(devicePath, slot, now)
                        }
                    }

                    "ABS_MT_POSITION_Y", "ABS_Y" -> {
                        parseInt(valueHexOrWord)?.let { y ->
                            val slot = currentSlotState(deviceState)
                            slot.y = y
                            slot.dirty = true
                            slot.lastUpdateMs = now
                            deviceState.lastUpdateMs = now
                            maybeSwitchPreferredDevice(devicePath, slot, now)
                        }
                    }
                }
            }

            "EV_KEY" -> {
                if (code == "BTN_TOUCH" || code == "BTN_TOOL_FINGER") {
                    val down = valueHexOrWord.equals("DOWN", true) || valueHexOrWord == "1" || valueHexOrWord == "00000001"
                    val up = valueHexOrWord.equals("UP", true) || valueHexOrWord == "0" || valueHexOrWord == "00000000"
                    if (down || up) {
                        val slot = currentSlotState(deviceState)
                        slot.pressed = down
                        slot.dirty = true
                        slot.lastUpdateMs = now
                        deviceState.lastUpdateMs = now
                        maybeSwitchPreferredDevice(devicePath, slot, now)
                    }
                }
            }

            "EV_SYN" -> {
                if (code == "SYN_REPORT") {
                    emitDirtySlots(devicePath, force = false)
                }
            }
        }
    }

    private fun currentSlotState(deviceState: DeviceState): SlotState {
        return deviceState.slots.getOrPut(deviceState.currentSlot) { SlotState() }
    }

    private fun maybeSwitchPreferredDevice(devicePath: String, state: SlotState, now: Long) {
        val current = preferredDevicePath
        if (current == null) {
            preferredDevicePath = devicePath
            onBackgroundLog("SHIZUKU_DEVICE", "selected:$devicePath", state.x, state.y)
            return
        }
        if (current == devicePath) return

        val currentState = stateByDevice[current]
        val currentStale = currentState == null || now - currentState.lastUpdateMs > DEVICE_STALE_MS
        if (currentStale && (state.x >= 0 || state.y >= 0)) {
            preferredDevicePath = devicePath
            onBackgroundLog("SHIZUKU_DEVICE", "switch:$current->$devicePath", state.x, state.y)
        }
    }

    private fun emitDirtySlots(devicePath: String, force: Boolean) {
        val now = SystemClock.elapsedRealtime()

        val preferred = preferredDevicePath
        if (preferred != null && preferred != devicePath) {
            val preferredState = stateByDevice[preferred]
            val preferredStale = preferredState == null || now - preferredState.lastUpdateMs > DEVICE_STALE_MS
            if (!preferredStale) return
            preferredDevicePath = devicePath
            onBackgroundLog("SHIZUKU_DEVICE", "fallback:$preferred->$devicePath", 0, 0)
        }

        val deviceState = stateByDevice[devicePath] ?: return
        val screen = resolveScreenGeometry()

        // Check if any slot has a state change (DOWN/UP) that must be emitted immediately
        val hasStateChange = deviceState.slots.values.any { state ->
            state.dirty && state.pressed != state.lastEmittedPressed
        }

        // Throttle only MOVE events based on fpsLimit, never throttle DOWN/UP
        if (!force && !hasStateChange && now - lastEmitMs < minEmitIntervalMs) {
            return
        }
        lastEmitMs = now

        deviceState.slots.forEach { (slotId, state) ->
            if (!force && !state.dirty) return@forEach

            val rawX = if (state.x >= 0) state.x else state.lastEmittedX
            val rawY = if (state.y >= 0) state.y else state.lastEmittedY
            if (rawX < 0 || rawY < 0) return@forEach

            val mapped = mapToDisplay(rawX, rawY, axisCalibrations[devicePath], screen)
            onPointer(slotId, mapped.first, mapped.second, state.pressed)

            val eventName = when {
                !state.pressed -> "UP"
                !state.lastEmittedPressed -> "DOWN"
                else -> "MOVE"
            }

            if (eventName == "MOVE") {
                if (now - lastMoveLogMs >= MOVE_LOG_INTERVAL_MS) {
                    onBackgroundLog(eventName, "getevent:$devicePath#$slotId", mapped.first, mapped.second)
                    lastMoveLogMs = now
                }
            } else {
                onBackgroundLog(eventName, "getevent:$devicePath#$slotId", mapped.first, mapped.second)
            }

            state.lastEmittedX = mapped.first
            state.lastEmittedY = mapped.second
            state.lastEmittedPressed = state.pressed
            state.dirty = false
        }
    }

    private fun mapToDisplay(rawX: Int, rawY: Int, axisRange: AxisRange?, screen: ScreenGeometry): Pair<Int, Int> {
        val realWidth = screen.realWidthPx.coerceAtLeast(1)
        val realHeight = screen.realHeightPx.coerceAtLeast(1)

        val normalized = if (axisRange == null) {
            val nx = rawX.toDouble() / realWidth.toDouble()
            val ny = rawY.toDouble() / realHeight.toDouble()
            Pair(nx.coerceIn(0.0, 1.0), ny.coerceIn(0.0, 1.0))
        } else {
            val axisWidth = (axisRange.maxX - axisRange.minX).coerceAtLeast(1)
            val axisHeight = (axisRange.maxY - axisRange.minY).coerceAtLeast(1)
            val nx = ((rawX - axisRange.minX).toDouble() / axisWidth.toDouble()).coerceIn(0.0, 1.0)
            val ny = ((rawY - axisRange.minY).toDouble() / axisHeight.toDouble()).coerceIn(0.0, 1.0)
            Pair(nx, ny)
        }

        val rotated = applyDisplayRotation(normalized.first, normalized.second, screen.rotation)

        // Convert to pixel coordinates in the true physical display
        // No inset compensation needed - overlay covers full screen with FLAG_LAYOUT_NO_LIMITS
        val px = (rotated.first * realWidth).roundToInt().coerceIn(0, realWidth - 1)
        val py = (rotated.second * realHeight).roundToInt().coerceIn(0, realHeight - 1)

        return Pair(px, py)
    }

    private fun applyDisplayRotation(nx: Double, ny: Double, rotation: Int): Pair<Double, Double> {
        return when (rotation) {
            Surface.ROTATION_90 -> Pair(ny, 1.0 - nx)
            Surface.ROTATION_180 -> Pair(1.0 - nx, 1.0 - ny)
            Surface.ROTATION_270 -> Pair(1.0 - ny, nx)
            else -> Pair(nx, ny)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveScreenGeometry(): ScreenGeometry {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val realMetrics = DisplayMetrics()
        display.getRealMetrics(realMetrics)

        val rotation = display.rotation

        // No insets - overlay covers full screen with FLAG_LAYOUT_NO_LIMITS
        return ScreenGeometry(
            widthPx = realMetrics.widthPixels.coerceAtLeast(1),
            heightPx = realMetrics.heightPixels.coerceAtLeast(1),
            realWidthPx = realMetrics.widthPixels.coerceAtLeast(1),
            realHeightPx = realMetrics.heightPixels.coerceAtLeast(1),
            rotation = rotation,
            leftInset = 0,
            topInset = 0
        )
    }

    private fun queryAxisCalibrations(service: IShizukuService): Map<String, AxisRange> {
        val process = service.newProcess(arrayOf("sh", "-c", "getevent -pl"), null, null)
        val input = ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream())
        val reader = BufferedReader(InputStreamReader(input))

        var currentDevicePath: String? = null
        var hasTouchCapability = false
        var minX: Int? = null
        var maxX: Int? = null
        var minY: Int? = null
        var maxY: Int? = null

        val ranges = linkedMapOf<String, AxisRange>()

        fun flushCurrent() {
            val devicePath = currentDevicePath ?: return
            val x0 = minX
            val x1 = maxX
            val y0 = minY
            val y1 = maxY
            if (!hasTouchCapability || x0 == null || x1 == null || y0 == null || y1 == null) return
            if (x1 <= x0 || y1 <= y0) return
            ranges[devicePath] = AxisRange(x0, x1, y0, y1)
        }

        while (true) {
            val rawLine = reader.readLine() ?: break
            val line = rawLine.trim()

            val addDevice = ADD_DEVICE_REGEX.find(line)
            if (addDevice != null) {
                flushCurrent()
                currentDevicePath = addDevice.groupValues[1]
                hasTouchCapability = false
                minX = null
                maxX = null
                minY = null
                maxY = null
                continue
            }

            if (line.contains("BTN_TOUCH") || line.contains("ABS_MT_TRACKING_ID")) {
                hasTouchCapability = true
            }

            ABS_X_REGEX.find(line)?.let { match ->
                minX = parseInt(match.groupValues[1])
                maxX = parseInt(match.groupValues[2])
            }
            ABS_Y_REGEX.find(line)?.let { match ->
                minY = parseInt(match.groupValues[1])
                maxY = parseInt(match.groupValues[2])
            }
        }

        flushCurrent()
        runCatching { process.destroy() }
        if (ranges.isEmpty()) {
            onBackgroundLog("SHIZUKU_WARN", "axis calibration unavailable; using clamped raw coordinates", 0, 0)
        }
        return ranges
    }

    private fun parseInt(raw: String): Int? {
        return runCatching {
            if (raw.startsWith("0x", true)) {
                raw.removePrefix("0x").toLong(16).toInt()
            } else if (raw.matches(HEX_LONG_REGEX)) {
                raw.toLong(16).toInt()
            } else {
                raw.toInt()
            }
        }.getOrNull()
    }

}
