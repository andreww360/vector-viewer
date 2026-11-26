package com.anddesign.vectorviewer.ps

/**
 * Fuentes: diccionarios de fuente, Type 3 (se ejecuta BuildChar/BuildGlyph: se dibuja en vector)
 * y texto con fuentes no incrustadas o Type 1/CFF (se dibuja con una fuente del sistema).
 */
object PsFonts {

    private val ascii = listOf(
        "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand", "quoteright", "parenleft", "parenright",
        "asterisk", "plus", "comma", "hyphen", "period", "slash", "zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "colon", "semicolon", "less", "equal", "greater", "question", "at",
    ) + ('A'..'Z').map { it.toString() } + listOf("bracketleft", "backslash", "bracketright", "asciicircum", "underscore", "quoteleft") +
        ('a'..'z').map { it.toString() } + listOf("braceleft", "bar", "braceright", "asciitilde")

    fun encoding(): PsArray {
        val a = Array<PsObj>(256) { PsName(".notdef", false) }
        for ((k, n) in ascii.withIndex()) a[32 + k] = PsName(n, false)
        return PsArray(a, 0, 256)
    }

    private val nameToChar: Map<String, Char> by lazy {
        val m = HashMap<String, Char>()
        for ((k, n) in ascii.withIndex()) m[n] = (32 + k).toChar()
        m["quoteright"] = '\''; m["quoteleft"] = '`'; m["quotesingle"] = '\''; m["grave"] = '`'
        m["endash"] = '–'; m["emdash"] = '—'; m["bullet"] = '•'; m["degree"] = '°'; m["copyright"] = '©'; m["registered"] = '®'
        m["trademark"] = '™'; m["ntilde"] = 'ñ'; m["Ntilde"] = 'Ñ'; m["aacute"] = 'á'; m["eacute"] = 'é'; m["iacute"] = 'í'
        m["oacute"] = 'ó'; m["uacute"] = 'ú'; m["Aacute"] = 'Á'; m["Eacute"] = 'É'; m["Iacute"] = 'Í'; m["Oacute"] = 'Ó'; m["Uacute"] = 'Ú'
        m["udieresis"] = 'ü'; m["Udieresis"] = 'Ü'; m["questiondown"] = '¿'; m["exclamdown"] = '¡'; m["minus"] = '−'
        m
    }

