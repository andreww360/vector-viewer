package com.anddesign.vectorviewer.ps

/** Intérprete de charstrings Type 1: convierte un glifo en trazado (espacio de glifo, 1000 unidades). */
object Type1 {

    class Glyph(val path: PsPath, val wx: Double, val wy: Double)

    fun decrypt(b: ByteArray, r0: Int, skip: Int): ByteArray {
        var r = r0
        val out = ByteArray(maxOf(0, b.size - skip))
        for (i in b.indices) {
            val c = b[i].toInt() and 0xFF
            val p = c xor (r shr 8)
            r = ((c + r) * 52845 + 22719) and 0xFFFF
            if (i >= skip) out[i - skip] = p.toByte()
        }
        return out
    }

    fun glyph(ip: PsInterpreter, font: PsDict, name: String, depth: Int = 0): Glyph? {
        val cs = (font["CharStrings"] as? PsDict)?.get(name) as? PsString ?: return null
        val priv = font["Private"] as? PsDict
        val lenIV = (priv?.get("lenIV") as? PsInt)?.v ?: 4
        val subrs = priv?.get("Subrs") as? PsArray
        val path = PsPath()
        val st = DoubleArray(48); var sp = 0
        var x = 0.0; var y = 0.0; var wx = 0.0; var wy = 0.0
        val ps = ArrayList<Double>()
        var flex = false; val flexPts = ArrayList<Double>()
        var open = false
        var done = false
        var ops = 0

        fun code(b: PsString) = if (lenIV < 0) b.bytes() else decrypt(b.bytes(), 4330, lenIV)

        fun moveTo(nx: Double, ny: Double) {
            x = nx; y = ny
            if (flex) { flexPts.add(x); flexPts.add(y); return }
            if (open) path.close()
            path.moveTo(x, y); open = true
        }

        fun exec(data: ByteArray) {
            var i = 0
            while (i < data.size && !done) {
                if (++ops > 20000) { done = true; return }
                val v = data[i++].toInt() and 0xFF
                if (v >= 32) {
                    val n = when {
                        v <= 246 -> v - 139
                        v <= 250 -> (v - 247) * 256 + (data[i++].toInt() and 0xFF) + 108
                        v <= 254 -> -(v - 251) * 256 - (data[i++].toInt() and 0xFF) - 108
                        else -> { val r = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF); i += 4; r }
                    }
                    if (sp < st.size) st[sp++] = n.toDouble()
                    continue
                }
                when (v) {
                    1, 3 -> sp = 0 // hstem, vstem
                    4 -> { moveTo(x, y + st[0]); sp = 0 } // vmoveto
                    5 -> { x += st[0]; y += st[1]; path.lineTo(x, y); sp = 0 }
                    6 -> { x += st[0]; path.lineTo(x, y); sp = 0 }
                    7 -> { y += st[0]; path.lineTo(x, y); sp = 0 }
                    8 -> {
                        val x1 = x + st[0]; val y1 = y + st[1]; val x2 = x1 + st[2]; val y2 = y1 + st[3]; x = x2 + st[4]; y = y2 + st[5]
                        path.curveTo(x1, y1, x2, y2, x, y); sp = 0
                    }
                    9 -> { if (open) path.close(); open = false; sp = 0 }
                    10 -> {
                        val n = st[--sp].toInt()
                        val s = subrs?.let { if (n in 0 until it.len) it[n] as? PsString else null }
                        if (s != null) exec(code(s))
                    }
                    11 -> return
                    13 -> { wx = st[1]; x = st[0]; y = 0.0; sp = 0 } // hsbw
                    14 -> { done = true; return } // endchar
                    21 -> { moveTo(x + st[0], y + st[1]); sp = 0 }
                    22 -> { moveTo(x + st[0], y); sp = 0 }
                    30 -> { // vhcurveto
                        val x1 = x; val y1 = y + st[0]; val x2 = x1 + st[1]; val y2 = y1 + st[2]; x = x2 + st[3]; y = y2
                        path.curveTo(x1, y1, x2, y2, x, y); sp = 0
                    }
                    31 -> { // hvcurveto
                        val x1 = x + st[0]; val y1 = y; val x2 = x1 + st[1]; val y2 = y1 + st[2]; x = x2; y = y2 + st[3]
                        path.curveTo(x1, y1, x2, y2, x, y); sp = 0
                    }
                    12 -> {
                        val e = data[i++].toInt() and 0xFF
                        when (e) {
                            0, 1, 2 -> sp = 0 // dotsection, vstem3, hstem3
                            6 -> { // seac: acento compuesto
                                val adx = st[1]; val ady = st[2]; val bchar = st[3].toInt(); val achar = st[4].toInt()
                                val enc = PsFonts.encoding()
                                val b = glyph(ip, font, ip.cvs(enc[bchar]), depth + 1)
                                val a = glyph(ip, font, ip.cvs(enc[achar]), depth + 1)
                                b?.let { appendPath(path, it.path, 0.0, 0.0) }
                                a?.let { appendPath(path, it.path, adx, ady) }
                                done = true; return
                            }
                            7 -> { wx = st[2]; wy = st[3]; x = st[0]; y = st[1]; sp = 0 } // sbw
                            12 -> { val b = st[--sp]; val a = st[--sp]; st[sp++] = if (b != 0.0) a / b else 0.0 }
                            16 -> { // callothersubr
                                val n = st[--sp].toInt(); val cnt = st[--sp].toInt()
                                val args = ArrayList<Double>(); repeat(cnt) { if (sp > 0) args.add(0, st[--sp]) }
                                when (n) {
                                    1 -> { flex = true; flexPts.clear() }
                                    2 -> {}
                                    0 -> {
                                        flex = false
                                        if (flexPts.size >= 14) {
                                            val p = flexPts
                                            path.curveTo(p[2], p[3], p[4], p[5], p[6], p[7])
                                            path.curveTo(p[8], p[9], p[10], p[11], p[12], p[13])
                                            x = p[12]; y = p[13]
                                        }
                                        ps.clear(); ps.add(y); ps.add(x)
                                    }
                                    3 -> { ps.clear(); ps.add(3.0) }
                                    else -> { ps.clear(); ps.addAll(args.reversed()) }
                                }
                            }
                            17 -> { if (ps.isNotEmpty()) st[sp++] = ps.removeAt(ps.size - 1) else st[sp++] = 0.0 } // pop
                            33 -> { x = st[0]; y = st[1]; sp = 0 } // setcurrentpoint
                            else -> sp = 0
                        }
                    }
                    else -> sp = 0
                }
            }
        }
        if (depth > 3) return null
        exec(code(cs))
        if (open) path.close()
        return Glyph(path, wx, wy)
    }

    private fun appendPath(dst: PsPath, src: PsPath, dx: Double, dy: Double) {
        src.forEach { op, p, i ->
            when (op) {
                PsPath.MOVE -> dst.moveTo(p[i] + dx, p[i + 1] + dy)
                PsPath.LINE -> dst.lineTo(p[i] + dx, p[i + 1] + dy)
                PsPath.CURVE -> dst.curveTo(p[i] + dx, p[i + 1] + dy, p[i + 2] + dx, p[i + 3] + dy, p[i + 4] + dx, p[i + 5] + dy)
                else -> dst.close()
            }
        }
    }
}
