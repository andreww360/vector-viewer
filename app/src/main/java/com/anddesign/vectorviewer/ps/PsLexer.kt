package com.anddesign.vectorviewer.ps

/** Tokenizador de PostScript. Lee de un [PsFile] y devuelve un objeto por llamada (null = fin). */
class PsLexer(private val resolveImmediate: (String) -> PsObj) {

    private val sb = StringBuilder()

    private fun isWhite(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0
    private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

    fun next(f: PsFile): PsObj? {
        while (true) {
            val c = f.read()
            if (c < 0) return null
            if (isWhite(c)) continue
            if (c == '%'.code) { skipComment(f); continue }
            return when (c) {
                '{'.code -> readProc(f)
                '}'.code -> throw PsError("syntaxerror")
                '('.code -> readString(f)
                '<'.code -> {
                    val d = f.read()
                    when (d) {
                        '<'.code -> PsName("<<", true)
                        '~'.code -> readA85(f)
                        else -> { f.unread(d); readHex(f) }
                    }
                }
                '>'.code -> {
                    val d = f.read()
                    if (d == '>'.code) PsName(">>", true) else { f.unread(d); throw PsError("syntaxerror") }
                }
                '['.code -> PsName("[", true)
                ']'.code -> PsName("]", true)
                '/'.code -> {
                    val d = f.read()
                    if (d == '/'.code) resolveImmediate(readRegular(f, -1))
                    else { f.unread(d); PsName(readRegular(f, -1), false) }
                }
                else -> {
                    val s = readRegular(f, c)
                    parseNumber(s) ?: PsName(s, true)
                }
            }
        }
    }

    private fun skipComment(f: PsFile) {
        while (true) {
            val c = f.read()
            if (c < 0 || c == 10 || c == 13) return
        }
    }

    private fun readRegular(f: PsFile, first: Int): String {
        sb.setLength(0)
        if (first >= 0) sb.append(first.toChar())
        while (true) {
            val c = f.read()
            if (c < 0) break
            if (isWhite(c)) { if (c == 13) { val d = f.read(); if (d != 10) f.unread(d) }; break }
            if (isDelim(c)) { f.unread(c); break }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readProc(f: PsFile): PsObj {
        val items = ArrayList<PsObj>()
        while (true) {
            val c = f.read()
            if (c < 0) throw PsError("syntaxerror")
            if (isWhite(c)) continue
            if (c == '}'.code) return PsArray(items, true)
            f.unread(c)
            // Dentro de un procedimiento los nombres no se ejecutan: se guardan tal cual.
            val o = nextInProc(f) ?: throw PsError("syntaxerror")
            items.add(o)
        }
    }

    private fun nextInProc(f: PsFile): PsObj? {
        while (true) {
            val c = f.read()
            if (c < 0) return null
            if (isWhite(c)) continue
            if (c == '%'.code) { skipComment(f); continue }
            f.unread(c)
            return next(f)
        }
    }

    private fun readString(f: PsFile): PsObj {
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        while (true) {
            var c = f.read()
            if (c < 0) break
            when (c) {
                '('.code -> { depth++; out.write(c) }
                ')'.code -> { depth--; if (depth == 0) break; out.write(c) }
                '\\'.code -> {
                    c = f.read()
                    when (c) {
                        'n'.code -> out.write(10)
                        'r'.code -> out.write(13)
                        't'.code -> out.write(9)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        10 -> {}
                        13 -> { val d = f.read(); if (d != 10) f.unread(d) }
                        in '0'.code..'7'.code -> {
                            var v = c - '0'.code
                            for (k in 0 until 2) {
                                val d = f.read()
                                if (d in '0'.code..'7'.code) v = v * 8 + (d - '0'.code) else { f.unread(d); break }
                            }
                            out.write(v and 0xFF)
                        }
                        -1 -> {}
                        else -> out.write(c)
                    }
                }
                13 -> { val d = f.read(); if (d != 10) f.unread(d); out.write(10) }
                else -> out.write(c)
            }
        }
        val b = out.toByteArray()
        return PsString(b, 0, b.size)
    }

    private fun hexVal(c: Int) = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    private fun readHex(f: PsFile): PsObj {
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (true) {
            val c = f.read()
            if (c < 0 || c == '>'.code) break
            val v = hexVal(c)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write(hi * 16 + v); hi = -1 }
        }
        if (hi >= 0) out.write(hi * 16)
        val b = out.toByteArray()
        return PsString(b, 0, b.size)
    }

    private fun readA85(f: PsFile): PsObj {
        val b = Filters.ascii85(PsFileInputStream(f)).readBytes()
        return PsString(b, 0, b.size)
    }

    companion object {
        fun parseNumber(s: String): PsObj? {
            if (s.isEmpty()) return null
            val c0 = s[0]
            if (!(c0.isDigit() || c0 == '-' || c0 == '+' || c0 == '.')) return null
            val hash = s.indexOf('#')
            if (hash > 0) {
                val radix = s.substring(0, hash).toIntOrNull() ?: return null
                if (radix !in 2..36) return null
                val v = s.substring(hash + 1).toLongOrNull(radix) ?: return null
                return PsInt(v.toInt())
            }
            var digits = false
            var real = false
            for ((i, ch) in s.withIndex()) {
                when {
                    ch.isDigit() -> digits = true
                    ch == '.' || ch == 'e' || ch == 'E' -> real = true
                    (ch == '-' || ch == '+') && (i == 0 || s[i - 1] == 'e' || s[i - 1] == 'E') -> {}
                    else -> return null
                }
            }
            if (!digits) return null
            if (!real) {
                val v = s.toLongOrNull() ?: return null
                return if (v in Int.MIN_VALUE..Int.MAX_VALUE) PsInt(v.toInt()) else PsReal(v.toDouble())
            }
            val d = (if (s.endsWith(".")) s + "0" else s).toDoubleOrNull() ?: return null
            return PsReal(d)
        }
    }
}