    fun install(ip: PsInterpreter) {
        val g = ip.gfx
        ip.systemdict.put("StandardEncoding", encoding())
        ip.systemdict.put("ISOLatin1Encoding", encoding())

        fun fallback(name: String): PsDict = PsDict(16).apply {
            put("FontName", PsName(name, false)); put("FontType", PsInt(1))
            put("FontMatrix", PsArray(listOf(PsReal(0.001), PsReal(0.0), PsReal(0.0), PsReal(0.001), PsReal(0.0), PsReal(0.0))))
            put("FontBBox", PsArray(listOf(PsInt(-100), PsInt(-250), PsInt(1000), PsInt(950))))
            put("Encoding", ip.systemdict["StandardEncoding"]!!); put("FID", PsFontId()); put("PaintType", PsInt(0))
            put("CharStrings", PsDict()); put("Private", PsDict())
        }

        fun scaled(f: PsDict, m: DoubleArray): PsDict {
            val d = PsDict(f.map.size + 2); d.map.putAll(f.map)
            val fm = (f["FontMatrix"] as? PsArray)?.let { a -> DoubleArray(6) { ip.num(a[it]) } } ?: doubleArrayOf(0.001, 0.0, 0.0, 0.001, 0.0, 0.0)
            d.put("FontMatrix", PsArray(Mx.mul(fm, m).map { PsReal(it) as PsObj }))
            d.put("FID", PsFontId())
            return d
        }

        ip.op("findfont") {
            val k = ip.pop(); val key = ip.key(k)
            val f = ip.fontDir[key] as? PsDict ?: fallback(ip.cvs(k)).also { ip.fontDir[key] = it }
            ip.push(f)
        }
        ip.op("definefont") {
            val f = ip.pop(); val k = ip.pop()
            if (f is PsDict && f["FID"] == null) f.put("FID", PsFontId())
            ip.fontDir[ip.key(k)] = f; ip.push(f)
        }
        ip.op("undefinefont") { ip.fontDir.map.remove(ip.key(ip.pop())) }
        ip.op("scalefont") { val s = ip.popNum(); val f = ip.popDict(); ip.push(scaled(f, doubleArrayOf(s, 0.0, 0.0, s, 0.0, 0.0))) }
        ip.op("makefont") { val m = ip.popArray(); val f = ip.popDict(); ip.push(scaled(f, DoubleArray(6) { ip.num(m[it]) })) }
        ip.op("setfont") { g.gs.font = ip.pop() as? PsDict }
        ip.op("currentfont") { ip.push(g.gs.font ?: fallback("Helvetica")) }
        ip.op("rootfont") { ip.push(g.gs.font ?: fallback("Helvetica")) }
        ip.op("selectfont") {
            val sz = ip.pop(); val k = ip.pop()
            val f = ip.fontDir[ip.key(k)] as? PsDict ?: fallback(ip.cvs(k)).also { ip.fontDir[ip.key(k)] = it }
            g.gs.font = if (sz is PsArray) scaled(f, DoubleArray(6) { ip.num(sz[it]) }) else { val s = ip.num(sz); scaled(f, doubleArrayOf(s, 0.0, 0.0, s, 0.0, 0.0)) }
        }
        ip.op("composefont") { val a = ip.pop(); ip.pop(); val k = ip.pop(); val f = fallback(ip.cvs(k)); ip.fontDir[ip.key(k)] = f; ip.push(f) }

        ip.op("show") { show(ip, ip.popString(), null) }
        ip.op("ashow") { val s = ip.popString(); val ay = ip.popNum(); val ax = ip.popNum(); show(ip, s, { _ -> Pair(ax, ay) }) }
        ip.op("widthshow") {
            val s = ip.popString(); val ch = ip.popInt(); val cy = ip.popNum(); val cx = ip.popNum()
            show(ip, s, { c -> if (c == ch) Pair(cx, cy) else Pair(0.0, 0.0) })
        }
        ip.op("awidthshow") {
            val s = ip.popString(); val ay = ip.popNum(); val ax = ip.popNum(); val ch = ip.popInt(); val cy = ip.popNum(); val cx = ip.popNum()
            show(ip, s, { c -> if (c == ch) Pair(cx + ax, cy + ay) else Pair(ax, ay) })
        }
        fun xyshow(kind: Int) {
            val nums = ip.pop(); val s = ip.popString()
            val list = (nums as? PsArray)?.toList()?.map { ip.num(it) } ?: emptyList()
            var idx = 0
            show(ip, s, null) { _ ->
                when (kind) {
                    0 -> Pair(list.getOrElse(idx++) { 0.0 }, 0.0)
                    1 -> Pair(0.0, list.getOrElse(idx++) { 0.0 })
                    else -> Pair(list.getOrElse(idx++) { 0.0 }, list.getOrElse(idx++) { 0.0 })
                }
            }
        }
        ip.op("xshow") { xyshow(0) }
        ip.op("yshow") { xyshow(1) }
        ip.op("xyshow") { xyshow(2) }
        ip.op("kshow") { val s = ip.popString(); ip.pop(); show(ip, s, null) }
        ip.op("cshow") { val s = ip.popString(); ip.pop(); show(ip, s, null) }
        ip.op("glyphshow") {
            val n = ip.cvs(ip.pop())
            showGlyphs(ip, intArrayOf(nameToChar[n]?.code ?: -1), listOf(n), null, null)
        }
        ip.op("stringwidth") {
            val s = ip.popString(); val f = g.gs.font ?: fallback("Helvetica")
            val fm = fontMatrix(ip, f)
            var wx = 0.0
            for (i in 0 until s.len) wx += approxWidth(s[i])
            ip.push(PsReal(fm[0] * wx)); ip.push(PsReal(fm[1] * wx))
        }
        ip.op("charpath") { ip.pop(); val s = ip.popString(); showGlyphs(ip, IntArray(s.len) { s[it] }, null, null, null, asPath = true) }
        ip.op("setcachedevice") { repeat(4) { ip.pop() }; val wy = ip.popNum(); val wx = ip.popNum(); charWidth = Pair(wx, wy) }
        ip.op("setcachedevice2") { repeat(8) { ip.pop() }; val wy = ip.popNum(); val wx = ip.popNum(); charWidth = Pair(wx, wy) }
        ip.op("setcharwidth") { val wy = ip.popNum(); val wx = ip.popNum(); charWidth = Pair(wx, wy) }
        ip.op("setcachelimit") { ip.pop() }
        ip.op("cachestatus") { repeat(7) { ip.pushInt(0) } }

        // Fuentes CFF/OpenType de Illustrator: se saltan los datos binarios
        val startData = PsOperator("StartData") {
            val n = ip.popInt(); ip.pop()
            val f = ip.fileStack.lastOrNull()
            if (f != null) repeat(n) { if (f.read() < 0) return@PsOperator }
        }
        ip.systemdict.put("StartData", startData)
        ip.resources.getOrPut("ProcSet") { PsDict() }["FontSetInit"] = PsDict().apply { put("StartData", startData) }
        val cid = PsDict()
        for (n in listOf("begincmap", "endcmap")) cid.put(n, PsOperator(n) { })
        cid.put("usecmap", PsOperator("usecmap") { ip.pop() })
        cid.put("usefont", PsOperator("usefont") { ip.pop() })
        for (n in listOf("codespacerange", "cidrange", "cidchar", "bfrange", "bfchar", "notdefrange", "notdefchar")) {
            cid.put("begin$n", PsOperator("begin$n") { ip.pop(); ip.push(PsMark) })
            cid.put("end$n", PsOperator("end$n") { ip.popToMark() })
        }
        cid.put("StartData", startData)
        cid.put("beginrearrangedfont", PsOperator("beginrearrangedfont") { ip.pop(); ip.pop(); ip.dstack.add(PsDict()) })
        cid.put("endrearrangedfont", PsOperator("endrearrangedfont") { if (ip.dstack.size > 3) ip.dstack.removeAt(ip.dstack.size - 1) })
        ip.resources["ProcSet"]!!["CIDInit"] = cid
    }

