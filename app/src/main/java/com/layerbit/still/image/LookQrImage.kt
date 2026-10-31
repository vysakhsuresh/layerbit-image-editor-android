package com.layerbit.still.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import com.layerbit.still.engine.Geometry2D
import com.layerbit.still.engine.LookCode
import com.layerbit.still.engine.Qr
import com.layerbit.still.engine.QrScan
import com.layerbit.still.engine.Recipe
import kotlin.math.max

/** A look's QR code as a picture, and a look read back from a picture of one. */
object LookQrImage {

    /** Black modules on white with the standard four-module quiet zone, and the look's name underneath. */
    fun render(code: String, title: String): Bitmap {
        val m = Qr.encode(code.toByteArray(Charsets.US_ASCII))
        val module = max(8, 960 / (m.size + 8))
        val quiet = 4 * module
        val side = m.size * module + 2 * quiet
        val caption = if (title.isBlank()) 0 else 5 * module
        val b = Bitmap.createBitmap(side, side + caption, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false; style = Paint.Style.FILL }
        for (y in 0 until m.size) for (x in 0 until m.size) if (m[x, y]) {
            val l = (quiet + x * module).toFloat(); val t = (quiet + y * module).toFloat()
            c.drawRect(l, t, l + module, t + module, ink)
        }
        if (caption > 0) {
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = module * 2.4f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            val shown = TextUtils.ellipsize(title, text, side - 2f * quiet, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, side / 2f, side + caption * 0.5f, text)
        }
        return b
    }

    /**
     * The look in the picture at [uri], or null when there is none. The picture is read once,
     * read-only, into memory, decoded at no more than four megapixels, and never kept.
     * @throws IllegalArgumentException when a code is found but is not a look this app trusts.
     */
    fun readLook(resolver: ContentResolver, uri: Uri, check: () -> Unit = {}): Pair<String, Recipe>? {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_BYTES) { "too large" }
            }
            out.toByteArray()
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = Geometry2D.sampleSize(bounds.outWidth, bounds.outHeight, 4_000_000L)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        check()
        val payload = QrScan.read(px, w, h, check) ?: return null
        return LookCode.decode(String(payload, Charsets.US_ASCII))
    }

    private const val MAX_BYTES = 60 * 1024 * 1024
}
