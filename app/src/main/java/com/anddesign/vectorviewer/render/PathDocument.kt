package com.anddesign.vectorviewer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.SizeF

/**
 * Documento de trazos (DXF, HPGL/PLT). Se dibuja como "línea de corte" magenta
 * de 1 px, visible sobre fondo blanco, cuadriculado u oscuro.
 */
class PathDocument private constructor(
    private val path: Path,
    private val widthPt: Float,
    private val heightPt: Float,
    override val engineNote: String?,
) : VectorDocument {

    override val pageCount = 1
    override val hasRealScale = true
    override val isPreviewOnly = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f // hairline: siempre 1 px, sin importar el zoom
        color = Color.rgb(233, 30, 99)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    override fun pageSize(page: Int) = SizeF(widthPt, heightPt)

    override fun render(page: Int, bitmap: Bitmap, matrix: Matrix) {
        val canvas = Canvas(bitmap)
        canvas.concat(matrix)
        canvas.drawPath(path, paint)
    }

    /** En PDF/impresión usamos un trazo fino real (0,5 pt) en lugar de 1 px. */
    private val printPaint = Paint(paint).apply { strokeWidth = 0.5f }

    override fun drawVector(page: Int, canvas: Canvas): Boolean {
        canvas.drawPath(path, printPaint)
        return true
    }

    companion object {
        private const val MARGIN_PT = 8f

        /**
         * @param raw trazado en unidades del archivo (Y hacia arriba si [flipY]).
         * @param unitToPt factor de conversión de la unidad del archivo a puntos.
         */
        fun fromRaw(raw: Path, unitToPt: Float, flipY: Boolean, note: String?): PathDocument {
            val m = Matrix()
            m.setScale(unitToPt, if (flipY) -unitToPt else unitToPt)
            raw.transform(m)
            val b = RectF()
            raw.computeBounds(b, true)
            if (b.width() <= 0f && b.height() <= 0f) b.set(0f, 0f, 100f, 100f)
            raw.offset(-b.left + MARGIN_PT, -b.top + MARGIN_PT)
            return PathDocument(raw, b.width() + 2 * MARGIN_PT, b.height() + 2 * MARGIN_PT, note)
        }
    }
}
