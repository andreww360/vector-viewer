package com.anddesign.vectorviewer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.anddesign.vectorviewer.util.FileUtils
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Exporter {

    private const val MAX_SIDE = 8000f
    private const val MAX_PIXELS = 48_000_000f

    private fun exportsDir(context: Context) = File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * PDF a escala real (1 pt = 1/72 in), listo para imprimir o enviar.
     *  - PDF e Illustrator: se copia el PDF original (todas las páginas, 100 % vectorial).
     *  - SVG, DXF, PLT: se dibujan como vector.
     *  - EPS / CDR (vista previa): se incrusta la imagen a 300 dpi en una página del tamaño real.
     */
    fun exportPdf(context: Context, d: LoadedDocument, page: Int): File {
        val out = File(exportsDir(context), FileUtils.safeBaseName(d.name) + ".pdf")
        val doc = d.document
        if (doc is PdfVectorDocument) {
            d.localFile.copyTo(out, overwrite = true)
            return out
        }
        val pdf = PdfDocument()
        try {
            val pages = if (doc.isPreviewOnly) listOf(page) else (0 until doc.pageCount).toList()
            pages.forEachIndexed { index, p ->
                val s = doc.pageSize(p)
                val info = PdfDocument.PageInfo.Builder(
                    max(1, s.width.roundToInt()), max(1, s.height.roundToInt()), index + 1
                ).create()
                val pg = pdf.startPage(info)
                if (!doc.drawVector(p, pg.canvas)) {
                    val scale = min(300f / 72f, 4000f / max(s.width, s.height))
                    val bmp = Bitmap.createBitmap(
                        max(1, (s.width * scale).roundToInt()),
                        max(1, (s.height * scale).roundToInt()),
                        Bitmap.Config.ARGB_8888,
                    )
                    doc.render(p, bmp, Matrix().apply { setScale(scale, scale) })
                    pg.canvas.drawBitmap(bmp, null, RectF(0f, 0f, s.width, s.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                }
                pdf.finishPage(pg)
            }
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return out
    }

    /**
     * PNG de la página. [opaque] = fondo blanco (para enviar a Claude o a clientes);
     * si no, transparente (para diseñadores).
     */
    fun exportPng(context: Context, d: LoadedDocument, page: Int, dpi: Int, maxSide: Float = MAX_SIDE, opaque: Boolean = false, suffix: String = ""): File {
        val size = d.document.pageSize(page)
        val scale = minOf(
            dpi / 72f,
            maxSide / max(size.width, size.height),
            sqrt(MAX_PIXELS / (size.width * size.height)),
        )
        val bmp = Bitmap.createBitmap(
            max(1, (size.width * scale).roundToInt()),
            max(1, (size.height * scale).roundToInt()),
            Bitmap.Config.ARGB_8888,
        )
        if (opaque) bmp.eraseColor(Color.WHITE)
        d.document.render(page, bmp, Matrix().apply { setScale(scale, scale) })
        val pageSuffix = if (d.document.pageCount > 1) "_p${page + 1}" else ""
        val out = File(exportsDir(context), "${FileUtils.safeBaseName(d.name)}$pageSuffix$suffix.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }
}

/** Envía un PDF ya generado al servicio de impresión de Android (impresoras Wi-Fi, "Guardar como PDF", etc.). */
class PdfFilePrintAdapter(private val file: File, private val jobName: String) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            file.inputStream().use { input ->
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