    private var charWidth: Pair<Double, Double>? = null

    private fun fontMatrix(ip: PsInterpreter, f: PsDict): DoubleArray =
        (f["FontMatrix"] as? PsArray)?.let { a -> DoubleArray(6) { ip.num(a[it]) } } ?: doubleArrayOf(0.001, 0.0, 0.0, 0.001, 0.0, 0.0)

    private fun approxWidth(c: Int): Double = when (c) {
        32 -> 278.0; in 48..57 -> 556.0; 'i'.code, 'l'.code, 'j'.code, '.'.code, ','.code, '\''.code -> 250.0
        'm'.code, 'w'.code -> 833.0; 'M'.code, 'W'.code -> 900.0; in 65..90 -> 667.0; else -> 540.0
    }

    /**
     * Dibuja un glifo en el punto actual. Devuelve el avance en espacio de glifo, o null si la fuente
     * no trae contornos (entonces se dibuja con una fuente del sistema).
     */
    private fun drawGlyph(ip: PsInterpreter, f: PsDict, fm: DoubleArray, code: Int, name: String, asPath: Boolean): Pair<Double, Double>? {
        val g = ip.gfx
        val type = (f["FontType"] as? PsInt)?.v ?: 1
        val ctmAt = g.gs.ctm.copyOf().also { it[4] = g.gs.cpx; it[5] = g.gs.cpy }
        val m = Mx.mul(fm, ctmAt)
        if (type == 3) {
            val build = f["BuildGlyph"] ?: f["BuildChar"] ?: return null
            charWidth = null
            g.gsave()
            g.gs.ctm = m
            g.newPath()
            val base = ip.ostack.size
            try {
                ip.push(f)
                if (f["BuildGlyph"] != null) ip.push(PsName(name, false)) else ip.pushInt(if (code >= 0) code else 32)
                ip.call(build)
            } catch (e: PsError) { } catch (e: PsExit) { }
            while (ip.ostack.size > base) ip.ostack.removeAt(ip.ostack.size - 1)
            g.grestore()
            return charWidth ?: Pair(0.0, 0.0)
        }
        val gl = try { Type1.glyph(ip, f, name) ?: Type1.glyph(ip, f, ".notdef")?.takeIf { name == ".notdef" } } catch (e: RuntimeException) { null } ?: return null
        if (!gl.path.isEmpty) {
            val dev = gl.path.transformed(m)
            if (asPath) appendTo(g.gs.path, dev)
            else if ((f["PaintType"] as? PsInt)?.v == 2) {
                g.ops.add(StrokeOp(gl.path, m, ip.num(f["StrokeWidth"] ?: PsInt(20)), 0, 0, 10.0, null, 0.0, g.gs.argb, g.gs.clip))
            } else g.ops.add(FillOp(dev, false, g.gs.argb, g.gs.clip))
        }
        return Pair(gl.wx, gl.wy)
    }

