package com.anddesign.vectorviewer.ps

/**
 * Operadores del formato Adobe Illustrator 88/3–8 (m, L, c, v, y, f, S, B, k, K, x, X, q, Q, *u, *U, ...).
 * Los archivos .ai antiguos y muchos EPS de Illustrator los usan; si el archivo trae su propio procset,
 * sus definiciones tienen prioridad porque quedan más arriba en la pila de diccionarios.
 */
object AiLegacy {

    fun install(ip: PsInterpreter) {
        val d = PsDict(128)
        val g = ip.gfx
        var fillArgb = 0xFF000000.toInt()
        var strokeArgb = 0xFF000000.toInt()
        var clipNext = false
        var compound = 0
        var pendingFill = false; var pendingStroke = false; var pendingEo = false
        var evenOdd = false

        fun op(n: String, f: () -> Unit) { d.put(n, PsOperator(n) { f() }) }
        fun cmyk(c: Double, m: Double, y: Double, k: Double) = g.cmykToArgb(c, m, y, k)

        fun paint(close: Boolean, fill: Boolean, stroke: Boolean) {
            if (close) g.closePath()
            if (compound > 0) {
                // dentro de un trazado compuesto: se acumulan los subtrazados y se pinta en *U
                pendingFill = pendingFill || fill; pendingStroke = pendingStroke || stroke; pendingEo = evenOdd
                return
            }
            val path = g.gs.path
            if (fill) { g.gs.argb = fillArgb; g.gs.pattern = null; g.gs.path = path.copy(); g.gs.hasCp = true; g.emitFill(evenOdd) }
            if (stroke) { g.gs.argb = strokeArgb; g.gs.path = path.copy(); g.gs.hasCp = true; g.emitStroke() }
            if (clipNext) { g.gs.path = path; g.clip(evenOdd); clipNext = false }
            g.newPath()
        }

        op("m") { val y = ip.popNum(); val x = ip.popNum(); g.moveTo(x, y) }
        for (n in listOf("l", "L")) op(n) { val y = ip.popNum(); val x = ip.popNum(); g.lineTo(x, y) }
        for (n in listOf("c", "C")) op(n) {
            val y3 = ip.popNum(); val x3 = ip.popNum(); val y2 = ip.popNum(); val x2 = ip.popNum(); val y1 = ip.popNum(); val x1 = ip.popNum()
            g.curveTo(x1, y1, x2, y2, x3, y3)
        }
        for (n in listOf("v", "V")) op(n) {
            val y3 = ip.popNum(); val x3 = ip.popNum(); val y2 = ip.popNum(); val x2 = ip.popNum()
            val (x1, y1) = g.devToUser(g.gs.cpx, g.gs.cpy); g.curveTo(x1, y1, x2, y2, x3, y3)
        }
        for (n in listOf("y", "Y")) op(n) {
            val y3 = ip.popNum(); val x3 = ip.popNum(); val y1 = ip.popNum(); val x1 = ip.popNum()
            g.curveTo(x1, y1, x3, y3, x3, y3)
        }
        op("N") { paint(false, false, false) }; op("n") { paint(true, false, false) }
        op("F") { paint(false, true, false) }; op("f") { paint(true, true, false) }
        op("S") { paint(false, false, true) }; op("s") { paint(true, false, true) }
        op("B") { paint(false, true, true) }; op("b") { paint(true, true, true) }
        op("H") { }; op("h") { g.closePath() }
        op("W") { clipNext = true }
        op("*u") { compound++; pendingFill = false; pendingStroke = false }
        op("*U") {
            compound = (compound - 1).coerceAtLeast(0)
            if (compound == 0) { evenOdd = pendingEo; paint(false, pendingFill, pendingStroke); pendingFill = false; pendingStroke = false }
        }
        op("D") { evenOdd = ip.popInt() == 1 }
        op("u") { }; op("U") { }
        op("q") { g.gsave() }; op("Q") { g.grestore() }
        op("A") { ip.pop() }; op("i") { ip.pop() }; op("O") { ip.pop() }; op("R") { ip.pop() }
        op("XR") { ip.pop() }; op("Ap") { ip.pop() }; op("Ar") { ip.pop() }
        op("g") { val v = ip.popNum(); fillArgb = g.rgb(v, v, v) }
        op("G") { val v = ip.popNum(); strokeArgb = g.rgb(v, v, v) }
        op("k") { val k = ip.popNum(); val y = ip.popNum(); val m = ip.popNum(); val c = ip.popNum(); fillArgb = cmyk(c, m, y, k) }
        op("K") { val k = ip.popNum(); val y = ip.popNum(); val m = ip.popNum(); val c = ip.popNum(); strokeArgb = cmyk(c, m, y, k) }
        fun custom(stroke: Boolean) {
            // c m y k (nombre) tinte x   — en AI 8 puede venir también con tipo de color: c m y k r g b (nombre) tinte tipo X
            val tint = ip.popNum(); var name = ip.pop()
            if (name is PsInt || name is PsReal) { name = ip.pop() } // variante con "tipo" al final
            val nums = ArrayList<Double>()
            while (ip.ostack.isNotEmpty() && (ip.peek() is PsInt || ip.peek() is PsReal) && nums.size < 7) nums.add(0, ip.popNum())
            val argb = if (nums.size >= 4) cmyk(nums[0] * tint, nums[1] * tint, nums[2] * tint, nums[3] * tint) else g.rgb(1 - tint, 1 - tint, 1 - tint)
            if (stroke) strokeArgb = argb else fillArgb = argb
        }
        op("x") { custom(false) }; op("X") { custom(true) }
        op("Xa") { val b = ip.popNum(); val gg = ip.popNum(); val r = ip.popNum(); fillArgb = g.rgb(r, gg, b) }
        op("XA") { val b = ip.popNum(); val gg = ip.popNum(); val r = ip.popNum(); strokeArgb = g.rgb(r, gg, b) }
        op("w") { g.gs.lineWidth = ip.popNum() }
        op("j") { g.gs.join = ip.popInt() }
        op("J") { g.gs.cap = ip.popInt() }
        op("M") { g.gs.miter = ip.popNum() }
        op("d") { g.gs.dashPhase = ip.popNum(); val a = ip.popArray(); g.gs.dash = if (a.len == 0) null else DoubleArray(a.len) { ip.num(a[it]) } }
        op("Lb") { repeat(10) { if (ip.ostack.isNotEmpty()) ip.pop() } }
        op("LB") { }; op("Ln") { ip.pop() }
        // Por debajo de userdict: el procset del archivo, si viene, tiene prioridad.
        ip.dstack.add(2, d); ip.baseDepth++
    }
}
