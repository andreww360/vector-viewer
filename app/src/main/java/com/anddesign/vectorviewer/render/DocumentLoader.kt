package com.anddesign.vectorviewer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import com.anddesign.vectorviewer.format.FormatDetector
import com.anddesign.vectorviewer.format.VectorFormat
import com.anddesign.vectorviewer.parsers.CdrParser
import com.anddesign.vectorviewer.parsers.DxfParser
import com.anddesign.vectorviewer.parsers.EpsParser
import com.anddesign.vectorviewer.parsers.HpglParser
import com.anddesign.vectorviewer.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LoadedDocument(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val format: VectorFormat,
    val document: VectorDocument,
    val localFile: File,
)

object DocumentLoader {

    suspend fun load(context: Context, uri: Uri, maxBytes: Long = Long.MAX_VALUE): LoadedDocument =
        withContext(Dispatchers.IO) {
            val meta = FileUtils.meta(context, uri)
            if (meta.size > maxBytes) throw DocumentException("Archivo demasiado grande para miniatura")

            val file = try {
                FileUtils.copyToCache(context, uri, meta.name)
            } catch (e: Exception) {
                throw DocumentException("No se pudo leer el archivo. Vuelve a abrirlo desde su ubicación.", e)
            }

            val header = file.inputStream().use { s ->
                val buf = ByteArray(4096)
                val n = s.read(buf).coerceAtLeast(0)
                buf.copyOf(n)
            }
            val format = FormatDetector.detect(header, meta.name)

            val doc = try {
                open(file, header, format)
            } catch (e: DocumentException) {
                throw e
            } catch (e: SecurityException) {
                throw DocumentException("El archivo está protegido con contraseña.", e)
            } catch (e: OutOfMemoryError) {
                throw DocumentException("El archivo es demasiado grande para este dispositivo.")
            } catch (e: Exception) {
                throw DocumentException(
                    "No se pudo interpretar el archivo ${format.badge}: ${e.message ?: e.javaClass.simpleName}", e
                )
            }

            LoadedDocument(
                uri = uri,
                name = meta.name,
                sizeBytes = if (meta.size >= 0) meta.size else file.length(),
                format = format,
                document = doc,
                localFile = file,
            )
        }

    private fun open(file: File, header: ByteArray, format: VectorFormat): VectorDocument = when (format) {
        VectorFormat.PDF -> PdfVectorDocument(file)
        VectorFormat.AI ->
            if (String(header, 0, minOf(4, header.size), Charsets.ISO_8859_1) == "%PDF") {
                PdfVectorDocument(file, "Illustrator (contenido compatible con PDF)")
            } else {
                EpsParser.parse(file, legacyAi = true)
            }
        VectorFormat.EPS -> EpsParser.parse(file)
        VectorFormat.SVG -> SvgVectorDocument.open(file)
        VectorFormat.CDR -> CdrParser.parse(file)
        VectorFormat.DXF -> DxfParser.parse(file.readText())
        VectorFormat.PLT -> HpglParser.parse(file.readText(Charsets.ISO_8859_1))
        VectorFormat.UNKNOWN -> throw DocumentException("Formato no compatible")
    }
}

object Rendering {
    /** Renderiza la página completa con su lado mayor = [maxSide] px. */
    fun renderPage(doc: VectorDocument, page: Int, maxSide: Int): Bitmap {
        val s = doc.pageSize(page)
        val scale = maxSide / max(max(s.width, s.height), 1f)
        val w = max(1, (s.width * scale).roundToInt())
        val h = max(1, (s.height * scale).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        doc.render(page, bmp, Matrix().apply { setScale(scale, scale) })
        return bmp
    }
}
