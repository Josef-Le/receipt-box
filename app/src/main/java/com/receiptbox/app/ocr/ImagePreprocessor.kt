package com.receiptbox.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max

/**
 * Lightweight on-device preprocess before OCR: grayscale + contrast + orientation candidates.
 * Tall receipts are often saved sideways without EXIF — try 0/270/90/180.
 */
object ImagePreprocessor {

    fun prepareForOcr(source: Bitmap, maxSide: Int = 2400): Bitmap {
        val scaled = scaleDown(source, maxSide)
        val gray = toGrayscale(scaled)
        if (scaled !== source && scaled !== gray) {
            // only recycle intermediate scale if we created it
            if (scaled !== source) scaled.recycle()
        }
        return boostContrast(gray, 1.35f)
    }

    fun orientationCandidates(source: Bitmap): List<Bitmap> {
        return listOf(0, 270, 90, 180).map { deg ->
            if (deg == 0) source else rotate(source, deg.toFloat())
        }
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val side = max(w, h)
        if (side <= maxSide) {
            // Upscale tiny images slightly for thermal text
            if (side < 1200) {
                val scale = 1400f / side
                return Bitmap.createScaledBitmap(src, max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), true)
            }
            return src
        }
        val scale = maxSide.toFloat() / side
        val nw = max(1, (w * scale).toInt())
        val nh = max(1, (h * scale).toInt())
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Contrast around mid-gray; amount > 1 increases contrast. */
    private fun boostContrast(src: Bitmap, amount: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val translate = (-0.5f * amount + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                amount, 0f, 0f, 0f, translate,
                0f, amount, 0f, 0f, translate,
                0f, 0f, amount, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        if (out !== src) src.recycle()
        return out
    }
}
