package com.cleanyourtv.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import org.json.JSONObject

/**
 * Icons that fit their shape, designed from the app's own artwork (the same method as engine.js fitIcon on the TV):
 *  - an adaptive icon (Android 8+): its layers drawn into the shape, as Android draws app icons;
 *  - anything else (a TV banner, an old square icon): its background colour read from its edges, the logo cut out
 *    of it, the whole shape filled with that colour and the logo set as large as fits inside the shape.
 * With an icon theme: the theme's background around, the app's colour on the theme's plate, the logo inside, grey
 * if asked. Done once per app, size and theme (cached).
 */
object IconFit {
    private val cache = LruCache<String, Bitmap>(200)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun path(kind: String, r: RectF): Path = Path().apply {
        when (kind) {
            "round" -> addOval(r, Path.Direction.CW)
            "squircle" -> { val c = Math.min(r.width(), r.height()) * .24f; addRoundRect(r, c, c, Path.Direction.CW) }
            "keep" -> { val c = Math.min(r.width(), r.height()) * .14f; addRoundRect(r, c, c, Path.Direction.CW) }
            else -> addRect(r, Path.Direction.CW)
        }
    }

    /** [shape]: "round", "tall", "square" or "wide" (the tile); [theme]: the icon theme's style, or null. */
    fun bitmap(key: String, src: Drawable, w: Int, h: Int, shape: String, theme: JSONObject?, fallback: Int): Bitmap {
        val ck = "$key|$w|$h|$shape|$theme"
        cache.get(ck)?.let { return it }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val adaptive = src is AdaptiveIconDrawable
        // the picture, its own background colour and where its logo is
        var logo: Bitmap? = null; var crop = Rect(); var brand = fallback
        if (adaptive) {
            (src as AdaptiveIconDrawable).background?.let { bgd -> edgeColor(raster(bgd, 96))?.let { brand = it } }
        } else {
            val r = raster(src, 256)
            val bg = edgeColor(r)
            if (bg != null) brand = bg
            crop = trim(r, bg); logo = r
        }
        val outer = when (val b = theme?.optString("background", "brand")) { null, "brand" -> brand; "none" -> Color.TRANSPARENT; else -> MainActivity.parseColor(b, brand) }
        paint.colorFilter = null
        paint.color = outer; cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        var plateKind = if (shape == "round") "round" else "rect"
        var box = RectF(0f, 0f, w.toFloat(), h.toFloat())
        var pad = if (shape == "round") .18f else .14f
        if (theme != null) {   // the theme's plate, in the app's colour
            val side = Math.min(w, h) * if (shape == "round") .78f else .82f
            box = RectF((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
            plateKind = theme.optString("shape", "squircle"); pad = theme.optDouble("padding", .18).toFloat()
            paint.color = brand; cv.drawPath(path(plateKind, box), paint)
        }
        cv.save(); cv.clipPath(path(plateKind, box))
        if (adaptive) {
            // 108 units of layer, 72 of them visible: the visible part fills the plate
            val a = src as AdaptiveIconDrawable; val s = Math.max(box.width(), box.height()) * 1.5f
            val l = (box.centerX() - s / 2).toInt(); val t = (box.centerY() - s / 2).toInt()
            a.background?.let { it.setBounds(l, t, (l + s).toInt(), (t + s).toInt()); it.draw(cv) }
            a.foreground?.let { it.setBounds(l, t, (l + s).toInt(), (t + s).toInt()); it.draw(cv) }
        } else if (logo != null) {
            val lw = crop.width().toFloat(); val lh = crop.height().toFloat()
            val sc = if (plateKind == "round") (Math.min(box.width(), box.height()) / 2 * (1 - pad)) / Math.sqrt((lw * lw / 4 + lh * lh / 4).toDouble()).toFloat()
                     else Math.min(box.width() * (1 - 2 * pad) / lw, box.height() * (1 - 2 * pad) / lh)
            val dw = lw * sc; val dh = lh * sc
            cv.drawBitmap(logo, crop, RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2), paint)
        }
        cv.restore()
        val result = if (theme?.optBoolean("monochrome") == true) {
            val g = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(g).drawBitmap(out, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
            g
        } else out
        cache.put(ck, result)
        return result
    }

    private fun raster(d: Drawable, max: Int): Bitmap {
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else max; val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else max
        val k = Math.min(1f, max.toFloat() / Math.max(iw, ih))
        val w = Math.max(1, (iw * k).toInt()); val h = Math.max(1, (ih * k).toInt())
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val old = d.copyBounds(); d.setBounds(0, 0, w, h); d.draw(Canvas(b)); d.bounds = old
        return b
    }

    /** The picture's background: the middle colour of its edges, or null when they are see-through. */
    private fun edgeColor(b: Bitmap): Int? {
        val w = b.width; val h = b.height; val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>(); var clear = 0; var n = 0
        fun at(x: Int, y: Int) { val p = b.getPixel(x, y); n++; if (Color.alpha(p) < 40) { clear++; return }; rs.add(Color.red(p)); gs.add(Color.green(p)); bs.add(Color.blue(p)) }
        for (x in 0 until w) { at(x, 0); at(x, h - 1) }
        for (y in 0 until h) { at(0, y); at(w - 1, y) }
        if (clear >= n / 2 || rs.isEmpty()) return null
        rs.sort(); gs.sort(); bs.sort()
        return Color.rgb(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
    }

    /** Where the logo is: what differs from the background (or isn't see-through). */
    private fun trim(b: Bitmap, bg: Int?): Rect {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            if (Color.alpha(p) < 40) continue
            if (bg != null && Math.abs(Color.red(p) - Color.red(bg)) + Math.abs(Color.green(p) - Color.green(bg)) + Math.abs(Color.blue(p) - Color.blue(bg)) < 90) continue
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return if (x1 < 0) Rect(0, 0, w, h) else Rect(x0, y0, x1 + 1, y1 + 1)
    }
}
