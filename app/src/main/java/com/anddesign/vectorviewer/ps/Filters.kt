package com.anddesign.vectorviewer.ps

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Filtros de decodificación de PostScript (ASCII85, ASCIIHex, RunLength, Flate, LZW, SubFile) y eexec. */
object Filters {

    fun ascii85(src: InputStream): InputStream = object : InputStream() {
        private val buf = IntArray(4); private var n = 0; private var i = 0; private var done = false
        override fun read(): Int {
            if (i < n) return buf[i++]
            if (done) return -1
            val g = IntArray(5); var k = 0
            while (k < 5) {
                val c = src.read()
                if (c < 0 || c == '~'.code) { if (c == '~'.code) src.read(); done = true; break }
                if (c == 'z'.code && k == 0) { buf.fill(0); n = 4; i = 0; return read() }
                if (c < '!'.code || c > 'u'.code) continue
                g[k++] = c - 33
            }
            if (k == 0) return -1
            val m = k
            while (k < 5) g[k++] = 84
            var v = 0L
            for (x in g) v = v * 85 + x
            for (j in 0 until 4) buf[j] = ((v shr (24 - 8 * j)) and 0xFF).toInt()
            n = m - 1; i = 0
            return if (n > 0) buf[i++] else -1
        }
    }

    fun asciiHex(src: InputStream): InputStream = object : InputStream() {
        private var done = false
        private fun hv(c: Int) = Character.digit(c, 16)
        override fun read(): Int {
            if (done) return -1
            var hi = -1
            while (true) {
                val c = src.read()
                if (c < 0 || c == '>'.code) { done = true; return if (hi >= 0) hi shl 4 else -1 }
                val v = hv(c)
                if (v < 0) continue
                if (hi < 0) hi = v else return (hi shl 4) or v
            }
        }
    }

    fun runLength(src: InputStream): InputStream = object : InputStream() {
        private var lit = 0; private var rep = 0; private var repByte = 0; private var done = false
        override fun read(): Int {
            while (true) {
                if (lit > 0) { lit--; return src.read() }
                if (rep > 0) { rep--; return repByte }
                if (done) return -1
                val l = src.read()
                if (l < 0 || l == 128) { done = true; return -1 }
                if (l < 128) lit = l + 1 else { rep = 257 - l; repByte = src.read().coerceAtLeast(0) }
            }
        }
    }

    fun flate(src: InputStream): InputStream {
        val b = src.buffered()
        b.mark(2)
        val cmf = b.read(); b.read(); b.reset()
        val nowrap = (cmf and 0x0F) != 8
        return InflaterInputStream(b, Inflater(nowrap), 4096)
    }

    fun lzw(src: InputStream, early: Int = 1): InputStream {
        val out = ByteArrayOutputStream()
        var bitBuf = 0L; var bits = 0
        var codeLen = 9
        val dict = ArrayList<ByteArray>(4096)
        fun reset() { dict.clear(); for (i in 0 until 256) dict.add(byteArrayOf(i.toByte())); dict.add(ByteArray(0)); dict.add(ByteArray(0)); codeLen = 9 }
        reset()
        var prev: ByteArray? = null
        loop@ while (true) {
            while (bits < codeLen) { val c = src.read(); if (c < 0) break@loop; bitBuf = (bitBuf shl 8) or c.toLong(); bits += 8 }
            val code = ((bitBuf shr (bits - codeLen)) and ((1L shl codeLen) - 1)).toInt(); bits -= codeLen
            if (code == 256) { reset(); prev = null; continue }
            if (code == 257) break
            val entry = when {
                code < dict.size -> dict[code]
                prev != null -> prev + prev[0]
                else -> break@loop
            }
            out.write(entry)
            if (prev != null && dict.size < 4096) dict.add(prev + entry[0])
            prev = entry
            if (dict.size + early >= (1 shl codeLen) && codeLen < 12) codeLen++
        }
        return out.toByteArray().inputStream()
    }

