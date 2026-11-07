package com.kameradamga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/** Damgayı dik duran (upright) bir w x h tuvaline çizer. */
object StampRenderer {

    fun draw(canvas: Canvas, w: Int, h: Int, lines: List<String>, c: StampConfig, map: Bitmap? = null) {
        if ((lines.isEmpty() && map == null) || w <= 0 || h <= 0) return

        val size = min(w, h) * c.sizeFactor
        val right = c.position.endsWith("r")
        val bottom = c.position.startsWith("b")
        val margin = size * 0.6f

        if (lines.isNotEmpty()) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                color = c.textColor
                typeface = Typeface.DEFAULT
                setShadowLayer(size * 0.10f, 0f, 0f, Color.BLACK)
            }

            val lineH = size * 1.22f
            val pad = size * 0.45f
            val widest = lines.maxOf { text.measureText(it) }
            val blockW = widest + pad * 2
            val blockH = lines.size * lineH + pad * 2 - (lineH - size)

            val left = if (right) w - margin - blockW else margin
            val top = if (bottom) h - margin - blockH else margin

            if (Color.alpha(c.bgColor) > 0) {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.bgColor }
                canvas.drawRoundRect(RectF(left, top, left + blockW, top + blockH), pad, pad, bg)
            }

            text.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            val x = if (right) left + blockW - pad else left + pad
            var y = top + pad - text.ascent()
            for (line in lines) {
                canvas.drawText(line, x, y, text)
                y += lineH
            }
        }

        if (map != null) {
            // Harita, yazının karşı köşesine (aynı alt/üst kenara) konur.
            val ms = min(w, h) * 0.27f
            val mx = if (right) margin else w - margin - ms
            val my = if (bottom) h - margin - ms else margin
            val rect = RectF(mx, my, mx + ms, my + ms)
            val radius = ms * 0.06f
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(map, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = ms * 0.015f
                color = Color.WHITE
            }
            canvas.drawRoundRect(rect, radius, radius, border)
        }
    }
}
