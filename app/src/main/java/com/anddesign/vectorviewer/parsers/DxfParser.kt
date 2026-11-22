package com.anddesign.vectorviewer.parsers

import android.graphics.Path
import android.graphics.RectF
import com.anddesign.vectorviewer.render.DocumentException
import com.anddesign.vectorviewer.render.PathDocument
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * DXF ASCII (AutoCAD, plotters de corte, láser, CNC).
 * Soporta LINE, CIRCLE, ARC, ELLIPSE, LWPOLYLINE y POLYLINE (con arcos "bulge") y SPLINE (NURBS no racional).
 * Respeta $INSUNITS; si no viene, asume milímetros.
 */
object DxfParser {

    private class Entity {
        var type = ""
        val v10 = ArrayList<Double>()
        val v20 = ArrayList<Double>()
        val v11 = ArrayList<Double>()
        val v21 = ArrayList<Double>()
        val v40 = ArrayList<Double>()
        val bulges = HashMap<Int, Double>()
        var v41 = Double.NaN
        var v42 = Double.NaN
        var v50 = 0.0
        var v51 = 360.0
        var flags = 0
        var degree = 3

        fun reset(t: String) {
            type = t
            v10.clear(); v20.clear(); v11.clear(); v21.clear(); v40.clear(); bulges.clear()
            v41 = Double.NaN; v42 = Double.NaN; v50 = 0.0; v51 = 360.0; flags = 0; degree = 3
        }

        fun add(code: Int, value: String) {
            val d = value.toDoubleOrNull()
            when (code) {
                10 -> if (d != null) v10.add(d)
                20 -> if (d != null) v20.add(d)
                11 -> if (d != null) v11.add(d)
                21 -> if (d != null) v21.add(d)
                40 -> if (d != null) v40.add(d)
                41 -> if (d != null) v41 = d
                42 -> if (d != null) {
                    if (type == "LWPOLYLINE") {
                        if (v10.isNotEmpty()) bulges[v10.size - 1] = d
                    } else {
                        v42 = d
                    }
                }
                50 -> if (d != null) v50 = d
                51 -> if (d != null) v51 = d
                70 -> flags = value.toIntOrNull() ?: flags
                71 -> degree = value.toIntOrNull() ?: degree
            }
        }

        fun x(i: Int = 0) = v10.getOrElse(i) { 0.0 }
        fun y(i: Int = 0) = v20.getOrElse(i) { 0.0 }
    }

