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

/**
 * Uno o varios SVG (una página cada uno), dibujados con AndroidSVG.
 * Se usa para archivos SVG/SVGZ y para lo que el motor CorelDRAW convierte a SVG.
 * Las unidades SVG (px a 96 dpi, mm, cm, in…) se convierten a pt.
 */
class SvgVectorDocument private constructor(
    private val pages: List<Page>,
    override val engineNote: String,
) : VectorDocument {

    private class Page(val svg: SVG, val widthPx: Float, val heightPx: Float)

    override val pageCount: Int get() = pages.size
    override val hasRealScale = true
    override val isPreviewOnly = false

    override fun pageSize(page: Int): SizeF = pages[page].let { SizeF(it.widthPx * PX_TO_PT, it.heightPx * PX_TO_PT) }

    override fun render(page: Int, bitmap: Bitmap, matrix: Matrix) {
        val canvas = Canvas(bitmap)
        canvas.concat(matrix)
        drawVector(page, canvas)
    }

    override fun drawVector(page: Int, canvas: Canvas): Boolean {
        val p = pages[page]
        canvas.save()
        canvas.scale(PX_TO_PT, PX_TO_PT)
        synchronized(p.svg) {
            p.svg.renderToCanvas(canvas, RenderOptions().viewPort(0f, 0f, p.widthPx, p.heightPx))
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
            return SvgVectorDocument(listOf(page(svg)), "SVG (AndroidSVG)")
        }

        /** Varias páginas a partir de texto SVG (p. ej. la salida del motor CorelDRAW). */
        fun fromStrings(svgPages: List<String>, note: String): SvgVectorDocument {
            require(svgPages.isNotEmpty()) { "Sin páginas" }
            return SvgVectorDocument(svgPages.map { page(SVG.getFromString(it)) }, note)
        }

        private fun page(svg: SVG): Page {
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
            return Page(svg, w, h)
        }
    }
}
