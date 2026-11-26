package com.anddesign.vectorviewer.ps

import kotlin.math.*

// ============================================================ modelo de dibujo (sin Android)

/** Trazado en coordenadas de dispositivo (= espacio de usuario por defecto de PostScript, en pt, Y hacia arriba). */
class PsPath {
    var ops = ByteArray(16); var nOps = 0
    var pts = DoubleArray(32); var nPts = 0

    fun copy(): PsPath = PsPath().also { it.ops = ops.copyOf(); it.nOps = nOps; it.pts = pts.copyOf(); it.nPts = nPts }
    val isEmpty get() = nOps == 0
    fun clear() { nOps = 0; nPts = 0 }

    private fun add(op: Int, vararg p: Double) {
        if (nOps == ops.size) ops = ops.copyOf(ops.size * 2)
        ops[nOps++] = op.toByte()
        if (nPts + p.size > pts.size) pts = pts.copyOf(max(pts.size * 2, nPts + p.size))
        for (v in p) pts[nPts++] = v
    }

    fun moveTo(x: Double, y: Double) {
        if (nOps > 0 && ops[nOps - 1] == MOVE.toByte()) { pts[nPts - 2] = x; pts[nPts - 1] = y; return }
        add(MOVE, x, y)
    }
    fun lineTo(x: Double, y: Double) = add(LINE, x, y)
    fun curveTo(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) = add(CURVE, a, b, c, d, e, f)
    fun close() { if (nOps > 0 && ops[nOps - 1] != CLOSE.toByte()) add(CLOSE) }

    inline fun forEach(f: (op: Int, pts: DoubleArray, i: Int) -> Unit) {
        var p = 0
        for (k in 0 until nOps) {
            val op = ops[k].toInt()
            f(op, pts, p)
            p += when (op) { MOVE, LINE -> 2; CURVE -> 6; else -> 0 }
        }
    }

    fun transformed(m: DoubleArray): PsPath {
        val r = copy()
        var i = 0
        while (i < r.nPts) {
            val x = r.pts[i]; val y = r.pts[i + 1]
            r.pts[i] = m[0] * x + m[2] * y + m[4]; r.pts[i + 1] = m[1] * x + m[3] * y + m[5]; i += 2
        }
        return r
    }

    fun bounds(): DoubleArray? {
        if (nPts == 0) return null
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        var i = 0
        while (i < nPts) { x0 = min(x0, pts[i]); x1 = max(x1, pts[i]); y0 = min(y0, pts[i + 1]); y1 = max(y1, pts[i + 1]); i += 2 }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    companion object { const val MOVE = 0; const val LINE = 1; const val CURVE = 2; const val CLOSE = 3 }
}

class ClipNode(val path: PsPath, val evenOdd: Boolean, val parent: ClipNode?)

sealed class DrawOp { abstract val clip: ClipNode? }
class FillOp(val path: PsPath, val evenOdd: Boolean, val argb: Int, override val clip: ClipNode?) : DrawOp()
class StrokeOp(
    val path: PsPath, val ctm: DoubleArray, val width: Double, val cap: Int, val join: Int, val miter: Double,
    val dash: DoubleArray?, val dashPhase: Double, val argb: Int, override val clip: ClipNode?,
) : DrawOp()
/** Imagen ARGB; [matrix] lleva el cuadrado unidad de la imagen (fila 0 arriba) al dispositivo. */
class ImageOp(val argb: IntArray?, val jpeg: ByteArray?, val w: Int, val h: Int, val matrix: DoubleArray, override val clip: ClipNode?) : DrawOp()
class ShadeOp(
    val radial: Boolean, val coords: DoubleArray, val ctm: DoubleArray, val colors: IntArray, val stops: FloatArray,
    val extendStart: Boolean, val extendEnd: Boolean, override val clip: ClipNode?,
) : DrawOp()
class TextOp(val text: String, val matrix: DoubleArray, val fontName: String, val argb: Int, override val clip: ClipNode?) : DrawOp()

class ColorSpace(
    val family: String, val n: Int,
    val alt: ColorSpace? = null, val tint: PsObj? = null,
    val base: ColorSpace? = null, val hival: Int = 0, val lookup: ByteArray? = null, val lookupProc: PsObj? = null,
) {
    companion object {
        val GRAY = ColorSpace("DeviceGray", 1); val RGB = ColorSpace("DeviceRGB", 3); val CMYK = ColorSpace("DeviceCMYK", 4)
        val PATTERN = ColorSpace("Pattern", 1)
    }
}

class GState {
    var ctm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    var path = PsPath()
    var cpx = 0.0; var cpy = 0.0; var hasCp = false
    var space: ColorSpace = ColorSpace.GRAY
    var comps = doubleArrayOf(0.0)
    var argb = 0xFF000000.toInt()
    var pattern: PsDict? = null
    var lineWidth = 1.0; var cap = 0; var join = 0; var miter = 10.0
    var dash: DoubleArray? = null; var dashPhase = 0.0
    var clip: ClipNode? = null
    var font: PsDict? = null
    var csObj: PsObj = PsArray(listOf(PsName("DeviceGray", false)))

    fun copy(): GState = GState().also { it.copyFrom(this); it.path = path.copy() }

    fun copyFrom(o: GState) {
        ctm = o.ctm.copyOf(); path = o.path.copy(); cpx = o.cpx; cpy = o.cpy; hasCp = o.hasCp
        space = o.space; comps = o.comps.copyOf(); argb = o.argb; pattern = o.pattern
        lineWidth = o.lineWidth; cap = o.cap; join = o.join; miter = o.miter; dash = o.dash; dashPhase = o.dashPhase
        clip = o.clip; font = o.font; csObj = o.csObj
    }
}

object Mx {
    fun mul(m: DoubleArray, n: DoubleArray) = doubleArrayOf(
        m[0] * n[0] + m[1] * n[2], m[0] * n[1] + m[1] * n[3],
        m[2] * n[0] + m[3] * n[2], m[2] * n[1] + m[3] * n[3],
        m[4] * n[0] + m[5] * n[2] + n[4], m[4] * n[1] + m[5] * n[3] + n[5],
    )
    fun inv(m: DoubleArray): DoubleArray {
        val det = m[0] * m[3] - m[1] * m[2]
        if (abs(det) < 1e-300) throw PsError("undefinedresult")
        val a = m[3] / det; val b = -m[1] / det; val c = -m[2] / det; val d = m[0] / det
        return doubleArrayOf(a, b, c, d, -(m[4] * a + m[5] * c), -(m[4] * b + m[5] * d))
    }
    fun tx(m: DoubleArray, x: Double, y: Double) = m[0] * x + m[2] * y + m[4]
    fun ty(m: DoubleArray, x: Double, y: Double) = m[1] * x + m[3] * y + m[5]
}

// ============================================================ operadores gráficos

class PsGraphics(private val ip: PsInterpreter) {
    var gs = GState()
    private val stack = ArrayList<GState>()
    val ops = ArrayList<DrawOp>()
    /** Decodificador JPEG que pone la plataforma (Android); devuelve ARGB y tamaño. */
    var jpegDecoder: ((ByteArray) -> Triple<IntArray, Int, Int>?)? = null
    private var pixelBudget = 40_000_000L

