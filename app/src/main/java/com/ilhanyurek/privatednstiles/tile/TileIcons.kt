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

    /** What to show on a tile for a given live DNS state. */
    data class Display(val name: String, val subtitle: String, val label: String, val active: Boolean)

    /** Icon showing a short (1–4 char) label as the tile glyph. */
    fun forLabel(label: String): Icon = labelIcon(label)

    /**
     * Describe the live system DNS state ([mode] + [hostname]) for display.
     * If it corresponds to a saved entry ([match]) that entry's name is used,
     * otherwise the real state is shown, so a tile never shows a stale entry.
     */
    fun describe(mode: DnsMode, hostname: String, match: DnsConfig?): Display =
        when {
            match != null -> Display(match.name, match.subtitle, shortLabel(match), mode != DnsMode.OFF)
            mode == DnsMode.OFF -> Display("Off", "Off", "OFF", false)
            mode == DnsMode.AUTOMATIC -> Display("Automatic", "Automatic", "AUTO", true)
            else -> Display(hostname, hostname, shortFromHostname(hostname), true)
        }

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

    /** Fallback short label derived from a raw hostname not in the saved list. */
    private fun shortFromHostname(hostname: String): String {
        val labels = hostname.split('.').filter { it.isNotEmpty() }
        // Prefer the most descriptive label (usually the provider name, not "dns").
        val pick = labels.firstOrNull { !it.equals("dns", true) && it.length > 2 }
            ?: labels.firstOrNull() ?: hostname
        return pick.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "DNS" }
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
