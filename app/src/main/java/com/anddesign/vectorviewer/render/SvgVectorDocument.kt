package com.anddesign.vectorviewer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.SizeF
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** SVG y SVGZ con AndroidSVG. Las unidades SVG (px a 96 dpi, mm, cm…) se convierten a pt. */
class SvgVectorDocument private constructor(
    private val svg: SVG,
    private val widthPx: Float,
    private val heightPx: Float,
) : VectorDocument {

    override val pageCount = 1
    override val hasRealScale = true
    override val isPreviewOnly = false
    override val engineNote = "SVG (AndroidSVG)"

    override fun pageSize(page: Int) = SizeF(widthPx * PX_TO_PT, heightPx * PX_TO_PT)

    override fun render(page: Int, bitmap: Bitmap, matrix: Matrix) {
        val canvas = Canvas(bitmap)
        canvas.concat(matrix)
        drawVector(page, canvas)
    }

    override fun drawVector(page: Int, canvas: Canvas): Boolean {
        canvas.save()
        canvas.scale(PX_TO_PT, PX_TO_PT)
        synchronized(svg) {
            svg.renderToCanvas(canvas, RenderOptions().viewPort(0f, 0f, widthPx, heightPx))
        }
        canvas.restore()
        return true
    }

    companion object {
        private const val PX_TO_PT = 0.75f // 96 px = 72 pt

        fun open(file: File): SvgVectorDocument {
            val gz = file.inputStream().use { it.read() == 0x1F && it.read() == 0x8B }
            val raw: InputStream = BufferedInputStream(file.inputStream())
            val stream: InputStream = if (gz) GZIPInputStream(raw) else raw
            val svg = stream.use { SVG.getFromInputStream(it) }

            var w = svg.documentWidth
            var h = svg.documentHeight
            val vb = svg.documentViewBox
            if (w <= 0f || h <= 0f) {
                if (vb != null && vb.width() > 0f && vb.height() > 0f) {
                    w = vb.width(); h = vb.height()
                } else {
                    w = 1000f; h = 1000f
                }
            }
            svg.setDocumentWidth(w)
            svg.setDocumentHeight(h)
            return SvgVectorDocument(svg, w, h)
        }
    }
}
