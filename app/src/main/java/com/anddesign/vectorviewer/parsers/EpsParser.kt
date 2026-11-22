package com.anddesign.vectorviewer.parsers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import com.anddesign.vectorviewer.render.PreviewImageDocument
import com.anddesign.vectorviewer.render.VectorDocument
import java.io.File
import kotlin.math.min

/**
 * EPS e Illustrator antiguo (≤ 8, basado en PostScript).
 *
 * Fase 1 (esta): lee el tamaño real (%%HiResBoundingBox / %%BoundingBox) y muestra la vista
 * previa incrustada — TIFF del encabezado DOS (lo que guarda Illustrator), EPSI o miniatura
 * de Photoshop.
 *
 * Fase 2 (Pro): render vectorial completo con un intérprete PostScript (Ghostscript vía NDK).
 * Ver README → "Licencias": Ghostscript es AGPL y requiere licencia comercial de Artifex
 * para venderse en una app cerrada.
 */
object EpsParser {

    private const val SCAN_LIMIT = 8 * 1024 * 1024

    fun parse(file: File, legacyAi: Boolean = false): VectorDocument {
        val bytes = file.readBytes()
        var ps = bytes
        var tiff: ByteArray? = null

        if (bytes.size > 30 && bytes.u8(0) == 0xC5 && bytes.u8(1) == 0xD0 && bytes.u8(2) == 0xD3 && bytes.u8(3) == 0xC6) {
            val psOff = bytes.le32(4)
            val psLen = bytes.le32(8)
            val tOff = bytes.le32(20)
            val tLen = bytes.le32(24)
            if (psOff in 0 until bytes.size && psLen > 0) {
                ps = bytes.copyOfRange(psOff, min(bytes.size, psOff + psLen))
            }
            if (tOff > 0 && tLen > 0 && tOff + tLen <= bytes.size) {
                tiff = bytes.copyOfRange(tOff, tOff + tLen)
            }
        }

        val text = String(ps, 0, min(ps.size, SCAN_LIMIT), Charsets.ISO_8859_1)
        val bbox = boundingBox(text)

        var source = ""
        val preview: Bitmap? =
            tiff?.let { TiffDecoder.decode(it) }?.also { source = "TIFF" }
                ?: decodeEpsi(text)?.also { source = "EPSI" }
                ?: decodePhotoshopThumb(text)?.also { source = "Photoshop" }

        val w: Float
        val h: Float
        when {
            bbox != null -> { w = bbox.width(); h = bbox.height() }
            preview != null -> { w = preview.width * 0.75f; h = preview.height * 0.75f }
            else -> { w = 595f; h = 842f }
        }

        val kind = if (legacyAi) "Illustrator antiguo (PostScript)" else "EPS"
        val note = if (preview != null) {
            "$kind · vista previa $source incrustada"
        } else {
            "$kind sin vista previa incrustada · requiere motor PostScript"
        }
        return PreviewImageDocument(preview, w, h, hasRealScale = bbox != null, engineNote = note)
    }

    private val NUM = "(-?[0-9]*\\.?[0-9]+)"
    private val HIRES = Regex("%%HiResBoundingBox:\\s*$NUM\\s+$NUM\\s+$NUM\\s+$NUM")
    private val BBOX = Regex("%%BoundingBox:\\s*$NUM\\s+$NUM\\s+$NUM\\s+$NUM")

    private fun boundingBox(text: String): RectF? {
        val m = HIRES.find(text) ?: BBOX.find(text) ?: return null
        val v = m.groupValues.drop(1).map { it.toFloatOrNull() ?: return null }
        val r = RectF(v[0], v[1], v[2], v[3])
        return if (r.width() > 0f && r.height() > 0f) r else null
    }

    /** EPSI: "%%BeginPreview: w h depth lines" + hexadecimal, 1 = tinta (negro). */
    private fun decodeEpsi(text: String): Bitmap? {
        val m = Regex("%%BeginPreview:\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)").find(text) ?: return null
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        val depth = m.groupValues[3].toInt()
        if (w <= 0 || h <= 0 || depth !in setOf(1, 2, 4, 8)) return null
        val start = m.range.last + 1
        val end = text.indexOf("%%EndPreview", start).takeIf { it > 0 } ?: return null
        // Saltar el resto de la línea de encabezado (el 4º número = cantidad de líneas)
        val dataStart = text.indexOf('\n', start).takeIf { it in start until end } ?: start
        val data = hexDecode(text, dataStart, end)
        val rowBytes = (w * depth + 7) / 8
        if (data.size < rowBytes * h) return null

        val maxV = (1 shl depth) - 1
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * rowBytes
            for (x in 0 until w) {
                val bit = x * depth
                val byte = data.u8(row + bit / 8)
                val v = (byte shr (8 - depth - bit % 8)) and maxV
                val g = 255 - v * 255 / maxV
                px[y * w + x] = Color.rgb(g, g, g)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Recursos 8BIM de Photoshop: el 0x040C (o 0x0409) contiene un JPEG de miniatura. */
    private fun decodePhotoshopThumb(text: String): Bitmap? {
        val m = Regex("%BeginPhotoshop:\\s*\\d+").find(text) ?: return null
        val end = text.indexOf("%EndPhotoshop", m.range.last).takeIf { it > 0 } ?: return null
        val b = hexDecode(text, m.range.last + 1, end)
        var i = 0
        while (i + 12 <= b.size) {
            if (b[i] != '8'.code.toByte() || b[i + 1] != 'B'.code.toByte() ||
                b[i + 2] != 'I'.code.toByte() || b[i + 3] != 'M'.code.toByte()
            ) return null
            val id = b.be16(i + 4)
            val nameLen = b.u8(i + 6)
            var namePadded = 1 + nameLen
            if (namePadded % 2 != 0) namePadded++
            val sizeOff = i + 6 + namePadded
            if (sizeOff + 4 > b.size) return null
            val size = b.be32(sizeOff)
            val dataOff = sizeOff + 4
            if (size < 0 || dataOff + size > b.size) return null
            if ((id == 0x040C || id == 0x0409) && size > 28) {
                BitmapFactory.decodeByteArray(b, dataOff + 28, size - 28)?.let { return it }
            }
            i = dataOff + size + (size % 2)
        }
        return null
    }
}
