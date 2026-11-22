package com.anddesign.vectorviewer.parsers

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF

internal fun ByteArray.le16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)

internal fun ByteArray.le32(o: Int): Int = le16(o) or (le16(o + 2) shl 16)

internal fun ByteArray.be16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)

internal fun ByteArray.be32(o: Int): Int = (be16(o) shl 16) or be16(o + 2)

internal fun ByteArray.putLe32(o: Int, v: Int) {
    this[o] = (v and 0xFF).toByte()
    this[o + 1] = ((v shr 8) and 0xFF).toByte()
    this[o + 2] = ((v shr 16) and 0xFF).toByte()
    this[o + 3] = ((v shr 24) and 0xFF).toByte()
}

internal fun ByteArray.indexOf(pattern: ByteArray, from: Int = 0): Int {
    if (pattern.isEmpty()) return from
    outer@ for (i in from..size - pattern.size) {
        for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}

/** Decodifica hexadecimal ignorando todo lo que no sea [0-9a-fA-F] (p. ej. "%" y saltos de línea). */
internal fun hexDecode(s: CharSequence, start: Int, end: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream((end - start) / 2)
    var hi = -1
    for (i in start until end) {
        val v = Character.digit(s[i], 16)
        if (v < 0) continue
        if (hi < 0) hi = v else {
            out.write((hi shl 4) or v)
            hi = -1
        }
    }
    return out.toByteArray()
}