    fun depth() = stack.size
    fun gsave() { stack.add(gs.copy()) }
    fun grestore() { if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1) }
    fun restoreTo(d: Int) { while (stack.size > d) grestore() }

    private fun arr(vararg v: Double) = PsArray(v.map { PsReal(it) as PsObj })
    private fun matOf(o: PsObj): DoubleArray {
        val a = o as? PsArray ?: throw PsError("typecheck"); if (a.len < 6) throw PsError("rangecheck")
        return DoubleArray(6) { ip.num(a[it]) }
    }
    private fun storeMat(a: PsArray, m: DoubleArray): PsArray { for (i in 0 until 6) a[i] = PsReal(m[i]); return a }

    fun userToDev(x: Double, y: Double) = Pair(Mx.tx(gs.ctm, x, y), Mx.ty(gs.ctm, x, y))
    fun devToUser(x: Double, y: Double): Pair<Double, Double> { val i = Mx.inv(gs.ctm); return Pair(Mx.tx(i, x, y), Mx.ty(i, x, y)) }

    private fun needCp() { if (!gs.hasCp) throw PsError("nocurrentpoint") }

    private fun moveDev(x: Double, y: Double) { gs.path.moveTo(x, y); gs.cpx = x; gs.cpy = y; gs.hasCp = true }
    private fun lineDev(x: Double, y: Double) { needCp(); gs.path.lineTo(x, y); gs.cpx = x; gs.cpy = y }
    private fun curveDev(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        needCp(); gs.path.curveTo(a, b, c, d, e, f); gs.cpx = e; gs.cpy = f
    }

