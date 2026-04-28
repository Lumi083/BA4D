package com.potdroid.overlay.overlay

import android.content.SharedPreferences
import org.json.JSONObject

data class BASparkConfig(
    val fpsLimit: Int = 60,
    val color: String = "rgba(87, 164, 255, 1)",
    val trailColor: String = "rgba(0, 200, 255, 1)",
    val scale: Float = 1.5f,
    val speed: Float = 1.0f,
    val maxTrail: Int = 12,
    val sparkRate: Float = 0.10f,
    val alwaysTrail: Boolean = false,
    val dpr: Int = 0,
    val opacityMul: Float = 1.0f,
    val port: Int = 42891,
    val adaptiveColor: Boolean = false
) {

    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("fpsLimit", fpsLimit)
            .put("color", color)
            .put("trailColor", trailColor)
            .put("scale", scale)
            .put("speed", speed)
            .put("maxTrail", maxTrail)
            .put("sparkRate", sparkRate)
            .put("alwaysTrail", alwaysTrail)
            .put("dpr", dpr)
            .put("opacityMul", opacityMul)
            .put("port", port)
            .put("adaptiveColor", adaptiveColor)
    }

    companion object {
        const val PREFS_NAME = "baspark_config"

        private const val KEY_FPS_LIMIT = "fps_limit"
        private const val KEY_COLOR = "color"
        private const val KEY_TRAIL_COLOR = "trail_color"
        private const val KEY_SCALE = "scale"
        private const val KEY_SPEED = "speed"
        private const val KEY_MAX_TRAIL = "max_trail"
        private const val KEY_SPARK_RATE = "spark_rate"
        private const val KEY_ALWAYS_TRAIL = "always_trail"
        private const val KEY_DPR = "dpr"
        private const val KEY_OPACITY_MUL = "opacity_mul"
        private const val KEY_PORT = "port"
        private const val KEY_ADAPTIVE_COLOR = "adaptive_color"

        fun fromPreferences(prefs: SharedPreferences): BASparkConfig {
            return BASparkConfig(
                fpsLimit = prefs.getInt(KEY_FPS_LIMIT, 60),
                color = prefs.getString(KEY_COLOR, null).orEmpty().ifBlank { "rgba(87, 164, 255, 1)" },
                trailColor = prefs.getString(KEY_TRAIL_COLOR, null).orEmpty().ifBlank { "rgba(0, 200, 255, 1)" },
                scale = prefs.getFloat(KEY_SCALE, 1.5f),
                speed = prefs.getFloat(KEY_SPEED, 1.0f),
                maxTrail = prefs.getInt(KEY_MAX_TRAIL, 12),
                sparkRate = prefs.getFloat(KEY_SPARK_RATE, 0.10f),
                alwaysTrail = prefs.getBoolean(KEY_ALWAYS_TRAIL, false),
                dpr = prefs.getInt(KEY_DPR, 0),
                opacityMul = prefs.getFloat(KEY_OPACITY_MUL, 1.0f),
                port = prefs.getInt(KEY_PORT, 42891),
                adaptiveColor = prefs.getBoolean(KEY_ADAPTIVE_COLOR, false)
            )
        }

        fun save(prefs: SharedPreferences, config: BASparkConfig) {
            prefs.edit()
                .putInt(KEY_FPS_LIMIT, config.fpsLimit)
                .putString(KEY_COLOR, config.color)
                .putString(KEY_TRAIL_COLOR, config.trailColor)
                .putFloat(KEY_SCALE, config.scale)
                .putFloat(KEY_SPEED, config.speed)
                .putInt(KEY_MAX_TRAIL, config.maxTrail)
                .putFloat(KEY_SPARK_RATE, config.sparkRate)
                .putBoolean(KEY_ALWAYS_TRAIL, config.alwaysTrail)
                .putInt(KEY_DPR, config.dpr)
                .putFloat(KEY_OPACITY_MUL, config.opacityMul)
                .putInt(KEY_PORT, config.port)
                .putBoolean(KEY_ADAPTIVE_COLOR, config.adaptiveColor)
                .commit()
        }
    }
}
