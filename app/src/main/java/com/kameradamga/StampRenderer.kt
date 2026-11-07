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

    fun draw(
        canvas: Canvas, w: Int, h: Int, lines: List<String>, c: StampConfig,
        map: Bitmap? = null, heading: Float? = null,
    ) {
        if ((lines.isEmpty() && map == null && heading == null) || w <= 0 || h <= 0) return

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

        if (heading != null) {
            // Pusula: yazı altta ise sol üstte, yazı üstte ise sol altta
            val r = min(w, h) * 0.10f
            val cx = margin + r
            val cy = if (bottom) margin + r else h - margin - r
            drawCompass(canvas, cx, cy, r, heading)
        }
    }

    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, r: Float, heading: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 40, 40, 40) }
        canvas.drawCircle(cx, cy, r, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * 0.13f
            color = Color.argb(220, 95, 95, 95)
        }
        canvas.drawCircle(cx, cy, r * 0.93f, ring)

        // Harfler gerçek kuzeye göre döner
        canvas.save()
        canvas.rotate(-heading, cx, cy)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = r * 0.28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val labels = arrayOf("N", "E", "S", "W")
        for (i in 0..3) {
            canvas.save()
            canvas.rotate(i * 90f, cx, cy)
            canvas.drawText(labels[i], cx, cy - r * 0.70f + tp.textSize * 0.35f, tp)
            canvas.restore()
        }
        canvas.restore()

        // İbre sabit: baktığın yönü gösterir
        val needle = Path().apply {
            moveTo(cx, cy - r * 0.55f)
            lineTo(cx - r * 0.10f, cy + r * 0.25f)
            lineTo(cx + r * 0.10f, cy + r * 0.25f)
            close()
        }
        canvas.drawPath(needle, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(41, 182, 246) })
    }
}