    fun moveTo(x: Double, y: Double) { val m = gs.ctm; moveDev(Mx.tx(m, x, y), Mx.ty(m, x, y)) }
    fun lineTo(x: Double, y: Double) { val m = gs.ctm; lineDev(Mx.tx(m, x, y), Mx.ty(m, x, y)) }
    fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        val m = gs.ctm
        curveDev(Mx.tx(m, x1, y1), Mx.ty(m, x1, y1), Mx.tx(m, x2, y2), Mx.ty(m, x2, y2), Mx.tx(m, x3, y3), Mx.ty(m, x3, y3))
    }

    fun closePath() {
        if (gs.path.isEmpty) return
        gs.path.close()
        // el punto actual vuelve al inicio del subtrazado
        var sx = gs.cpx; var sy = gs.cpy
        gs.path.forEach { op, p, i -> if (op == PsPath.MOVE) { sx = p[i]; sy = p[i + 1] } }
        gs.cpx = sx; gs.cpy = sy
    }

    private fun arc(cx: Double, cy: Double, r: Double, a1In: Double, a2In: Double, ccw: Boolean) {
        var a1 = a1In; var a2 = a2In
        if (ccw) { while (a2 < a1) a2 += 360.0 } else { while (a2 > a1) a2 -= 360.0 }
        val sx = cx + r * cos(Math.toRadians(a1)); val sy = cy + r * sin(Math.toRadians(a1))
        if (gs.hasCp) lineTo(sx, sy) else moveTo(sx, sy)
        val total = a2 - a1
        val n = max(1, ceil(abs(total) / 90.0 - 1e-9).toInt())
        val step = total / n
        for (i in 0 until n) {
            val t1 = Math.toRadians(a1 + step * i); val t2 = Math.toRadians(a1 + step * (i + 1))
            val k = 4.0 / 3.0 * tan((t2 - t1) / 4)
            curveTo(
                cx + r * (cos(t1) - k * sin(t1)), cy + r * (sin(t1) + k * cos(t1)),
                cx + r * (cos(t2) + k * sin(t2)), cy + r * (sin(t2) - k * cos(t2)),
                cx + r * cos(t2), cy + r * sin(t2),
            )
        }
    }

    // ------------------------------------------------------------ pintura
    fun emitFill(evenOdd: Boolean) {
        val pat = gs.pattern
        if (!gs.path.isEmpty) {
            if (pat != null) fillWithPattern(pat, gs.path, evenOdd)
            else ops.add(FillOp(gs.path, evenOdd, gs.argb, gs.clip))
        }
        newPath()
    }

    fun emitStroke() {
        if (!gs.path.isEmpty) {
            val inv = try { Mx.inv(gs.ctm) } catch (e: PsError) { null }
            if (inv != null) ops.add(
                StrokeOp(gs.path.transformed(inv), gs.ctm.copyOf(), gs.lineWidth, gs.cap, gs.join, gs.miter, gs.dash, gs.dashPhase, gs.argb, gs.clip)
            )
        }
        newPath()
    }

    fun newPath() { gs.path = PsPath(); gs.hasCp = false }

    fun clip(evenOdd: Boolean) {
        gs.clip = ClipNode(gs.path.copy(), evenOdd, gs.clip)
    }

    private fun rectPath(x: Double, y: Double, w: Double, h: Double) {
        moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); closePath()
    }

    private fun rects(body: (Double, Double, Double, Double) -> Unit) {
        val top = ip.pop()
        if (top is PsArray) {
            var i = 0; while (i + 3 < top.len) { body(ip.num(top[i]), ip.num(top[i + 1]), ip.num(top[i + 2]), ip.num(top[i + 3])); i += 4 }
        } else if (top is PsString) {
            // cadena codificada (número homogéneo): poco común, se ignora
        } else {
            val h = ip.num(top); val w = ip.popNum(); val y = ip.popNum(); val x = ip.popNum(); body(x, y, w, h)
        }
    }

    // ------------------------------------------------------------ color
    fun cmykToArgb(c: Double, m: Double, y: Double, k: Double): Int {
        val r = (1 - min(1.0, c + k)); val g = (1 - min(1.0, m + k)); val b = (1 - min(1.0, y + k))
        return rgb(r, g, b)
    }
    fun rgb(r: Double, g: Double, b: Double): Int =
        (0xFF shl 24) or ((r.coerceIn(0.0, 1.0) * 255 + 0.5).toInt() shl 16) or ((g.coerceIn(0.0, 1.0) * 255 + 0.5).toInt() shl 8) or (b.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()

    fun toArgb(cs: ColorSpace, c: DoubleArray): Int {
        fun at(i: Int) = if (i < c.size) c[i] else 0.0
        return when (cs.family) {
            "DeviceGray", "CIEBasedA", "CalGray" -> rgb(at(0), at(0), at(0))
            "DeviceRGB", "CIEBasedABC", "CalRGB", "Lab" -> if (cs.family == "Lab") labToArgb(at(0), at(1), at(2)) else rgb(at(0), at(1), at(2))
            "DeviceCMYK", "CIEBasedDEFG" -> cmykToArgb(at(0), at(1), at(2), at(3))
            "Separation", "DeviceN" -> {
                val alt = cs.alt ?: return rgb(1 - at(0), 1 - at(0), 1 - at(0))
                val out = try { evalTint(cs.tint, c) } catch (e: PsError) { null }
                if (out == null || out.isEmpty()) rgb(1 - at(0), 1 - at(0), 1 - at(0)) else toArgb(alt, out)
            }
            "Indexed" -> {
                val base = cs.base ?: return 0xFF000000.toInt()
                val i = at(0).toInt().coerceIn(0, cs.hival)
                val comps = if (cs.lookup != null) DoubleArray(base.n) { k -> ((cs.lookup.getOrNull(i * base.n + k)?.toInt() ?: 0) and 0xFF) / 255.0 }
                else if (cs.lookupProc != null) ip.callForResults(cs.lookupProc, doubleArrayOf(i.toDouble())) else DoubleArray(base.n)
                toArgb(base, comps)
            }
            else -> rgb(at(0), at(0), at(0))
        }
    }

    private fun labToArgb(l: Double, a: Double, b: Double): Int {
        fun f(t: Double) = if (t > 6.0 / 29) t * t * t else 3 * (6.0 / 29) * (6.0 / 29) * (t - 4.0 / 29)
        val fy = (l + 16) / 116; val x = 0.9505 * f(fy + a / 500); val y = f(fy); val z = 1.089 * f(fy - b / 200)
        fun g(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
        return rgb(g(3.2406 * x - 1.5372 * y - 0.4986 * z), g(-0.9689 * x + 1.8758 * y + 0.0415 * z), g(0.0557 * x - 0.2040 * y + 1.0570 * z))
    }

    private fun evalTint(fn: PsObj?, c: DoubleArray): DoubleArray? = when {
        fn == null -> null
        fn is PsDict -> evalFunction(fn, c)
        fn is PsArray && fn.isExec -> ip.callForResults(fn, c)
        else -> null
    }

    fun parseSpace(o: PsObj): ColorSpace {
        val (fam, a) = when (o) {
            is PsName -> Pair(o.name, null)
            is PsString -> Pair(o.text(), null)
            is PsArray -> Pair(ip.cvs(o[0]), o)
            else -> throw PsError("typecheck")
        }
        return when (fam) {
            "DeviceGray", "G", "CalGray", "CIEBasedA" -> ColorSpace.GRAY
            "DeviceRGB", "RGB", "CalRGB", "CIEBasedABC" -> ColorSpace.RGB
            "DeviceCMYK", "CMYK", "CIEBasedDEFG", "CIEBasedDEF" -> ColorSpace.CMYK
            "Lab" -> ColorSpace("Lab", 3)
            "Pattern" -> ColorSpace("Pattern", 1, base = if (a != null && a.len > 1) parseSpace(a[1]) else null)
            "ICCBased" -> {
                val d = a?.get(1) as? PsDict
                d?.get("Alternate")?.let { return parseSpace(it) }
                when ((d?.get("N") as? PsInt)?.v ?: 3) { 1 -> ColorSpace.GRAY; 4 -> ColorSpace.CMYK; else -> ColorSpace.RGB }
            }
            "Separation" -> ColorSpace("Separation", 1, alt = parseSpace(a!![2]), tint = a[3])
            "DeviceN" -> ColorSpace("DeviceN", (a!![1] as PsArray).len, alt = parseSpace(a[2]), tint = a[3])
            "Indexed", "I" -> {
                val base = parseSpace(a!![1]); val hi = ip.num(a[2]).toInt()
                val lk = a[3]
                ColorSpace("Indexed", 1, base = base, hival = hi, lookup = (lk as? PsString)?.bytes(), lookupProc = if (lk is PsArray) lk else null)
            }
            else -> ColorSpace.GRAY
        }
    }

    fun setSpace(o: PsObj) {
        gs.space = parseSpace(o); gs.csObj = o; gs.pattern = null
        gs.comps = when (gs.space.family) {
            "DeviceCMYK" -> doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            "Separation", "DeviceN" -> DoubleArray(gs.space.n) { 1.0 }
            "Lab" -> doubleArrayOf(0.0, 0.0, 0.0)
            else -> DoubleArray(gs.space.n)
        }
        gs.argb = toArgb(gs.space, gs.comps)
    }

    fun setColor(c: DoubleArray) {
        gs.comps = c; gs.pattern = null
        gs.argb = toArgb(gs.space, c)
    }

    private fun simpleColor(space: ColorSpace, obj: String, c: DoubleArray) {
        gs.space = space; gs.csObj = PsName(obj, false); setColor(c)
    }

    // ------------------------------------------------------------ funciones (shading, separaciones)
    fun evalFunction(fo: PsObj, input: DoubleArray): DoubleArray {
        if (fo is PsArray && !fo.isExec) {
            return DoubleArray(fo.len) { evalFunction(fo[it], input).firstOrNull() ?: 0.0 }
        }
        if (fo is PsArray) return ip.callForResults(fo, input)
        val d = fo as? PsDict ?: throw PsError("typecheck")
        val dom = (d["Domain"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } } ?: doubleArrayOf(0.0, 1.0)
        val x = DoubleArray(input.size) { input[it].coerceIn(dom.getOrElse(2 * it) { 0.0 }, dom.getOrElse(2 * it + 1) { 1.0 }) }
        val range = (d["Range"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } }
        val out = when ((d["FunctionType"] as? PsInt)?.v ?: 2) {
            2 -> {
                val c0 = (d["C0"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } } ?: doubleArrayOf(0.0)
                val c1 = (d["C1"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } } ?: doubleArrayOf(1.0)
                val n = ip.num(d["N"] ?: PsInt(1)); val t = x[0].pow(n)
                DoubleArray(c0.size) { c0[it] + t * (c1.getOrElse(it) { 0.0 } - c0[it]) }
            }
            3 -> {
                val fns = d["Functions"] as PsArray
                val bounds = (d["Bounds"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } } ?: DoubleArray(0)
                val enc = (d["Encode"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } } ?: DoubleArray(0)
                var k = 0; while (k < bounds.size && x[0] >= bounds[k]) k++
                val lo = if (k == 0) dom[0] else bounds[k - 1]; val hi = if (k == bounds.size) dom[1] else bounds[k]
                val e0 = enc.getOrElse(2 * k) { 0.0 }; val e1 = enc.getOrElse(2 * k + 1) { 1.0 }
                val t = if (hi > lo) e0 + (x[0] - lo) * (e1 - e0) / (hi - lo) else e0
                evalFunction(fns[k.coerceAtMost(fns.len - 1)], doubleArrayOf(t))
            }
            0 -> sampled(d, x, dom)
            4 -> ip.callForResults(d["_proc"] ?: (d["DataSource"] ?: throw PsError("undefined")), x)
            else -> doubleArrayOf(0.0)
        }
        if (range != null) for (i in out.indices) if (2 * i + 1 < range.size) out[i] = out[i].coerceIn(range[2 * i], range[2 * i + 1])
        return out
    }

    private fun sampled(d: PsDict, x: DoubleArray, dom: DoubleArray): DoubleArray {
        val size = (d["Size"] as PsArray).let { a -> IntArray(a.len) { ip.num(a[it]).toInt() } }
        val bps = ip.num(d["BitsPerSample"] ?: PsInt(8)).toInt()
        val range = (d["Range"] as PsArray).let { a -> DoubleArray(a.len) { ip.num(a[it]) } }
        val nOut = range.size / 2
        val data = (d["_data"] as? PsString)?.bytes() ?: run {
            val src = d["DataSource"]
            val total = size.fold(1) { acc, v -> acc * v } * nOut * bps / 8 + 1
            val bytes = when (src) {
                is PsString -> src.bytes()
                is PsFile -> ByteArray(total).also { b -> for (i in b.indices) b[i] = src.read().coerceAtLeast(0).toByte() }
                else -> ByteArray(total)
            }
            d.put("_data", PsString(bytes, 0, bytes.size)); bytes
        }
        val enc = (d["Encode"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } }
        val dec = (d["Decode"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } } ?: range
        // solo 1 entrada (lo habitual en degradados)
        val e0 = enc?.get(0) ?: 0.0; val e1 = enc?.get(1) ?: (size[0] - 1).toDouble()
        val t = ((x[0] - dom[0]) / max(1e-12, dom[1] - dom[0]) * (e1 - e0) + e0).coerceIn(0.0, (size[0] - 1).toDouble())
        val i0 = floor(t).toInt(); val i1 = min(i0 + 1, size[0] - 1); val f = t - i0
        val maxV = (1L shl bps) - 1
        fun sample(idx: Int, o: Int): Double {
            val bit = (idx * nOut + o).toLong() * bps
            var v = 0L
            for (b in 0 until bps) {
                val pos = bit + b; val byte = data.getOrNull((pos / 8).toInt())?.toInt() ?: 0
                v = (v shl 1) or ((byte shr (7 - (pos % 8).toInt())) and 1).toLong()
            }
            return v.toDouble() / maxV
        }
        return DoubleArray(nOut) { o ->
            val s = sample(i0, o) * (1 - f) + sample(i1, o) * f
            dec[2 * o] + s * (dec[2 * o + 1] - dec[2 * o])
        }
    }

    // ------------------------------------------------------------ shading y patrones
    fun shade(sh: PsDict, ctm: DoubleArray, clip: ClipNode?) {
        val type = (sh["ShadingType"] as? PsInt)?.v ?: return
        val cs = parseSpace(sh["ColorSpace"] ?: return)
        val fn = sh["Function"]
        if (type == 2 || type == 3) {
            val c = (sh["Coords"] as PsArray).let { a -> DoubleArray(a.len) { ip.num(a[it]) } }
            val dom = (sh["Domain"] as? PsArray)?.let { a -> doubleArrayOf(ip.num(a[0]), ip.num(a[1])) } ?: doubleArrayOf(0.0, 1.0)
            val ext = sh["Extend"] as? PsArray
            val n = 33
            val colors = IntArray(n); val stops = FloatArray(n)
            for (i in 0 until n) {
                val f = i / (n - 1.0)
                val t = dom[0] + f * (dom[1] - dom[0])
                val comps = if (fn != null) try { evalFunction(fn, doubleArrayOf(t)) } catch (e: PsError) { doubleArrayOf(f) } else doubleArrayOf(f)
                colors[i] = toArgb(cs, comps); stops[i] = f.toFloat()
            }
            ops.add(ShadeOp(type == 3, c, ctm, colors, stops, (ext?.get(0) as? PsBool)?.v ?: false, (ext?.get(1) as? PsBool)?.v ?: false, clip))
        } else {
            // función de color (1) y mallas (4-7): se aproxima con un color medio sobre el área de recorte
            val bg = sh["Background"] as? PsArray
            val comps = bg?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } }
                ?: firstMeshColor(sh, cs) ?: return
            val area = clip?.path ?: return
            ops.add(FillOp(area, clip.evenOdd, toArgb(cs, comps), clip.parent))
        }
    }

    private fun firstMeshColor(sh: PsDict, cs: ColorSpace): DoubleArray? {
        val fn = sh["Function"]
        if (fn != null) return try { evalFunction(fn, doubleArrayOf(0.5, 0.5)) } catch (e: PsError) { null }
        val dec = (sh["Decode"] as? PsArray) ?: return null
        val n = cs.n
        return DoubleArray(n) { k -> val lo = ip.num(dec[dec.len - 2 * n + 2 * k]); val hi = ip.num(dec[dec.len - 2 * n + 2 * k + 1]); (lo + hi) / 2 }
    }

    private fun fillWithPattern(pat: PsDict, path: PsPath, evenOdd: Boolean) {
        val pm = (pat["_ctm"] as? PsArray)?.let { matOf(it) } ?: gs.ctm
        val clipNode = ClipNode(path.copy(), evenOdd, gs.clip)
        when ((pat["PatternType"] as? PsInt)?.v) {
            2 -> (pat["Shading"] as? PsDict)?.let { shade(it, pm, clipNode) }
            1 -> tile(pat, pm, clipNode)
        }
    }

    private fun tile(pat: PsDict, pm: DoubleArray, clipNode: ClipNode) {
        val bbox = (pat["BBox"] as? PsArray)?.let { a -> DoubleArray(4) { ip.num(a[it]) } } ?: return
        val xs = ip.num(pat["XStep"] ?: return); val ys = ip.num(pat["YStep"] ?: return)
        val proc = pat["PaintProc"] ?: return
        val area = clipNode.path.bounds() ?: return
        val inv = try { Mx.inv(pm) } catch (e: PsError) { return }
        val corners = listOf(area[0] to area[1], area[2] to area[1], area[0] to area[3], area[2] to area[3]).map { (x, y) -> Mx.tx(inv, x, y) to Mx.ty(inv, x, y) }
        val ux0 = corners.minOf { it.first }; val ux1 = corners.maxOf { it.first }
        val uy0 = corners.minOf { it.second }; val uy1 = corners.maxOf { it.second }
        if (abs(xs) < 1e-6 || abs(ys) < 1e-6) return
        val i0 = floor((ux0 - bbox[2]) / abs(xs)).toInt(); val i1 = ceil((ux1 - bbox[0]) / abs(xs)).toInt()
        val j0 = floor((uy0 - bbox[3]) / abs(ys)).toInt(); val j1 = ceil((uy1 - bbox[1]) / abs(ys)).toInt()
        if ((i1 - i0 + 1).toLong() * (j1 - j0 + 1) > 2500) return
        val uncolored = (pat["PaintType"] as? PsInt)?.v == 2
        val saved = gs
        for (i in i0..i1) for (j in j0..j1) {
            gs = saved.copy(); gs.pattern = null
            if (uncolored && gs.space.base != null) { gs.argb = toArgb(gs.space.base!!, gs.comps) }
            gs.ctm = Mx.mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, i * xs, j * ys), pm)
            gs.clip = clipNode; newPath()
            val base = ip.ostack.size
            try { ip.push(pat); ip.call(proc) } catch (e: PsError) { } catch (e: PsExit) { }
            while (ip.ostack.size > base) ip.ostack.removeAt(ip.ostack.size - 1)
        }
        gs = saved
    }

    // ------------------------------------------------------------ imágenes
    private fun imageOp(mask: Boolean, multiColorOp: Boolean) {
        var w: Int; var h: Int; var bpc: Int; var m: DoubleArray
        var sources: List<PsObj>; var cs: ColorSpace; var decode: DoubleArray? = null; var invertMask = false
        var multi = false
        val top = ip.peek()
        if (top is PsDict) {
            val d = ip.popDict()
            w = ip.num(d["Width"]!!).toInt(); h = ip.num(d["Height"]!!).toInt()
            bpc = if (mask) 1 else ip.num(d["BitsPerComponent"] ?: PsInt(8)).toInt()
            m = matOf(d["ImageMatrix"]!!)
            val ds = d["DataSource"]!!
            multi = (d["MultipleDataSources"] as? PsBool)?.v == true
            sources = if (multi && ds is PsArray && !ds.isExec) ds.toList() else listOf(ds)
            cs = if (mask) ColorSpace.GRAY else gs.space
            decode = (d["Decode"] as? PsArray)?.let { a -> DoubleArray(a.len) { ip.num(a[it]) } }
            if (mask) invertMask = decode != null && decode[0] > decode[1]
            // máscaras con /ImageType 3 o 4: se dibuja solo la imagen
        } else if (mask) {
            val src = ip.pop(); m = matOf(ip.pop()); invertMask = !ip.popBool(); h = ip.popInt(); w = ip.popInt()
            bpc = 1; sources = listOf(src); cs = ColorSpace.GRAY
        } else if (multiColorOp) {
            val ncomp = ip.popInt(); multi = ip.popBool()
            sources = if (multi) { val l = ArrayList<PsObj>(); repeat(ncomp) { l.add(0, ip.pop()) }; l } else listOf(ip.pop())
            m = matOf(ip.pop()); bpc = ip.popInt(); h = ip.popInt(); w = ip.popInt()
            cs = when (ncomp) { 1 -> ColorSpace.GRAY; 4 -> ColorSpace.CMYK; else -> ColorSpace.RGB }
        } else {
            val src = ip.pop(); m = matOf(ip.pop()); bpc = ip.popInt(); h = ip.popInt(); w = ip.popInt()
            sources = listOf(src); cs = ColorSpace.GRAY
        }
        if (w <= 0 || h <= 0) return
        val nc = if (mask) 1 else if (cs.family == "Indexed") 1 else cs.n
        val rowBytes = (w * bpc * (if (multi) 1 else nc) + 7) / 8
        val planes = if (multi) nc else 1
        val big = w.toLong() * h > 16_000_000L || w.toLong() * h > pixelBudget
        // JPEG (DCTDecode): se decodifica completo en la plataforma
        val s0 = sources[0]
        if (!mask && s0 is StreamPsFile && s0.isDct) {
            val bytes = PsFileInputStream(s0).readBytes()
            emitImage(null, bytes, w, h, m); return
        }
        val data = Array(planes) { ByteArray(if (big) 0 else rowBytes * h) }
        val readers = sources.map { dataReader(it) }
        val total = rowBytes.toLong() * h
        for (p in 0 until planes) {
            var got = 0L; val rd = readers[min(p, readers.size - 1)]
            while (got < total) {
                val chunk = rd() ?: break
                if (!big) { val n = min(chunk.size.toLong(), total - got).toInt(); System.arraycopy(chunk, 0, data[p], got.toInt(), n) }
                got += chunk.size
            }
        }
        if (big) return
        pixelBudget -= w.toLong() * h
        val px = IntArray(w * h)
        val maxV = (1 shl bpc) - 1
        fun sample(plane: Int, row: Int, idx: Int): Int {
            val buf = data[plane]; val bit = idx * bpc; val base = row * rowBytes
            return when (bpc) {
                8 -> buf[base + idx].toInt() and 0xFF
                16 -> buf[base + idx * 2].toInt() and 0xFF
                12 -> { val o = base + bit / 8; val v = ((buf[o].toInt() and 0xFF) shl 8) or (buf.getOrElse(o + 1) { 0 }.toInt() and 0xFF); if (bit % 8 == 0) v shr 4 else v and 0xFFF }
                else -> ((buf[base + bit / 8].toInt() and 0xFF) shr (8 - bpc - bit % 8)) and maxV
            }
        }
        val mv = if (bpc == 16) 255 else maxV
        val comps = DoubleArray(nc)
        val cache = HashMap<Long, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (mask) {
                val v = sample(0, y, x)
                val paint = if (invertMask) v == 1 else v == 0
                px[y * w + x] = if (paint) gs.argb else 0
                continue
            }
            var keyL = 0L
            for (k in 0 until nc) {
                val v = if (multi) sample(k, y, x) else sample(0, y, x * nc + k)
                keyL = keyL * 65537 + v
                val lo = decode?.getOrNull(2 * k) ?: 0.0
                val hi = decode?.getOrNull(2 * k + 1) ?: if (cs.family == "Indexed") maxV.toDouble() else 1.0
                comps[k] = lo + v * (hi - lo) / mv
            }
            px[y * w + x] = if (nc == 1 && cs.family == "DeviceGray") toArgb(cs, comps)
            else cache.getOrPut(keyL) { toArgb(cs, comps) }
        }
        emitImage(px, null, w, h, m)
    }

    private fun emitImage(px: IntArray?, jpeg: ByteArray?, w: Int, h: Int, imageMatrix: DoubleArray) {
        // la matriz de imagen lleva el espacio de usuario al espacio de la imagen (w×h)
        val inv = try { Mx.inv(imageMatrix) } catch (e: PsError) { return }
        val unitToImage = doubleArrayOf(w.toDouble(), 0.0, 0.0, h.toDouble(), 0.0, 0.0)
        val toDev = Mx.mul(Mx.mul(unitToImage, inv), gs.ctm)
        ops.add(ImageOp(px, jpeg, w, h, toDev, gs.clip))
    }

    /** Devuelve una función que entrega bloques de datos de la fuente (procedimiento, cadena o archivo). */
    private fun dataReader(src: PsObj): () -> ByteArray? = when {
        src is PsArray && src.isExec -> {
            {
                ip.call(src)
                val s = ip.pop() as? PsString
                if (s == null || s.len == 0) null else s.bytes()
            }
        }
        src is PsString -> { { if (src.len == 0) null else src.bytes() } }
        src is PsFile -> {
            {
                val b = ByteArray(8192); var n = 0
                while (n < b.size) { val c = src.read(); if (c < 0) break; b[n++] = c.toByte() }
                if (n == 0) null else b.copyOf(n)
            }
        }
        else -> { { null } }
    }

    // ------------------------------------------------------------ instalación
    fun install() {
        val i = ip
        fun op(n: String, f: () -> Unit) = i.op(n) { f() }

        op("gsave") { gsave() }
        op("grestore") { grestore() }
        op("grestoreall") { restoreTo(0) }
        op("initgraphics") { gs = GState() }
        op("gstate") { i.push(PsGState(gs.copy())) }
        op("currentgstate") { val g = i.pop() as PsGState; g.gs.copyFrom(gs); i.push(g) }
        op("setgstate") { val g = i.pop() as PsGState; gs = g.gs.copy() }

        op("newpath") { newPath() }
        op("moveto") { val y = i.popNum(); val x = i.popNum(); moveTo(x, y) }
        op("rmoveto") { val dy = i.popNum(); val dx = i.popNum(); needCp(); val (ux, uy) = devToUser(gs.cpx, gs.cpy); moveTo(ux + dx, uy + dy) }
        op("lineto") { val y = i.popNum(); val x = i.popNum(); lineTo(x, y) }
        op("rlineto") { val dy = i.popNum(); val dx = i.popNum(); needCp(); val (ux, uy) = devToUser(gs.cpx, gs.cpy); lineTo(ux + dx, uy + dy) }
        op("curveto") { val y3 = i.popNum(); val x3 = i.popNum(); val y2 = i.popNum(); val x2 = i.popNum(); val y1 = i.popNum(); val x1 = i.popNum(); curveTo(x1, y1, x2, y2, x3, y3) }
        op("rcurveto") {
            val y3 = i.popNum(); val x3 = i.popNum(); val y2 = i.popNum(); val x2 = i.popNum(); val y1 = i.popNum(); val x1 = i.popNum()
            needCp(); val (ux, uy) = devToUser(gs.cpx, gs.cpy); curveTo(ux + x1, uy + y1, ux + x2, uy + y2, ux + x3, uy + y3)
        }
        op("closepath") { closePath() }
        op("arc") { val a2 = i.popNum(); val a1 = i.popNum(); val r = i.popNum(); val y = i.popNum(); val x = i.popNum(); arc(x, y, r, a1, a2, true) }
        op("arcn") { val a2 = i.popNum(); val a1 = i.popNum(); val r = i.popNum(); val y = i.popNum(); val x = i.popNum(); arc(x, y, r, a1, a2, false) }
        fun arct(push: Boolean) {
            val r = i.popNum(); val y2 = i.popNum(); val x2 = i.popNum(); val y1 = i.popNum(); val x1 = i.popNum()
            needCp(); val (x0, y0) = devToUser(gs.cpx, gs.cpy)
            val ax = x0 - x1; val ay = y0 - y1; val bx = x2 - x1; val by = y2 - y1
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            val ang = acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0))
            if (la == 0.0 || lb == 0.0 || ang == 0.0 || ang.isNaN()) { lineTo(x1, y1); if (push) repeat(4) { i.push(PsReal(if (it % 2 == 0) x1 else y1)) }; return }
            val d = r / tan(ang / 2)
            val t1x = x1 + ax / la * d; val t1y = y1 + ay / la * d; val t2x = x1 + bx / lb * d; val t2y = y1 + by / lb * d
            lineTo(t1x, t1y)
            val k = 4.0 / 3.0 * tan((PI - ang) / 4)
            val f = k * r / max(d, 1e-9)
            curveTo(t1x + (x1 - t1x) * f, t1y + (y1 - t1y) * f, t2x + (x1 - t2x) * f, t2y + (y1 - t2y) * f, t2x, t2y)
            if (push) { i.push(PsReal(t1x)); i.push(PsReal(t1y)); i.push(PsReal(t2x)); i.push(PsReal(t2y)) }
        }
        op("arct") { arct(false) }
        op("arcto") { arct(true) }
        op("currentpoint") { needCp(); val (x, y) = devToUser(gs.cpx, gs.cpy); i.push(PsReal(x)); i.push(PsReal(y)) }
        op("flattenpath") { }
        op("reversepath") { }
        op("strokepath") { }
        op("clippath") {
            val c = gs.clip
            gs.path = c?.path?.copy() ?: PsPath().also { p -> p.moveTo(-1e5, -1e5); p.lineTo(1e5, -1e5); p.lineTo(1e5, 1e5); p.lineTo(-1e5, 1e5); p.close() }
            gs.hasCp = true
        }
        op("pathbbox") {
            val b = gs.path.bounds() ?: throw PsError("nocurrentpoint")
            val inv = Mx.inv(gs.ctm)
            val xs = doubleArrayOf(Mx.tx(inv, b[0], b[1]), Mx.tx(inv, b[2], b[1]), Mx.tx(inv, b[0], b[3]), Mx.tx(inv, b[2], b[3]))
            val ys = doubleArrayOf(Mx.ty(inv, b[0], b[1]), Mx.ty(inv, b[2], b[1]), Mx.ty(inv, b[0], b[3]), Mx.ty(inv, b[2], b[3]))
            i.push(PsReal(xs.min())); i.push(PsReal(ys.min())); i.push(PsReal(xs.max())); i.push(PsReal(ys.max()))
        }
        op("pathforall") { repeat(4) { i.pop() } }
        op("upath") { i.pop(); i.push(PsArray(emptyList(), true)) }
        op("initclip") { gs.clip = null }
        op("clip") { clip(false) }
        op("eoclip") { clip(true) }
        op("rectclip") { gs.path = PsPath(); rects { x, y, w, h -> rectPath(x, y, w, h) }; clip(false); newPath() }
        op("clipsave") { clipStack.add(gs.clip) }
        op("cliprestore") { if (clipStack.isNotEmpty()) gs.clip = clipStack.removeAt(clipStack.size - 1) }

        op("fill") { emitFill(false) }
        op("eofill") { emitFill(true) }
        op("stroke") { emitStroke() }
        op("rectfill") { val save = gs.path; gs.path = PsPath(); rects { x, y, w, h -> rectPath(x, y, w, h) }; emitFill(false); gs.path = save }
        op("rectstroke") {
            if (i.peek() is PsArray && (i.peek() as PsArray).len == 6 && i.peek(1) !is PsInt) i.pop()
            val save = gs.path; gs.path = PsPath(); rects { x, y, w, h -> rectPath(x, y, w, h) }; emitStroke(); gs.path = save
        }
        op("erasepage") { }
        op("showpage") { }
        op("copypage") { }
        op("shfill") { val sh = i.popDict(); shade(sh, gs.ctm.copyOf(), gs.clip) }
        op("image") { imageOp(mask = false, multiColorOp = false) }
        op("colorimage") { imageOp(mask = false, multiColorOp = true) }
        op("imagemask") { imageOp(mask = true, multiColorOp = false) }

        // estado de línea
        op("setlinewidth") { gs.lineWidth = i.popNum() }
        op("currentlinewidth") { i.push(PsReal(gs.lineWidth)) }
        op("setlinecap") { gs.cap = i.popInt() }
        op("currentlinecap") { i.pushInt(gs.cap) }
        op("setlinejoin") { gs.join = i.popInt() }
        op("currentlinejoin") { i.pushInt(gs.join) }
        op("setmiterlimit") { gs.miter = i.popNum() }
        op("currentmiterlimit") { i.push(PsReal(gs.miter)) }
        op("setdash") {
            gs.dashPhase = i.popNum(); val a = i.popArray()
            gs.dash = if (a.len == 0) null else DoubleArray(a.len) { i.num(a[it]) }.takeIf { d -> d.any { it > 0 } }
        }
        op("currentdash") { i.push(PsArray(gs.dash?.map { PsReal(it) as PsObj } ?: emptyList())); i.push(PsReal(gs.dashPhase)) }
        for (n in listOf("setflat", "setsmoothness", "setstrokeadjust", "setoverprint", "setcolorrendering", "settransfer",
            "setblackgeneration", "setundercolorremoval", "sethalftone", "setcolorspace_dummy", "setrenderingintent", "setalphaisshape", "setopacityalpha", "setshapealpha", "setfillconstantalpha", "setstrokeconstantalpha", "setblendmode", "setoverprintmode", "setaccuratecurves")) op(n) { i.pop() }
        op("setscreen") { i.pop(); i.pop(); i.pop() }
        op("setcolorscreen") { repeat(12) { i.pop() } }
        op("setcolortransfer") { repeat(4) { i.pop() } }
        op("currentflat") { i.push(PsReal(1.0)) }
        op("currentsmoothness") { i.push(PsReal(0.02)) }
        op("currentstrokeadjust") { i.pushBool(false) }
        op("currentoverprint") { i.pushBool(false) }
        op("currenttransfer") { i.push(PsArray(emptyList(), true)) }
        op("currentblackgeneration") { i.push(PsArray(emptyList(), true)) }
        op("currentundercolorremoval") { i.push(PsArray(emptyList(), true)) }
        op("currentcolortransfer") { repeat(4) { i.push(PsArray(emptyList(), true)) } }
        op("currentscreen") { i.push(PsReal(60.0)); i.push(PsReal(45.0)); i.push(PsArray(emptyList(), true)) }
        op("currenthalftone") { i.push(PsDict().apply { put("HalftoneType", PsInt(1)) }) }
        op("currentcolorrendering") { i.push(PsDict()) }

        // color
        op("setgray") { simpleColor(ColorSpace.GRAY, "DeviceGray", doubleArrayOf(i.popNum())) }
        op("setrgbcolor") { val b = i.popNum(); val g = i.popNum(); val r = i.popNum(); simpleColor(ColorSpace.RGB, "DeviceRGB", doubleArrayOf(r, g, b)) }
        op("setcmykcolor") { val k = i.popNum(); val y = i.popNum(); val m = i.popNum(); val c = i.popNum(); simpleColor(ColorSpace.CMYK, "DeviceCMYK", doubleArrayOf(c, m, y, k)) }
        op("sethsbcolor") {
            val b = i.popNum(); val s = i.popNum(); val h = i.popNum()
            val hh = (h * 6) % 6; val f = hh - floor(hh); val p = b * (1 - s); val q = b * (1 - s * f); val t = b * (1 - s * (1 - f))
            val (r, g, bb) = when (floor(hh).toInt()) { 0 -> Triple(b, t, p); 1 -> Triple(q, b, p); 2 -> Triple(p, b, t); 3 -> Triple(p, q, b); 4 -> Triple(t, p, b); else -> Triple(b, p, q) }
            simpleColor(ColorSpace.RGB, "DeviceRGB", doubleArrayOf(r, g, bb))
        }
        op("currentgray") { val c = gs.argb; i.push(PsReal((0.3 * (c shr 16 and 255) + 0.59 * (c shr 8 and 255) + 0.11 * (c and 255)) / 255)) }
        op("currentrgbcolor") { val c = gs.argb; i.push(PsReal((c shr 16 and 255) / 255.0)); i.push(PsReal((c shr 8 and 255) / 255.0)); i.push(PsReal((c and 255) / 255.0)) }
        op("currentcmykcolor") {
            if (gs.space.family == "DeviceCMYK") gs.comps.forEach { i.push(PsReal(it)) }
            else { val c = gs.argb; i.push(PsReal(1 - (c shr 16 and 255) / 255.0)); i.push(PsReal(1 - (c shr 8 and 255) / 255.0)); i.push(PsReal(1 - (c and 255) / 255.0)); i.push(PsReal(0.0)) }
        }
        op("currenthsbcolor") { i.push(PsReal(0.0)); i.push(PsReal(0.0)); i.push(PsReal(((gs.argb and 255) / 255.0))) }
        op("setcolorspace") { setSpace(i.pop()) }
        op("currentcolorspace") { val o = gs.csObj; i.push(if (o is PsArray) o else PsArray(listOf(o))) }
        op("setcolor") {
            if (gs.space.family == "Pattern") {
                val p = i.pop() as? PsDict ?: throw PsError("typecheck")
                val base = gs.space.base
                if (base != null && (p["PaintType"] as? PsInt)?.v == 2) {
                    val c = DoubleArray(base.n); for (k in base.n - 1 downTo 0) c[k] = i.popNum(); gs.comps = c
                }
                gs.pattern = p
            } else {
                val n = gs.space.n; val c = DoubleArray(n); for (k in n - 1 downTo 0) c[k] = i.popNum(); setColor(c)
            }
        }
        op("currentcolor") { gs.comps.forEach { i.push(PsReal(it)) } }
        op("setpattern") {
            val p = i.pop() as? PsDict ?: throw PsError("typecheck")
            if ((p["PaintType"] as? PsInt)?.v == 2) {
                val base = gs.space
                val n = base.n; val c = DoubleArray(n); for (k in n - 1 downTo 0) c[k] = i.popNum()
                gs.space = ColorSpace("Pattern", 1, base = base); gs.comps = c
            } else gs.space = ColorSpace.PATTERN
            gs.csObj = PsName("Pattern", false); gs.pattern = p
        }
        op("makepattern") {
            val m = matOf(i.pop()); val p = i.popDict()
            val copy = PsDict(p.map.size + 2); copy.map.putAll(p.map)
            copy.put("_ctm", arr(*Mx.mul(m, gs.ctm)))
            i.push(copy)
        }
        op("findcmykcustomcolor") { val name = i.pop(); val k = i.popNum(); val y = i.popNum(); val m = i.popNum(); val c = i.popNum(); i.push(PsArray(listOf(PsReal(c), PsReal(m), PsReal(y), PsReal(k), name))) }
        op("setcustomcolor") {
            val t = i.popNum(); val a = i.popArray()
            simpleColor(ColorSpace.CMYK, "DeviceCMYK", doubleArrayOf(i.num(a[0]) * t, i.num(a[1]) * t, i.num(a[2]) * t, i.num(a[3]) * t))
        }
        op("findrgbcustomcolor") { val name = i.pop(); val b = i.popNum(); val g = i.popNum(); val r = i.popNum(); i.push(PsArray(listOf(PsReal(r), PsReal(g), PsReal(b), name))) }

        // matrices
        op("matrix") { i.push(arr(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)) }
        op("identmatrix") { i.push(storeMat(i.popArray(), doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))) }
        op("currentmatrix") { i.push(storeMat(i.popArray(), gs.ctm)) }
        op("defaultmatrix") { i.push(storeMat(i.popArray(), doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))) }
        op("initmatrix") { gs.ctm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0) }
        op("setmatrix") { gs.ctm = matOf(i.pop()) }
        fun matOp(name: String, build: (DoubleArray) -> DoubleArray, nArgs: Int) = i.op(name) {
            if (i.peek() is PsArray) {
                val a = i.popArray(); val args = DoubleArray(nArgs); for (k in nArgs - 1 downTo 0) args[k] = i.popNum()
                i.push(storeMat(a, build(args)))
            } else {
                val args = DoubleArray(nArgs); for (k in nArgs - 1 downTo 0) args[k] = i.popNum()
                gs.ctm = Mx.mul(build(args), gs.ctm)
            }
        }
        matOp("translate", { doubleArrayOf(1.0, 0.0, 0.0, 1.0, it[0], it[1]) }, 2)
        matOp("scale", { doubleArrayOf(it[0], 0.0, 0.0, it[1], 0.0, 0.0) }, 2)
        matOp("rotate", { val r = Math.toRadians(it[0]); doubleArrayOf(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0) }, 1)
        op("concat") { gs.ctm = Mx.mul(matOf(i.pop()), gs.ctm) }
        op("concatmatrix") { val dst = i.popArray(); val b = matOf(i.pop()); val a = matOf(i.pop()); i.push(storeMat(dst, Mx.mul(a, b))) }
        op("invertmatrix") { val dst = i.popArray(); val a = matOf(i.pop()); i.push(storeMat(dst, Mx.inv(a))) }
        fun xform(name: String, f: (DoubleArray, Double, Double) -> Pair<Double, Double>, inverse: Boolean, delta: Boolean) = i.op(name) {
            var m = gs.ctm
            if (i.peek() is PsArray) m = matOf(i.pop())
            if (inverse) m = Mx.inv(m)
            if (delta) m = doubleArrayOf(m[0], m[1], m[2], m[3], 0.0, 0.0)
            val y = i.popNum(); val x = i.popNum(); val (a, b) = f(m, x, y); i.push(PsReal(a)); i.push(PsReal(b))
        }
        val tf: (DoubleArray, Double, Double) -> Pair<Double, Double> = { m, x, y -> Pair(Mx.tx(m, x, y), Mx.ty(m, x, y)) }
        xform("transform", tf, false, false); xform("itransform", tf, true, false)
        xform("dtransform", tf, false, true); xform("idtransform", tf, true, true)

        // formularios
        op("execform") {
            val f = i.popDict(); val proc = f["PaintProc"] ?: return@op
            gsave()
            (f["Matrix"] as? PsArray)?.let { gs.ctm = Mx.mul(matOf(it), gs.ctm) }
            (f["BBox"] as? PsArray)?.let { b -> newPath(); rectPath(i.num(b[0]), i.num(b[1]), i.num(b[2]) - i.num(b[0]), i.num(b[3]) - i.num(b[1])); clip(false); newPath() }
            try { i.push(f); i.call(proc) } finally { grestore() }
        }
    }

    private val clipStack = ArrayList<ClipNode?>()
}
