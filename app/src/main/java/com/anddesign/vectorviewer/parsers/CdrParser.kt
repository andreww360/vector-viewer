package com.anddesign.vectorviewer.parsers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.anddesign.vectorviewer.render.CdrNative
import com.anddesign.vectorviewer.render.PreviewImageDocument
import com.anddesign.vectorviewer.render.SvgVectorDocument
import com.anddesign.vectorviewer.render.VectorDocument
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * CorelDRAW.
 *  - X4 en adelante: contenedor ZIP con metadata/thumbnails/thumbnail.(png|bmp).
 *  - Hasta X3: RIFF "CDRx" con un bloque DISP (DIB sin encabezado de archivo).
 *
 * Fase 2 (Pro): render vectorial real con libcdr (MPL 2.0, apta para uso comercial) vía NDK.
 */
object CdrParser {

    fun parse(file: File): VectorDocument {
        // 1) Motor vectorial completo (libcdr), si viene en el APK
        if (CdrNative.available) {
            val pages = runCatching { CdrNative.convertToSvg(file.absolutePath) }.getOrNull()
            if (!pages.isNullOrEmpty()) {
                val svgs = pages.map { CdrNative.normalizeSvg(String(it, Charsets.UTF_8)) }
                val doc = runCatching {
                    SvgVectorDocument.fromStrings(svgs, "CorelDRAW · vector completo (libcdr) · ${svgs.size} página(s)")
                }.getOrNull()
                if (doc != null) return doc
            }
        }

        // 2) Respaldo: miniatura incrustada en el archivo
        val head = ByteArray(12)
        val n = file.inputStream().use { it.read(head) }
        val isZip = n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

        val (bmp, version) = if (isZip) fromZip(file) else fromRiff(file.readBytes())

        val wPx = bmp?.width ?: 800
        val hPx = bmp?.height ?: 600
        val v = version?.let { " v$it" } ?: ""
        val note = if (bmp != null) {
            "CorelDRAW$v · miniatura incrustada"
        } else {
            "CorelDRAW$v sin miniatura · requiere motor libcdr"
        }
        return PreviewImageDocument(bmp, wPx * 0.75f, hPx * 0.75f, hasRealScale = false, engineNote = note)
    }

    private fun versionFromChar(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        in 'a'..'z' -> c - 'a' + 10
        else -> null
    }

    private fun fromZip(file: File): Pair<Bitmap?, Int?> = ZipFile(file).use { zip ->
        val entries = zip.entries().toList()
        val thumb = entries.firstOrNull { e ->
            val name = e.name.lowercase()
            "thumbnail" in name && (name.endsWith(".png") || name.endsWith(".bmp") || name.endsWith(".jpg"))
        }
        val bmp = thumb?.let { e -> zip.getInputStream(e).use { BitmapFactory.decodeStream(it) } }

        val riff = entries.firstOrNull { e ->
            val name = e.name.lowercase()
            name.endsWith("riffdata.cdr") || name.endsWith("root.dat")
        }
        val ver = riff?.let { e ->
            zip.getInputStream(e).use { s ->
                val h = ByteArray(12)
                if (s.read(h) == 12 && String(h, 8, 3, Charsets.ISO_8859_1).equals("CDR", true)) {
                    versionFromChar((h[11].toInt() and 0xFF).toChar())
                } else {
                    null
                }
            }
        }
        bmp to ver
    }

    private fun fromRiff(bytes: ByteArray): Pair<Bitmap?, Int?> {
        val ver = if (bytes.size > 12) versionFromChar((bytes[11].toInt() and 0xFF).toChar()) else null
        val idx = bytes.indexOf("DISP".toByteArray(Charsets.ISO_8859_1))
        if (idx < 0 || idx + 8 > bytes.size) return null to ver
        val size = bytes.le32(idx + 4)
        val start = idx + 8
        val end = min(bytes.size, start + maxOf(size, 0))
        for (skip in intArrayOf(0, 4)) {
            dibToBitmap(bytes, start + skip, end)?.let { return it to ver }
        }
        return null to ver
    }

    /** Agrega el encabezado BITMAPFILEHEADER a un DIB para que BitmapFactory lo lea. */
    private fun dibToBitmap(b: ByteArray, s: Int, e: Int): Bitmap? {
        if (e - s < 40) return null
        val headerSize = b.le32(s)
        if (headerSize != 40 && headerSize != 108 && headerSize != 124) return null
        val bitCount = b.le16(s + 14)
        val clrUsed = b.le32(s + 32)
        val palette = if (bitCount <= 8) (if (clrUsed > 0) clrUsed else 1 shl bitCount) * 4 else 0
        val dibLen = e - s
        val out = ByteArray(14 + dibLen)
        out[0] = 'B'.code.toByte()
        out[1] = 'M'.code.toByte()
        out.putLe32(2, out.size)
        out.putLe32(10, 14 + headerSize + palette)
        System.arraycopy(b, s, out, 14, dibLen)
        return BitmapFactory.decodeByteArray(out, 0, out.size)
    }
}
