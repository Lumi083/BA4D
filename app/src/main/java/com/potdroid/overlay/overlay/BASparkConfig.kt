package com.potdroid.overlay.overlay

import android.content.SharedPreferences
import org.json.JSONObject

data class BASparkConfig(
    val fpsLimit: Int = 30,
    val color: String = "rgba(35, 207, 255, 1)",
    val clickColor: String = "rgba(87, 164, 255, 1)",
    val scale: Float = 1.0f,
    val speed: Float = 1.0f,
    val maxTrail: Int = 12,
    val sparkRate: Float = 0.15f,
    val alwaysTrail: Boolean = false,
    val dpr: Int = 0,
    val opacityMul: Float = 1.0f,
    val port: Int = 42891
) {

    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("fpsLimit", fpsLimit)
            .put("color", color)
            .put("clickColor", clickColor)
            .put("scale", scale)
            .put("speed", speed)
            .put("maxTrail", maxTrail)
            .put("sparkRate", sparkRate)
            .put("alwaysTrail", alwaysTrail)
            .put("dpr", dpr)
            .put("opacityMul", opacityMul)
            .put("port", port)
    }

    companion object {
        const val PREFS_NAME = "baspark_config"

        private const val KEY_FPS_LIMIT = "fps_limit"
        private const val KEY_COLOR = "color"
        private const val KEY_CLICK_COLOR = "click_color"
        private const val KEY_SCALE = "scale"
        private const val KEY_SPEED = "speed"
        private const val KEY_MAX_TRAIL = "max_trail"
        private const val KEY_SPARK_RATE = "spark_rate"
        private const val KEY_ALWAYS_TRAIL = "always_trail"
        private const val KEY_DPR = "dpr"
        private const val KEY_OPACITY_MUL = "opacity_mul"
        private const val KEY_PORT = "port"

        fun fromPreferences(prefs: SharedPreferences): BASparkConfig {
            return BASparkConfig(
                fpsLimit = prefs.getInt(KEY_FPS_LIMIT, 60).coerceIn(15, 240),
                color = prefs.getString(KEY_COLOR, null).orEmpty().ifBlank { "rgba(87, 164, 255, 1)" },
                clickColor = prefs.getString(KEY_CLICK_COLOR, null).orEmpty().ifBlank { "rgba(87, 164, 255, 1)" },
                scale = prefs.getFloat(KEY_SCALE, 1.5f).coerceIn(0.5f, 3.0f),
                speed = prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(0.2f, 3.0f),
                maxTrail = prefs.getInt(KEY_MAX_TRAIL, 12).coerceIn(0, 64),
                sparkRate = prefs.getFloat(KEY_SPARK_RATE, 0.10f).coerceIn(0f, 1f),
                alwaysTrail = prefs.getBoolean(KEY_ALWAYS_TRAIL, false),
                dpr = prefs.getInt(KEY_DPR, 0).coerceIn(0, 500),
                opacityMul = prefs.getFloat(KEY_OPACITY_MUL, 1.0f).coerceIn(0f, 1f),
                port = prefs.getInt(KEY_PORT, 42891).coerceIn(1, 65535)
            )
        }

        fun save(prefs: SharedPreferences, config: BASparkConfig) {
            prefs.edit()
                .putInt(KEY_FPS_LIMIT, config.fpsLimit.coerceIn(15, 240))
                .putString(KEY_COLOR, config.color)
                .putString(KEY_CLICK_COLOR, config.clickColor)
                .putFloat(KEY_SCALE, config.scale.coerceIn(0.5f, 3.0f))
                .putFloat(KEY_SPEED, config.speed.coerceIn(0.2f, 3.0f))
                .putInt(KEY_MAX_TRAIL, config.maxTrail.coerceIn(0, 64))
                .putFloat(KEY_SPARK_RATE, config.sparkRate.coerceIn(0f, 1f))
                .putBoolean(KEY_ALWAYS_TRAIL, config.alwaysTrail)
                .putInt(KEY_DPR, config.dpr.coerceIn(0, 500))
                .putFloat(KEY_OPACITY_MUL, config.opacityMul.coerceIn(0f, 1f))
                .putInt(KEY_PORT, config.port.coerceIn(1, 65535))
                .commit()
        }
    }
}