    /** SubFileDecode: lee hasta [count] repeticiones de [eod] (o [n] bytes si eod está vacío). */
    fun subFile(src: InputStream, count: Int, eod: ByteArray): InputStream = object : InputStream() {
        private var remaining = count
        private var bytesLeft = if (eod.isEmpty()) count else -1
        private val pending = ArrayDeque<Int>()
        private var matched = 0
        private var done = false
        override fun read(): Int {
            if (pending.isNotEmpty()) return pending.removeFirst()
            if (done) return -1
            if (eod.isEmpty()) {
                if (count == 0) return src.read()
                if (bytesLeft <= 0) { done = true; return -1 }
                bytesLeft--; return src.read()
            }
            while (true) {
                val c = src.read()
                if (c < 0) { done = true; for (j in 0 until matched) pending.addLast(eod[j].toInt() and 0xFF); matched = 0; return if (pending.isEmpty()) -1 else pending.removeFirst() }
                if (c == (eod[matched].toInt() and 0xFF)) {
                    matched++
                    if (matched == eod.size) {
                        matched = 0
                        if (count == 0 || --remaining <= 0) { done = true; return if (pending.isEmpty()) -1 else pending.removeFirst() }
                        for (b in eod) pending.addLast(b.toInt() and 0xFF)
                        return pending.removeFirst()
                    }
                    continue
                }
                for (j in 0 until matched) pending.addLast(eod[j].toInt() and 0xFF)
                matched = 0
                if (c == (eod[0].toInt() and 0xFF)) { matched = 1; return if (pending.isEmpty()) read() else pending.removeFirst() }
                pending.addLast(c)
                return pending.removeFirst()
            }
        }
    }

    /** Copia un JPEG hasta su marcador final (FF D9), sin decodificarlo. */
    fun jpegUntilEoi(src: InputStream): InputStream = object : InputStream() {
        private var prev = 0; private var done = false
        override fun read(): Int {
            if (done) return -1
            val c = src.read()
            if (c < 0 || (prev == 0xFF && c == 0xD9)) done = true
            prev = c
            return c
        }
    }

    /** Descifrado eexec (fuentes Type 1). Detecta binario o hexadecimal y salta los 4 bytes iniciales. */
    class EexecFile(private val src: PsFile) : PsFile(true) {
        private var r = 55665
        private var hex = false
        private var started = false
        private val head = ArrayList<Int>()
        private var headPos = 0

        private fun rawByte(): Int {
            if (headPos < head.size) return head[headPos++]
            if (!hex) return src.read()
            var hi = -1
            while (true) {
                val c = src.read()
                if (c < 0) return -1
                val v = Character.digit(c, 16)
                if (v < 0) continue
                if (hi < 0) hi = v else return (hi shl 4) or v
            }
        }

        private fun start() {
            started = true
            var c = src.read()
            while (c == 32 || c == 9 || c == 10 || c == 13) c = src.read()
            val first = ArrayList<Int>()
            first.add(c)
            for (i in 0 until 3) first.add(src.read())
            hex = first.all { it >= 0 && Character.digit(it, 16) >= 0 }
            if (hex) {
                // re-agrupar los 4 caracteres hexadecimales leídos como 2 bytes y completar a 4
                head.add((Character.digit(first[0], 16) shl 4) or Character.digit(first[1], 16))
                head.add((Character.digit(first[2], 16) shl 4) or Character.digit(first[3], 16))
            } else head.addAll(first)
            for (i in 0 until 4) dec(rawByte())
        }

        private fun dec(c: Int): Int {
            if (c < 0) return -1
            val p = c xor (r shr 8)
            r = ((c + r) * 52845 + 22719) and 0xFFFF
            return p and 0xFF
        }

        override fun readRaw(): Int {
            if (!started) start()
            return dec(rawByte())
        }
    }
}
