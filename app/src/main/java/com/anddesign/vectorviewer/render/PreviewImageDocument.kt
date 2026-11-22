package com.anddesign.vectorviewer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.SizeF

/**
 * Documento basado en una vista previa incrustada (EPS con TIFF/EPSI, CorelDRAW con miniatura).
 * Si no hay vista previa dibuja un marcador con el tamaño de la mesa de trabajo.
 */
class PreviewImageDocument(
    private val image: Bitmap?,
    private val widthPt: Float,
    private val heightPt: Float,
    override val hasRealScale: Boolean,
    override val engineNote: String?,
) : VectorDocument {

    override val pageCount = 1
    override val isPreviewOnly = true

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun pageSize(page: Int) = SizeF(widthPt, heightPt)

    override fun render(page: Int, bitmap: Bitmap, matrix: Matrix) {
        val canvas = Canvas(bitmap)
        canvas.concat(matrix)
        val dst = RectF(0f, 0f, widthPt, heightPt)
        if (image != null) {
            canvas.drawBitmap(image, null, dst, paint)
        } else {
            drawPlaceholder(canvas, dst)
        }
    }

    private fun drawPlaceholder(canvas: Canvas, r: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(236, 236, 236)
        canvas.drawRect(r, p)
        p.color = Color.rgb(200, 200, 200)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0f
        canvas.drawLine(r.left, r.top, r.right, r.bottom, p)
        canvas.drawLine(r.right, r.top, r.left, r.bottom, p)
        p.style = Paint.Style.FILL
        p.color = Color.rgb(90, 90, 90)
        p.textAlign = Paint.Align.CENTER
        p.textSize = r.width() / 14f
        canvas.drawText("Sin vista previa incrustada", r.centerX(), r.centerY(), p)
    }
}
