package com.anddesign.vectorviewer.render

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.SizeF
import java.io.File

/**
 * PDF y Adobe Illustrator (CS en adelante, guardado con "Crear archivo compatible con PDF").
 * Usa el PdfRenderer del sistema (PDFium por dentro): vectorial real, sin dependencias.
 * Cada mesa de trabajo de Illustrator aparece como una página.
 */
class PdfVectorDocument(
    file: File,
    override val engineNote: String? = "PDF (motor PDFium del sistema)",
) : VectorDocument {

    private val lock = Any()
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
    private val sizes: List<SizeF> = List(renderer.pageCount) { i ->
        renderer.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) }
    }
    private var closed = false

    override val pageCount: Int get() = sizes.size
    override val hasRealScale = true
    override val isPreviewOnly = false

    override fun pageSize(page: Int): SizeF = sizes[page]

    override fun render(page: Int, bitmap: Bitmap, matrix: Matrix) {
        synchronized(lock) {
            if (closed) return
            renderer.openPage(page).use {
                it.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}
