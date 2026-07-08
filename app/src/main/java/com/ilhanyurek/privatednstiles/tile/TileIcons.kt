package com.ilhanyurek.privatednstiles.tile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsMode

/**
 * Builds Quick Settings tile icons that show a short label of the active DNS, so
 * the current choice is recognisable at a glance from the icon alone.
 */
object TileIcons {

    /** Icon for the given config: a 1–4 char label drawn as a tile glyph. */
    fun forConfig(config: DnsConfig): Icon = labelIcon(shortLabel(config))

    /** Short, uppercase label used on the tile (e.g. OFF, AUTO, CL, GO). */
    fun shortLabel(config: DnsConfig): String = when (config.mode) {
        DnsMode.OFF -> "OFF"
        DnsMode.AUTOMATIC -> "AUTO"
        DnsMode.HOSTNAME -> {
            val name = config.name.trim()
            val tokens = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            val s = if (tokens.size >= 2) tokens[0].take(1) + tokens[1].take(1)
            else name.filter { it.isLetterOrDigit() }.take(2)
            s.uppercase().ifEmpty { "DNS" }
        }
    }

    private fun labelIcon(text: String): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        // Shrink the text until it fits the bitmap in both width and height.
        var ts = size.toFloat()
        paint.textSize = ts
        val maxW = size * 0.86f
        val w = paint.measureText(text)
        if (w > maxW) {
            ts *= maxW / w
            paint.textSize = ts
        }
        val fm = paint.fontMetrics
        val textH = fm.descent - fm.ascent
        val maxH = size * 0.9f
        if (textH > maxH) {
            ts *= maxH / textH
            paint.textSize = ts
        }

        val fm2 = paint.fontMetrics
        val y = size / 2f - (fm2.ascent + fm2.descent) / 2f
        canvas.drawText(text, size / 2f, y, paint)
        return Icon.createWithBitmap(bmp)
    }
}