    fun parse(text: String): PathDocument {
        if (text.startsWith("AutoCAD Binary DXF")) {
            throw DocumentException("DXF binario aún no compatible. Expórtalo como DXF ASCII.")
        }
        val lines = text.lines()
        val path = Path()
        val ent = Entity()
        var section = ""
        var headerVar = ""
        var insUnits = 0
        val poly = ArrayList<DoubleArray>()
        var polyActive = false
        var polyClosed = false
        val skipped = sortedSetOf<String>()
        var drawn = 0

        fun flush() {
            when (ent.type) {
                "LINE" -> if (ent.v10.isNotEmpty() && ent.v11.isNotEmpty()) {
                    path.moveTo(ent.x().toFloat(), ent.y().toFloat())
                    path.lineTo(ent.v11[0].toFloat(), ent.v21.getOrElse(0) { 0.0 }.toFloat())
                    drawn++
                }
                "CIRCLE" -> ent.v40.firstOrNull()?.let { r ->
                    path.addCircle(ent.x().toFloat(), ent.y().toFloat(), r.toFloat(), Path.Direction.CCW)
                    drawn++
                }
                "ARC" -> ent.v40.firstOrNull()?.let { r ->
                    val cx = ent.x(); val cy = ent.y()
                    var sweep = ((ent.v51 - ent.v50) % 360.0 + 360.0) % 360.0
                    if (sweep == 0.0) sweep = 360.0
                    val rect = RectF((cx - r).toFloat(), (cy - r).toFloat(), (cx + r).toFloat(), (cy + r).toFloat())
                    if (sweep >= 360.0) {
                        path.addCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), Path.Direction.CCW)
                    } else {
                        path.addArc(rect, ent.v50.toFloat(), sweep.toFloat())
                    }
                    drawn++
                }
                "ELLIPSE" -> if (ent.v10.isNotEmpty() && ent.v11.isNotEmpty()) {
                    drawEllipse(path, ent)
                    drawn++
                }
                "LWPOLYLINE" -> if (ent.v10.size >= 2) {
                    val pts = ent.v10.indices.map { k -> doubleArrayOf(ent.x(k), ent.y(k), ent.bulges[k] ?: 0.0) }
                    drawPoly(path, pts, ent.flags and 1 != 0)
                    drawn++
                }
                "POLYLINE" -> {
                    polyActive = true
                    polyClosed = ent.flags and 1 != 0
                    poly.clear()
                }
                "VERTEX" -> if (polyActive && ent.v10.isNotEmpty() && ent.flags and 16 == 0) {
                    poly.add(doubleArrayOf(ent.x(), ent.y(), if (ent.v42.isNaN()) 0.0 else ent.v42))
                }
                "SEQEND" -> if (polyActive) {
                    if (poly.size >= 2) {
                        drawPoly(path, poly, polyClosed)
                        drawn++
                    }
                    polyActive = false
                }
                "SPLINE" -> if (drawSpline(path, ent)) drawn++
                "", "SECTION", "ENDSEC", "EOF", "POINT" -> Unit
                else -> skipped.add(ent.type)
            }
        }

        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull()
            if (code == null) {
                i++
                continue
            }
            val value = lines[i + 1].trim()
            i += 2
            if (code == 0) {
                if (section == "ENTITIES") flush()
                if (value == "ENDSEC") section = ""
                if (value == "EOF") break
                ent.reset(value)
                continue
            }
            when {
                ent.type == "SECTION" && code == 2 -> section = value
                section == "HEADER" -> {
                    if (code == 9) {
                        headerVar = value
                    } else if (headerVar == "\$INSUNITS" && code == 70) {
                        insUnits = value.toIntOrNull() ?: 0
                    }
                }
                section == "ENTITIES" -> ent.add(code, value)
            }
        }
        if (section == "ENTITIES") flush()

        if (drawn == 0) {
            throw DocumentException("No se encontraron trazos compatibles en el DXF")
        }

        val (unitToPt, unitName) = when (insUnits) {
            1 -> 72.0 to "pulgadas"
            2 -> 864.0 to "pies"
            5 -> 720.0 / 25.4 to "cm"
            6 -> 72000.0 / 25.4 to "metros"
            else -> 72.0 / 25.4 to "mm"
        }
        val extra = if (skipped.isNotEmpty()) " · omitidos: ${skipped.take(6).joinToString()}" else ""
        return PathDocument.fromRaw(path, unitToPt.toFloat(), flipY = true, note = "DXF en $unitName · $drawn trazos$extra")
    }

    private fun drawPoly(path: Path, pts: List<DoubleArray>, closed: Boolean) {
        if (pts.isEmpty()) return
        path.moveTo(pts[0][0].toFloat(), pts[0][1].toFloat())
        val n = pts.size
        val segments = if (closed) n else n - 1
        for (k in 0 until segments) {
            val a = pts[k]
            val b = pts[(k + 1) % n]
            if (abs(a[2]) < 1e-9) {
                path.lineTo(b[0].toFloat(), b[1].toFloat())
            } else {
                bulgeArc(path, a[0], a[1], b[0], b[1], a[2])
            }
        }
        if (closed) path.close()
    }

    /** Arco de polilínea: bulge = tan(ángulo/4), positivo = antihorario. */
    private fun bulgeArc(path: Path, x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double) {
        val dx = x2 - x1
        val dy = y2 - y1
        val d = hypot(dx, dy)
        if (d < 1e-12) return
        val theta = 4 * atan(bulge)
        val r = d / (2 * sin(theta / 2))
        val h = r * cos(theta / 2)
        val ux = dx / d
        val uy = dy / d
        val cx = (x1 + x2) / 2 - h * uy
        val cy = (y1 + y2) / 2 + h * ux
        val a0 = atan2(y1 - cy, x1 - cx)
        val rad = abs(r)
        val steps = max(4, (abs(theta) * 180 / PI / 5).toInt())
        for (s in 1..steps) {
            val a = a0 + theta * s / steps
            path.lineTo((cx + rad * cos(a)).toFloat(), (cy + rad * sin(a)).toFloat())
        }
    }

    private fun drawEllipse(path: Path, e: Entity) {
        val cx = e.x(); val cy = e.y()
        val mx = e.v11[0]; val my = e.v21.getOrElse(0) { 0.0 }
        val ratio = e.v40.firstOrNull() ?: 1.0
        val a0 = if (e.v41.isNaN()) 0.0 else e.v41
        var a1 = if (e.v42.isNaN()) 2 * PI else e.v42
        if (a1 <= a0) a1 += 2 * PI
        val steps = 96
        for (s in 0..steps) {
            val t = a0 + (a1 - a0) * s / steps
            val x = cx + mx * cos(t) - ratio * my * sin(t)
            val y = cy + my * cos(t) + ratio * mx * sin(t)
            if (s == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
    }

    private fun drawSpline(path: Path, e: Entity): Boolean {
        val cps = e.v10.indices.map { doubleArrayOf(e.x(it), e.y(it)) }
        val fit = e.v11.indices.map { doubleArrayOf(e.v11[it], e.v21.getOrElse(it) { 0.0 }) }
        val p = e.degree
        val knots = e.v40
        if (p >= 1 && cps.size >= p + 1 && knots.size == cps.size + p + 1) {
            val t0 = knots[p]
            val t1 = knots[cps.size]
            val steps = max(24, cps.size * 10)
            for (s in 0..steps) {
                val t = t0 + (t1 - t0) * s / steps
                val pt = deBoor(t, p, knots, cps)
                if (s == 0) path.moveTo(pt[0].toFloat(), pt[1].toFloat()) else path.lineTo(pt[0].toFloat(), pt[1].toFloat())
            }
            return true
        }
        val pts = if (fit.size >= 2) fit else cps
        if (pts.size < 2) return false
        path.moveTo(pts[0][0].toFloat(), pts[0][1].toFloat())
        for (k in 1 until pts.size) path.lineTo(pts[k][0].toFloat(), pts[k][1].toFloat())
        return true
    }

    private fun deBoor(t: Double, p: Int, k: List<Double>, c: List<DoubleArray>): DoubleArray {
        val n = c.size
        var s = p
        while (s < n - 1 && t >= k[s + 1]) s++
        val d = Array(p + 1) { j -> c[j + s - p].copyOf() }
        for (r in 1..p) {
            for (j in p downTo r) {
                val i = j + s - p
                val denom = k[i + p - r + 1] - k[i]
                val alpha = if (denom == 0.0) 0.0 else (t - k[i]) / denom
                d[j][0] = (1 - alpha) * d[j - 1][0] + alpha * d[j][0]
                d[j][1] = (1 - alpha) * d[j - 1][1] + alpha * d[j][1]
            }
        }
        return d[p]
    }
}