    private fun appendTo(dst: PsPath, src: PsPath) {
        src.forEach { op, p, i ->
            when (op) {
                PsPath.MOVE -> dst.moveTo(p[i], p[i + 1]); PsPath.LINE -> dst.lineTo(p[i], p[i + 1])
                PsPath.CURVE -> dst.curveTo(p[i], p[i + 1], p[i + 2], p[i + 3], p[i + 4], p[i + 5]); else -> dst.close()
            }
        }
    }

    fun showGlyphs(
        ip: PsInterpreter, codes: IntArray, names: List<String>?, extra: ((Int) -> Pair<Double, Double>)?,
        absolute: ((Int) -> Pair<Double, Double>)?, asPath: Boolean = false,
    ) {
        val g = ip.gfx
        val gs = g.gs
        if (!gs.hasCp) throw PsError("nocurrentpoint")
        val f = gs.font ?: throw PsError("invalidfont")
        val fm = fontMatrix(ip, f)
        val enc = f["Encoding"] as? PsArray
        val fontName = ip.cvs(f["FontName"] ?: PsName("Helvetica", false))
        for ((k, c) in codes.withIndex()) {
            val name = names?.get(k) ?: enc?.let { if (c in 0 until it.len) ip.cvs(it[c]) else null } ?: ".notdef"
            val startX = g.gs.cpx; val startY = g.gs.cpy
            var adv = drawGlyph(ip, f, fm, c, name, asPath)
            val m = Mx.mul(fm, g.gs.ctm)
            if (adv == null) {
                val ch = nameToChar[name] ?: if (c in 33..126 || c >= 160) c.toChar() else ' '
                if (ch != ' ' && !asPath) g.ops.add(TextOp(ch.toString(), m.copyOf().also { it[4] = startX; it[5] = startY }, fontName, g.gs.argb, g.gs.clip))
                adv = Pair(approxWidth(c), 0.0)
            }
            val ctm = g.gs.ctm
            var nx: Double; var ny: Double
            if (absolute != null) {
                val (ax, ay) = absolute(c); nx = startX + ctm[0] * ax + ctm[2] * ay; ny = startY + ctm[1] * ax + ctm[3] * ay
            } else {
                nx = startX + m[0] * adv.first + m[2] * adv.second; ny = startY + m[1] * adv.first + m[3] * adv.second
                if (extra != null) { val (ex, ey) = extra(c); nx += ctm[0] * ex + ctm[2] * ey; ny += ctm[1] * ex + ctm[3] * ey }
            }
            g.gs.cpx = nx; g.gs.cpy = ny; g.gs.hasCp = true
        }
    }

    private fun show(ip: PsInterpreter, s: PsString, extra: ((Int) -> Pair<Double, Double>)?, absolute: ((Int) -> Pair<Double, Double>)? = null) =
        showGlyphs(ip, IntArray(s.len) { s[it] }, null, extra, absolute)
}
