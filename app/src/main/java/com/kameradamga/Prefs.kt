package com.kameradamga

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.preference.PreferenceManager

/** Damganın nasıl çizileceğinin anlık görüntüsü (thread güvenli, değişmez). */
data class StampConfig(
    val position: String,
    val sizeFactor: Float,
    val textColor: Int,
    val bgColor: Int,
) {
    companion object {
        fun default() = StampConfig("br", 0.036f, Color.WHITE, Color.TRANSPARENT)
    }
}

class Prefs(ctx: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    val showDateTime get() = sp.getBoolean("show_datetime", true)
    val showAddress get() = sp.getBoolean("show_address", true)
    val showCoords get() = sp.getBoolean("show_coords", true)
    val showAltSpeed get() = sp.getBoolean("show_alt_speed", true)
    val showHeading get() = sp.getBoolean("show_heading", true)
    val showIndex get() = sp.getBoolean("show_index", false)
    val showMap get() = sp.getBoolean("show_map", false)
    val blockScreenshot get() = sp.getBoolean("block_screenshot", true)
    val showCompass get() = sp.getBoolean("show_compass", false)
    val customText get() = sp.getString("custom_text", "") ?: ""

    val grid get() = sp.getBoolean("grid", false)
    val shutterSound get() = sp.getBoolean("shutter_sound", true)
    val tapShoot get() = sp.getBoolean("tap_shoot", false)
    val mirrorFront get() = sp.getBoolean("mirror_front", true)
    val keepOriginal get() = sp.getBoolean("keep_original", false)
    val videoQuality get() = sp.getString("video_quality", "fhd") ?: "fhd"

    var index: Int
        get() = sp.getInt("index", 0)
        set(v) = sp.edit().putInt("index", v).apply()

    var timerSec: Int
        get() = sp.getInt("timer_sec", 0)
        set(v) = sp.edit().putInt("timer_sec", v).apply()

    private fun colorOf(name: String): Int = when (name) {
        "yellow" -> Color.rgb(255, 235, 59)
        "green" -> Color.rgb(102, 255, 102)
        "red" -> Color.rgb(255, 82, 82)
        "cyan" -> Color.rgb(77, 238, 255)
        "black" -> Color.BLACK
        "gray" -> Color.rgb(90, 90, 90)
        else -> Color.WHITE
    }

    private fun withAlpha(color: Int, percent: Int): Int {
        val a = (percent.coerceIn(0, 100) * 255) / 100
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    fun config(): StampConfig {
        val factor = when (sp.getString("text_size", "m")) {
            "s" -> 0.028f
            "l" -> 0.046f
            else -> 0.036f
        }
        val text = withAlpha(colorOf(sp.getString("text_color", "white") ?: "white"), sp.getInt("text_opacity", 100))
        val bg = withAlpha(colorOf(sp.getString("bg_color", "black") ?: "black"), sp.getInt("bg_opacity", 0))
        return StampConfig(sp.getString("position", "br") ?: "br", factor, text, bg)
    }
}
