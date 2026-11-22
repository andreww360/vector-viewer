package com.anddesign.vectorviewer.parsers

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * Decodificador TIFF mínimo para la vista previa de los EPS (Android no lee TIFF).
 * Soporta: sin compresión y PackBits; gris 1/2/4/8 bits, paleta y RGB(A) de 8 bits.
 */
object TiffDecoder {

    fun decode(b: ByteArray): Bitmap? = try {
        decodeInternal(b)
    } catch (e: Exception) {
        null
    }

    private fun decodeInternal(b: ByteArray): Bitmap? {
        if (b.size < 8) return null
        val le = b[0] == 'I'.code.toByte()
        fun u16(o: Int) = if (le) b.le16(o) else b.be16(o)
        fun u32(o: Int) = if (le) b.le32(o) else b.be32(o)
        if (u16(2) != 42) return null

        val ifd = u32(4)
        val n = u16(ifd)
        val tags = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            val tag = u16(e)
            val type = u16(e + 2)
            val count = u32(e + 4)
            val sz = when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9 -> 4
                else -> continue
            }
            if (count <= 0 || count > 1_000_000) continue
            val base = if (sz * count <= 4) e + 8 else u32(e + 8)
            tags[tag] = IntArray(count) { k ->
                when (sz) {
                    1 -> b.u8(base + k)
                    2 -> u16(base + 2 * k)
                    else -> u32(base + 4 * k)
                }
            }
        }

        val w = tags[256]?.get(0) ?: return null
        val h = tags[257]?.get(0) ?: return null
        val bps = tags[258]?.get(0) ?: 1
        val compression = tags[259]?.get(0) ?: 1
        val photometric = tags[262]?.get(0) ?: 1
        val spp = tags[277]?.get(0) ?: 1
        val offsets = tags[273] ?: return null
        val counts = tags[279] ?: return null
        if (compression != 1 && compression != 32773) return null
        if (w <= 0 || h <= 0 || w.toLong() * h > 40_000_000L) return null

        val raw = ByteArrayOutputStream()
        for (i in offsets.indices) {
            val off = offsets[i]
            val len = counts.getOrElse(i) { 0 }
            if (off < 0 || len < 0 || off + len > b.size) return null
            if (compression == 1) raw.write(b, off, len) else unpackBits(b, off, len, raw)
        }
        val data = raw.toByteArray()
        val rowBytes = (w * spp * bps + 7) / 8
        if (data.size < rowBytes * h) return null

        val cmap = tags[320]
        val maxV = (1 shl bps) - 1
        fun sample(row: Int, index: Int): Int {
            val bit = index * bps
            val byte = data.u8(row + bit / 8)
            return if (bps == 8) byte else (byte shr (8 - bps - bit % 8)) and maxV
        }

        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * rowBytes
            for (x in 0 until w) {
                px[y * w + x] = when {
                    photometric == 2 && bps == 8 && spp >= 3 -> {
                        val o = row + x * spp
                        val a = if (spp >= 4) data.u8(o + 3) else 255
                        Color.argb(a, data.u8(o), data.u8(o + 1), data.u8(o + 2))
                    }
                    photometric == 3 && cmap != null -> {
                        val idx = sample(row, x)
                        val nC = 1 shl bps
                        Color.rgb(cmap[idx] shr 8, cmap[nC + idx] shr 8, cmap[2 * nC + idx] shr 8)
                    }
                    (photometric == 0 || photometric == 1) && spp == 1 -> {
                        var g = sample(row, x) * 255 / maxV
                        if (photometric == 0) g = 255 - g
                        Color.rgb(g, g, g)
                    }
                    else -> return null
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun unpackBits(src: ByteArray, off: Int, len: Int, out: ByteArrayOutputStream) {
        var i = off
        val end = off + len
        while (i < end) {
            val n = src[i].toInt()
            i++
            if (n >= 0) {
                val c = minOf(n + 1, end - i)
                out.write(src, i, c)
                i += c
            } else if (n != -128) {
                if (i < end) {
                    val v = src[i].toInt() and 0xFF
                    repeat(1 - n) { out.write(v) }
                    i++
                }
            }
        }
    }
}
