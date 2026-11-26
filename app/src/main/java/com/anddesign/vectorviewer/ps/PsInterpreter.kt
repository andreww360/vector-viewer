package com.anddesign.vectorviewer.ps

import kotlin.math.*

/**
 * Intérprete PostScript (nivel 2/3, subconjunto amplio) que produce una lista de dibujo ([PsGraphics.ops]).
 * No intenta ser un RIP completo: el objetivo es leer EPS de Illustrator, CorelDRAW, Inkscape, Cairo, etc.
 */
class PsInterpreter(
    private val maxOps: Long = 80_000_000L,
    private val deadlineMs: Long = 20_000L,
) {
    val ostack = ArrayList<PsObj>(256)
    val dstack = ArrayList<PsDict>()
    val systemdict = PsDict(512)
    val globaldict = PsDict(64)
    val userdict = PsDict(256)
    val errorDollar = PsDict(16)
    val fontDir = PsDict(64)
    val resources = HashMap<String, PsDict>()
    val fileStack = ArrayList<PsFile>()
    val lexer = PsLexer { n -> lookup(n) ?: PsName(n, true) }
    val gfx = PsGraphics(this)

    /** Primeros errores (para diagnóstico). */
    val errors = ArrayList<String>()
    var errorCount = 0
    private var opCount = 0L
    private var depth = 0
    private var startMs = 0L
    var global = false
    var baseDepth = 3

    init {
        dstack.add(systemdict); dstack.add(globaldict); dstack.add(userdict)
        installCore()
        gfx.install()
        PsFonts.install(this)
    }

    // ---------------------------------------------------------------- pila
    fun push(o: PsObj) { ostack.add(o) }
    fun pushInt(i: Int) = push(PsInt(i))
    fun pushNum(d: Double) = push(if (d == floor(d) && abs(d) < 2.0e9) PsReal(d) else PsReal(d))
    fun pushBool(b: Boolean) = push(PsBool.of(b))

    fun pop(): PsObj = if (ostack.isEmpty()) throw PsError("stackunderflow") else ostack.removeAt(ostack.size - 1)
    fun peek(i: Int = 0): PsObj = if (ostack.size <= i) throw PsError("stackunderflow") else ostack[ostack.size - 1 - i]

    fun popNum(): Double = when (val o = pop()) { is PsInt -> o.v.toDouble(); is PsReal -> o.v; else -> throw PsError("typecheck") }
    fun popInt(): Int = when (val o = pop()) { is PsInt -> o.v; is PsReal -> o.v.toInt(); else -> throw PsError("typecheck") }
    fun popBool(): Boolean = (pop() as? PsBool ?: throw PsError("typecheck")).v
    fun popDict(): PsDict = pop() as? PsDict ?: throw PsError("typecheck")
    fun popArray(): PsArray = pop() as? PsArray ?: throw PsError("typecheck")
    fun popString(): PsString = pop() as? PsString ?: throw PsError("typecheck")
    fun popProc(): PsObj = pop()

    fun num(o: PsObj): Double = when (o) { is PsInt -> o.v.toDouble(); is PsReal -> o.v; else -> throw PsError("typecheck") }

    // ---------------------------------------------------------------- diccionarios
    fun key(o: PsObj): Any = when (o) {
        is PsName -> o.name
        is PsString -> o.text()
        is PsInt -> o.v.toDouble()
        is PsReal -> o.v
        is PsBool -> o.v
        else -> o
    }

    fun keyObj(k: Any): PsObj = when (k) {
        is String -> PsName(k, false)
        is Double -> if (k == floor(k) && abs(k) < 2.0e9) PsInt(k.toInt()) else PsReal(k)
        is Boolean -> PsBool.of(k)
        is PsObj -> k
        else -> PsNull
    }

    fun lookup(name: String): PsObj? {
        for (i in dstack.indices.reversed()) dstack[i].map[name]?.let { return it }
        return null
    }

    fun whereDict(k: Any): PsDict? {
        for (i in dstack.indices.reversed()) if (dstack[i].map.containsKey(k)) return dstack[i]
        return null
    }

    fun def(name: String, o: PsObj) { dstack.last().map[name] = o }

    fun op(name: String, fn: (PsInterpreter) -> Unit) { systemdict.map[name] = PsOperator(name, fn) }

    // ---------------------------------------------------------------- ejecución
    fun checkBudget() {
        if (++opCount > maxOps) throw PsLimit("demasiadas operaciones")
        if ((opCount and 0xFFFF) == 0L && System.currentTimeMillis() - startMs > deadlineMs) throw PsLimit("tiempo agotado")
    }

    /** Ejecuta un objeto leído directamente (del archivo o de un procedimiento). */
    fun execToken(o: PsObj) {
        checkBudget()
        when (o) {
            is PsName -> if (o.isExec) {
                val v = lookup(o.name) ?: throw PsError("undefined", o.name)
                call(v, o.name)
            } else push(o)
            is PsOperator -> runOperator(o)
            else -> push(o)
        }
    }

    private fun runOperator(o: PsOperator) {
        val mark = ostack.size
        try {
            o.fn(this)
        } catch (e: PsError) {
            throw if (e.command.isEmpty()) PsError(e.errName, o.name) else e
        } catch (e: IndexOutOfBoundsException) {
            throw PsError("rangecheck", o.name)
        } catch (e: ClassCastException) {
            throw PsError("typecheck", o.name)
        } catch (e: ArithmeticException) {
            throw PsError("undefinedresult", o.name)
        }
    }

    /** Ejecuta el valor de un nombre o el operando de "exec". */
    fun call(v: PsObj, name: String = "") {
        when {
            v is PsOperator -> runOperator(v)
            v is PsArray && v.isExec -> runProc(v)
            v is PsName && v.isExec -> execToken(v)
            v is PsFile && v.isExec -> runFile(v, topLevel = false)
            v is PsString && v.isExec -> runFile(ByteArrayPsFile(v.bytes()), topLevel = false)
            else -> push(v)
        }
    }

    fun runProc(p: PsArray) {
        if (++depth > 1500) { depth--; throw PsError("execstackoverflow") }
        try {
            for (i in 0 until p.len) {
                val o = p[i]
                if ((o is PsArray || o is PsString) && o.isExec) push(o) else execToken(o)
            }
        } finally { depth-- }
    }

    fun runFile(f: PsFile, topLevel: Boolean) {
        fileStack.add(f)
        if (++depth > 1500) { depth--; fileStack.removeAt(fileStack.size - 1); throw PsError("execstackoverflow") }
        try {
            while (!f.closed) {
                val tok = try { lexer.next(f) } catch (e: PsError) { if (topLevel) { note(e); continue } else throw e } ?: break
                if (topLevel) {
                    try { execToken(tok) } catch (e: PsError) { note(e) } catch (e: PsStop) { note(PsError("stop")) } catch (e: PsExit) { }
                } else execToken(tok)
            }
        } finally { depth--; fileStack.removeAt(fileStack.size - 1) }
    }

    private fun note(e: PsError) {
        errorCount++
        if (errors.size < 25) errors.add("${e.errName} en ${e.command}")
    }

    fun setError(e: PsError) {
        errorDollar.put("newerror", PsBool.TRUE)
        errorDollar.put("errorname", PsName(e.errName, false))
        errorDollar.put("command", PsName(e.command, false))
    }

    /** Ejecuta un programa completo. */
    fun run(program: ByteArray, offset: Int = 0, end: Int = program.size) {
        startMs = System.currentTimeMillis()
        try {
            runFile(ByteArrayPsFile(program, offset, end), topLevel = true)
        } catch (e: PsQuit) {
        }
    }

    /** Ejecuta un procedimiento y devuelve lo que dejó en la pila (para funciones de color). */
    fun callForResults(proc: PsObj, args: DoubleArray): DoubleArray {
        val base = ostack.size
        for (a in args) push(PsReal(a))
        call(proc)
        val n = ostack.size - base
        val out = DoubleArray(max(n, 0)) { num(ostack[base + it]) }
        while (ostack.size > base) ostack.removeAt(ostack.size - 1)
        return out
    }

    // ---------------------------------------------------------------- utilidades
    fun cvs(o: PsObj): String = when (o) {
        is PsInt -> o.v.toString()
        is PsReal -> if (o.v == floor(o.v) && abs(o.v) < 1e15) o.v.toLong().toString() + ".0" else o.v.toString()
        is PsString -> o.text()
        is PsName -> o.name
        is PsBool -> if (o.v) "true" else "false"
        is PsOperator -> o.name
        else -> "--nostringval--"
    }

    fun psEq(a: PsObj, b: PsObj): Boolean = when {
        (a is PsInt || a is PsReal) && (b is PsInt || b is PsReal) -> num(a) == num(b)
        (a is PsString || a is PsName) && (b is PsString || b is PsName) -> cvs(a) == cvs(b)
        a is PsBool && b is PsBool -> a.v == b.v
        a is PsArray && b is PsArray -> a.items === b.items && a.off == b.off && a.len == b.len
        a is PsOperator && b is PsOperator -> a.name == b.name
        else -> a === b
    }

    fun newString(n: Int) = PsString(ByteArray(n), 0, n)

    fun counttomark(): Int {
        for (i in ostack.indices.reversed()) if (ostack[i] === PsMark) return ostack.size - 1 - i
        throw PsError("unmatchedmark")
    }

    fun popToMark(): List<PsObj> {
        val n = counttomark()
        val items = ArrayList(ostack.subList(ostack.size - n, ostack.size))
        repeat(n + 1) { ostack.removeAt(ostack.size - 1) }
        return items
    }

    fun bind(p: PsArray, seen: HashSet<Array<PsObj>> = HashSet()) {
        if (!seen.add(p.items)) return
        for (i in 0 until p.len) {
            val o = p[i]
            if (o is PsName && o.isExec) {
                val v = lookup(o.name)
                if (v is PsOperator) p[i] = v
            } else if (o is PsArray && o.isExec) bind(o, seen)
        }
    }

    fun forEachKey(d: PsDict, body: (PsObj, PsObj) -> Unit) {
        for ((k, v) in ArrayList(d.map.entries)) body(keyObj(k), v)
    }

    private inline fun loop(body: () -> Unit) {
        try { body() } catch (e: PsExit) { }
    }

    // ---------------------------------------------------------------- operadores básicos
    private fun installCore() {
        val sd = systemdict
        sd.put("systemdict", sd); sd.put("userdict", userdict); sd.put("globaldict", globaldict)
        sd.put("\$error", errorDollar); sd.put("errordict", PsDict(32)); sd.put("statusdict", PsDict(32))
        sd.put("FontDirectory", fontDir); sd.put("GlobalFontDirectory", fontDir); sd.put("SharedFontDirectory", fontDir)
        sd.put("languagelevel", PsInt(3))
        sd.put("true", PsBool.TRUE); sd.put("false", PsBool.FALSE); sd.put("null", PsNull)
        errorDollar.put("newerror", PsBool.FALSE)
        (sd["statusdict"] as PsDict).apply {
            put("product", PsString("AND Vector Viewer")); put("waittimeout", PsInt(0))
            put("setjobtimeout", PsOperator("setjobtimeout") { it.pop() })
            put("jobname", PsString("eps")); put("processcolors", PsOperator("processcolors") { it.pushInt(4) })
        }

        // pila
        op("pop") { pop() }
        op("exch") { val a = pop(); val b = pop(); push(a); push(b) }
        op("dup") { push(peek()) }
        op("copy") {
            val top = peek()
            if (top is PsInt) {
                pop(); val n = top.v; if (n < 0 || n > ostack.size) throw PsError("rangecheck")
                val s = ostack.size; for (i in 0 until n) push(ostack[s - n + i])
            } else {
                val dst = pop(); val src = pop()
                when {
                    src is PsArray && dst is PsArray -> { for (i in 0 until src.len) dst[i] = src[i]; push(PsArray(dst.items, dst.off, src.len, dst.isExec)) }
                    src is PsString && dst is PsString -> { System.arraycopy(src.buf, src.off, dst.buf, dst.off, src.len); push(PsString(dst.buf, dst.off, src.len)) }
                    src is PsDict && dst is PsDict -> { dst.map.putAll(src.map); push(dst) }
                    src is PsGState && dst is PsGState -> { dst.gs.copyFrom(src.gs); push(dst) }
                    else -> throw PsError("typecheck")
                }
            }
        }
        op("index") { val n = popInt(); push(peek(n)) }
        op("roll") {
            val j = popInt(); val n = popInt()
            if (n < 0 || n > ostack.size) throw PsError("rangecheck")
            if (n > 0) {
                val sub = ostack.subList(ostack.size - n, ostack.size)
                java.util.Collections.rotate(sub, ((j % n) + n) % n)
            }
        }
        op("clear") { ostack.clear() }
        op("count") { pushInt(ostack.size) }
        op("mark") { push(PsMark) }
        op("[") { push(PsMark) }
        op("<<") { push(PsMark) }
        op("cleartomark") { popToMark() }
        op("counttomark") { pushInt(counttomark()) }
        op("]") { push(PsArray(popToMark())) }
        op(">>") {
            val items = popToMark(); if (items.size % 2 != 0) throw PsError("rangecheck")
            val d = PsDict(items.size / 2)
            for (i in items.indices step 2) d[key(items[i])] = items[i + 1]
            push(d)
        }

        // aritmética
        fun arith(name: String, iop: (Long, Long) -> Long?, dop: (Double, Double) -> Double) = op(name) {
            val b = pop(); val a = pop()
            if (a is PsInt && b is PsInt) {
                val r = iop(a.v.toLong(), b.v.toLong())
                if (r != null && r in Int.MIN_VALUE..Int.MAX_VALUE) { pushInt(r.toInt()); return@op }
            }
            push(PsReal(dop(num(a), num(b))))
        }
        arith("add", { a, b -> a + b }) { a, b -> a + b }
        arith("sub", { a, b -> a - b }) { a, b -> a - b }
        arith("mul", { a, b -> a * b }) { a, b -> a * b }
        op("div") { val b = popNum(); val a = popNum(); if (b == 0.0) throw PsError("undefinedresult"); push(PsReal(a / b)) }
        op("idiv") { val b = popInt(); val a = popInt(); if (b == 0) throw PsError("undefinedresult"); pushInt(a / b) }
        op("mod") { val b = popInt(); val a = popInt(); if (b == 0) throw PsError("undefinedresult"); pushInt(a % b) }
        op("neg") { val a = pop(); if (a is PsInt && a.v != Int.MIN_VALUE) pushInt(-a.v) else push(PsReal(-num(a))) }
        op("abs") { val a = pop(); if (a is PsInt && a.v != Int.MIN_VALUE) pushInt(abs(a.v)) else push(PsReal(abs(num(a)))) }
        fun rnd(name: String, f: (Double) -> Double) = op(name) { val a = pop(); if (a is PsInt) push(a) else push(PsReal(f(num(a)))) }
        rnd("ceiling", ::ceil); rnd("floor", ::floor); rnd("round") { floor(it + 0.5) }; rnd("truncate", ::truncate)
        op("sqrt") { val a = popNum(); if (a < 0) throw PsError("rangecheck"); push(PsReal(sqrt(a))) }
        op("atan") { val x = popNum(); val y = popNum(); var d = Math.toDegrees(atan2(y, x)); if (d < 0) d += 360.0; push(PsReal(d)) }
        op("cos") { push(PsReal(cos(Math.toRadians(popNum())))) }
        op("sin") { push(PsReal(sin(Math.toRadians(popNum())))) }
        op("exp") { val e = popNum(); val b = popNum(); push(PsReal(b.pow(e))) }
        op("ln") { push(PsReal(ln(popNum()))) }
        op("log") { push(PsReal(log10(popNum()))) }
        op("cvi") { val a = pop(); pushInt(when (a) { is PsString -> num(PsLexer.parseNumber(a.text().trim()) ?: throw PsError("typecheck")).toInt(); else -> num(a).toLong().toInt() }) }
        op("cvr") { val a = pop(); push(PsReal(when (a) { is PsString -> num(PsLexer.parseNumber(a.text().trim()) ?: throw PsError("typecheck")); else -> num(a) })) }
        var seed = 1L
        op("rand") { seed = (seed * 16807) % 2147483647; pushInt(seed.toInt()) }
        op("srand") { seed = popInt().toLong().coerceAtLeast(1) }
        op("rrand") { pushInt(seed.toInt()) }

        // relacionales y lógicos
        op("eq") { val b = pop(); val a = pop(); pushBool(psEq(a, b)) }
        op("ne") { val b = pop(); val a = pop(); pushBool(!psEq(a, b)) }
        fun cmp(name: String, t: (Int) -> Boolean) = op(name) {
            val b = pop(); val a = pop()
            val c = if (a is PsString && b is PsString) a.text().compareTo(b.text()) else num(a).compareTo(num(b))
            pushBool(t(c))
        }
        cmp("gt") { it > 0 }; cmp("ge") { it >= 0 }; cmp("lt") { it < 0 }; cmp("le") { it <= 0 }
        fun logic(name: String, bf: (Boolean, Boolean) -> Boolean, inf: (Int, Int) -> Int) = op(name) {
            val b = pop(); val a = pop()
            if (a is PsBool && b is PsBool) pushBool(bf(a.v, b.v)) else pushInt(inf((a as PsInt).v, (b as PsInt).v))
        }
        logic("and", { a, b -> a && b }) { a, b -> a and b }
        logic("or", { a, b -> a || b }) { a, b -> a or b }
        logic("xor", { a, b -> a xor b }) { a, b -> a xor b }
        op("not") { val a = pop(); if (a is PsBool) pushBool(!a.v) else pushInt((a as PsInt).v.inv()) }
        op("bitshift") { val s = popInt(); val v = popInt(); pushInt(if (s >= 0) v shl s else v ushr -s) }

        // control
        op("exec") { call(pop()) }
        op("if") { val p = pop(); if (popBool()) call(p) }
        op("ifelse") { val p2 = pop(); val p1 = pop(); if (popBool()) call(p1) else call(p2) }
        op("for") {
            val p = pop(); val lim = pop(); val inc = pop(); val init = pop()
            loop {
                if (init is PsInt && inc is PsInt && lim is PsInt) {
                    var i = init.v
                    while (if (inc.v >= 0) i <= lim.v else i >= lim.v) { pushInt(i); call(p); i += inc.v }
                } else {
                    var i = num(init); val st = num(inc); val l = num(lim)
                    while (if (st >= 0) i <= l + 1e-9 else i >= l - 1e-9) { push(PsReal(i)); call(p); i += st }
                }
            }
        }
        op("repeat") { val p = pop(); val n = popInt(); loop { repeat(n) { call(p) } } }
        op("loop") { val p = pop(); loop { while (true) call(p) } }
        op("exit") { throw PsExit() }
        op("stop") { throw PsStop() }
        op("stopped") {
            val p = pop()
            val saveDepth = depth
            try { call(p); pushBool(false) }
            catch (e: PsStop) { depth = saveDepth; pushBool(true) }
            catch (e: PsError) { depth = saveDepth; setError(e); pushBool(true) }
        }
        op("forall") {
            val p = pop(); val o = pop()
            loop {
                when (o) {
                    is PsArray -> for (i in 0 until o.len) { push(o[i]); call(p) }
                    is PsString -> for (i in 0 until o.len) { pushInt(o[i]); call(p) }
                    is PsDict -> forEachKey(o) { k, v -> push(k); push(v); call(p) }
                    else -> throw PsError("typecheck")
                }
            }
        }
        op("quit") { throw PsQuit() }
        op("countexecstack") { pushInt(depth) }
        op("execstack") { val a = pop(); push(a) }

        // tipos
        op("type") { push(PsName(pop().typeName, true)) }
        op("cvlit") { val o = pop(); push(when (o) { is PsArray -> o.withExec(false); is PsName -> PsName(o.name, false); is PsString -> o.withExec(false); else -> o }) }
        op("cvx") {
            val o = pop()
            push(when (o) { is PsArray -> o.withExec(true); is PsName -> PsName(o.name, true); is PsString -> o.withExec(true); is ByteArrayPsFile -> o; else -> o })
        }
        op("xcheck") { pushBool(pop().isExec) }
        for (n in listOf("executeonly", "noaccess", "readonly")) op(n) { }
        op("rcheck") { pop(); pushBool(true) }
        op("wcheck") { pop(); pushBool(true) }
        op("cvn") { val s = pop(); push(PsName(cvs(s), s.isExec)) }
        op("cvs") {
            val dst = popString(); val src = cvs(pop()).toByteArray(Charsets.ISO_8859_1)
            val n = min(src.size, dst.len); System.arraycopy(src, 0, dst.buf, dst.off, n); push(PsString(dst.buf, dst.off, n))
        }
        op("cvrs") {
            val dst = popString(); val radix = popInt(); val v = pop()
            val s = (if (radix == 10) cvs(v) else Integer.toString(num(v).toInt(), radix).uppercase()).toByteArray(Charsets.ISO_8859_1)
            val n = min(s.size, dst.len); System.arraycopy(s, 0, dst.buf, dst.off, n); push(PsString(dst.buf, dst.off, n))
        }

        // diccionarios
        op("dict") { push(PsDict(popInt())) }
        op("begin") { dstack.add(popDict()) }
        op("end") { if (dstack.size <= baseDepth) throw PsError("dictstackunderflow"); dstack.removeAt(dstack.size - 1) }
        op("def") { val v = pop(); val k = pop(); dstack.last()[key(k)] = v }
        op("load") { val k = key(pop()); push(whereDict(k)?.get(k) ?: throw PsError("undefined", k.toString())) }
        op("store") { val v = pop(); val k = key(pop()); (whereDict(k) ?: dstack.last())[k] = v }
        op("known") { val k = key(pop()); pushBool(popDict().map.containsKey(k)) }
        op("where") { val k = key(pop()); val d = whereDict(k); if (d != null) { push(d); pushBool(true) } else pushBool(false) }
        op("undef") { val k = key(pop()); popDict().map.remove(k) }
        op("currentdict") { push(dstack.last()) }
        op("countdictstack") { pushInt(dstack.size) }
        op("dictstack") { val a = popArray(); for (i in dstack.indices) a[i] = dstack[i]; push(PsArray(a.items, a.off, dstack.size)) }
        op("cleardictstack") { while (dstack.size > baseDepth) dstack.removeAt(dstack.size - 1) }
        op("maxlength") { pushInt(max(popDict().maxLen, 1000)) }

        // compuestos
        op("array") { val n = popInt(); if (n < 0) throw PsError("rangecheck"); push(PsArray(Array(n) { PsNull }, 0, n)) }
        op("packedarray") { val n = popInt(); val l = ArrayList<PsObj>(); repeat(n) { l.add(0, pop()) }; push(PsArray(l).also { it.packed = true }) }
        op("setpacking") { pop() }
        op("currentpacking") { pushBool(false) }
        op("string") { val n = popInt(); if (n < 0 || n > 65535 * 64) throw PsError("rangecheck"); push(newString(n)) }
        op("length") {
            pushInt(when (val o = pop()) { is PsArray -> o.len; is PsString -> o.len; is PsDict -> o.map.size; is PsName -> o.name.length; else -> throw PsError("typecheck") })
        }
        op("get") {
            val k = pop(); val o = pop()
            when (o) {
                is PsArray -> { val i = num(k).toInt(); if (i !in 0 until o.len) throw PsError("rangecheck"); push(o[i]) }
                is PsString -> { val i = num(k).toInt(); if (i !in 0 until o.len) throw PsError("rangecheck"); pushInt(o[i]) }
                is PsDict -> push(o[key(k)] ?: throw PsError("undefined", cvs(k)))
                else -> throw PsError("typecheck")
            }
        }
        op("put") {
            val v = pop(); val k = pop(); val o = pop()
            when (o) {
                is PsArray -> { val i = num(k).toInt(); if (i !in 0 until o.len) throw PsError("rangecheck"); o[i] = v }
                is PsString -> { val i = num(k).toInt(); if (i !in 0 until o.len) throw PsError("rangecheck"); o.buf[o.off + i] = num(v).toInt().toByte() }
                is PsDict -> o[key(k)] = v
                else -> throw PsError("typecheck")
            }
        }
        op("getinterval") {
            val n = popInt(); val i = popInt(); val o = pop()
            when (o) {
                is PsArray -> { if (i < 0 || n < 0 || i + n > o.len) throw PsError("rangecheck"); push(PsArray(o.items, o.off + i, n, o.isExec)) }
                is PsString -> { if (i < 0 || n < 0 || i + n > o.len) throw PsError("rangecheck"); push(PsString(o.buf, o.off + i, n, o.isExec)) }
                else -> throw PsError("typecheck")
            }
        }
        op("putinterval") {
            val src = pop(); val i = popInt(); val dst = pop()
            when {
                dst is PsArray && src is PsArray -> { if (i + src.len > dst.len) throw PsError("rangecheck"); for (j in 0 until src.len) dst[i + j] = src[j] }
                dst is PsString && src is PsString -> { if (i + src.len > dst.len) throw PsError("rangecheck"); System.arraycopy(src.buf, src.off, dst.buf, dst.off + i, src.len) }
                else -> throw PsError("typecheck")
            }
        }
        op("aload") { val a = popArray(); for (i in 0 until a.len) push(a[i]); push(a) }
        op("astore") { val a = popArray(); for (i in a.len - 1 downTo 0) a[i] = pop(); push(a) }
        op("bind") { val p = peek(); if (p is PsArray) bind(p) }
        op("search") {
            val seek = popString(); val s = popString()
            val idx = s.text().indexOf(seek.text())
            if (idx < 0) { push(s); pushBool(false) } else {
                push(PsString(s.buf, s.off + idx + seek.len, s.len - idx - seek.len))
                push(PsString(s.buf, s.off + idx, seek.len)); push(PsString(s.buf, s.off, idx)); pushBool(true)
            }
        }
        op("anchorsearch") {
            val seek = popString(); val s = popString()
            if (s.text().startsWith(seek.text())) { push(PsString(s.buf, s.off + seek.len, s.len - seek.len)); push(PsString(s.buf, s.off, seek.len)); pushBool(true) }
            else { push(s); pushBool(false) }
        }
        op("token") {
            when (val src = pop()) {
                is PsString -> {
                    val f = ByteArrayPsFile(src.buf, src.off, src.off + src.len)
                    val t = lexer.next(f)
                    if (t == null) pushBool(false) else {
                        val used = f.position - src.off
                        push(PsString(src.buf, src.off + used, src.len - used)); push(t); pushBool(true)
                    }
                }
                is PsFile -> { val t = lexer.next(src); if (t == null) pushBool(false) else { push(t); pushBool(true) } }
                else -> throw PsError("typecheck")
            }
        }

        // archivos
        op("currentfile") { push(fileStack.lastOrNull() ?: ByteArrayPsFile(ByteArray(0))) }
        op("file") {
            pop(); val name = popString().text()
            push(if (name == "%stdin" && fileStack.isNotEmpty()) fileStack.last() else ByteArrayPsFile(ByteArray(0)))
        }
        op("closefile") { (pop() as PsFile).closed = true }
        op("read") { val f = pop() as PsFile; val c = f.read(); if (c < 0) pushBool(false) else { pushInt(c); pushBool(true) } }
        op("readstring") {
            val s = popString(); val f = pop() as PsFile
            var n = 0
            while (n < s.len) { val c = f.read(); if (c < 0) break; s.buf[s.off + n++] = c.toByte() }
            push(PsString(s.buf, s.off, n)); pushBool(n == s.len)
        }
        op("readhexstring") {
            val s = popString(); val f = pop() as PsFile
            var n = 0; var hi = -1
            while (n < s.len) {
                val c = f.read(); if (c < 0) break
                val v = Character.digit(c, 16); if (v < 0) continue
                if (hi < 0) hi = v else { s.buf[s.off + n++] = ((hi shl 4) or v).toByte(); hi = -1 }
            }
            push(PsString(s.buf, s.off, n)); pushBool(n == s.len)
        }
        op("readline") {
            val s = popString(); val f = pop() as PsFile
            var n = 0; var eol = false
            while (true) {
                val c = f.read()
                if (c < 0) break
                if (c == 10) { eol = true; break }
                if (c == 13) { val d = f.read(); if (d != 10) f.unread(d); eol = true; break }
                if (n < s.len) s.buf[s.off + n++] = c.toByte()
            }
            push(PsString(s.buf, s.off, n)); pushBool(eol)
        }
        op("bytesavailable") { pushInt((pop() as PsFile).available()) }
        op("flushfile") { val f = pop() as PsFile; while (f.read() >= 0) { } }
        op("resetfile") { pop() }
        op("status") { val o = pop(); if (o is PsFile) pushBool(!o.closed) else pushBool(false) }
        op("run") { pop() }
        op("filter") { push(makeFilter()) }
        op("eexec") {
            val src = pop()
            val f = when (src) { is PsFile -> Filters.EexecFile(src); is PsString -> Filters.EexecFile(ByteArrayPsFile(src.bytes())); else -> throw PsError("typecheck") }
            dstack.add(systemdict)
            try { runFile(f, topLevel = false) } finally { if (dstack.last() === systemdict && dstack.size > 3) dstack.removeAt(dstack.size - 1) }
        }
        for (n in listOf("print", "flush", "=", "==", "pstack", "stack", "echo", "prompt", "executive")) op(n) {
            if (n == "print" || n == "=" || n == "==") pop(); if (n == "echo") pop()
        }
        op("writestring") { pop(); pop() }
        op("write") { pop(); pop() }
        op("writehexstring") { pop(); pop() }

        // VM y entorno
        op("save") { push(PsSave(gfx.depth())); gfx.gsave() }
        op("restore") { val s = pop() as? PsSave ?: throw PsError("typecheck"); gfx.restoreTo(s.gsDepth) }
        op("setglobal") { global = popBool() }
        op("currentglobal") { pushBool(global) }
        op("gcheck") { pop(); pushBool(false) }
        op("vmstatus") { pushInt(0); pushInt(100000); pushInt(64_000_000) }
        op("vmreclaim") { pop() }
        op("setvmthreshold") { pop() }
        op("version") { push(PsString("3010")) }
        op("product") { push(PsString("AND Vector Viewer")) }
        op("revision") { pushInt(1) }
        op("serialnumber") { pushInt(1) }
        op("realtime") { pushInt((System.currentTimeMillis() and 0x7FFFFFFF).toInt()) }
        op("usertime") { pushInt(((System.currentTimeMillis() - startMs) and 0x7FFFFFFF).toInt()) }
        op("setuserparams") { pop() }
        op("setsystemparams") { pop() }
        op("currentuserparams") { push(PsDict()) }
        op("currentsystemparams") { push(PsDict()) }
        op("setdevparams") { pop(); pop() }
        op("currentdevparams") { pop(); push(PsDict()) }
        op("setpagedevice") { pop() }
        op("currentpagedevice") {
            push(PsDict().apply {
                put("PageSize", PsArray(listOf(PsInt(612), PsInt(792))))
                put("HWResolution", PsArray(listOf(PsInt(72), PsInt(72))))
                put("Policies", PsDict()); put("InputAttributes", PsDict()); put("OutputAttributes", PsDict())
                put("ProcessColorModel", PsName("DeviceCMYK", false))
            })
        }
        op("setdistillerparams") { pop() }
        op("currentdistillerparams") { push(PsDict()) }
        op("setobjectformat") { pop() }
        op("currentobjectformat") { pushInt(0) }
        op("defineuserobject") { pop(); pop() }
        op("execuserobject") { pop() }
        op("undefineuserobject") { pop() }
        op("handleerror") { }
        op(".error") { }
        op("superexec") { call(pop()) }

        // recursos
        op("findresource") {
            val cat = cvs(pop()); val k = pop()
            if (cat == "Font") { push(k); call(lookup("findfont")!!); return@op }
            val d = resources[cat]?.get(key(k))
            if (d != null) push(d) else if (cat == "Category") push(PsDict()) else throw PsError("undefinedresource", cvs(k))
        }
        op("defineresource") {
            val cat = cvs(pop()); val inst = pop(); val k = pop()
            if (cat == "Font") fontDir[key(k)] = inst
            resources.getOrPut(cat) { PsDict() }[key(k)] = inst
            push(inst)
        }
        op("undefineresource") { val cat = cvs(pop()); val k = pop(); resources[cat]?.map?.remove(key(k)) }
        op("resourcestatus") {
            val cat = cvs(pop()); val k = key(pop())
            val ok = resources[cat]?.map?.containsKey(k) == true || (cat == "Font" && fontDir.map.containsKey(k))
            if (ok) { pushInt(1); pushInt(0); pushBool(true) } else pushBool(false)
        }
        op("resourceforall") { pop(); pop(); pop(); pop() }

        // valores de usuario
        userdict.put("#copies", PsInt(1))
    }

    private fun makeFilter(): PsObj {
        val name = cvs(pop())
        // parámetros opcionales (diccionario) antes del nombre
        var params: PsDict? = null
        if (peek() is PsDict && name != "SubFileDecode") params = pop() as PsDict
        else if (peek() is PsDict && name == "SubFileDecode" && peek(1) !is PsInt) params = pop() as PsDict
        var count = 0; var eod = ByteArray(0)
        if (name == "SubFileDecode" && params == null) {
            eod = (pop() as? PsString)?.bytes() ?: ByteArray(0); count = popInt()
        }
        val src = pop()
        val input: java.io.InputStream = when (src) {
            is PsFile -> PsFileInputStream(src)
            is PsString -> src.bytes().inputStream()
            is PsArray -> {
                // fuente procedimiento: se llama cada vez que se agota el dato
                object : java.io.InputStream() {
                    var cur: ByteArray = ByteArray(0); var pos = 0; var done = false
                    override fun read(): Int {
                        while (pos >= cur.size) {
                            if (done) return -1
                            call(src)
                            val s = pop() as? PsString
                            if (s == null || s.len == 0) { done = true; return -1 }
                            cur = s.bytes(); pos = 0
                        }
                        return cur[pos++].toInt() and 0xFF
                    }
                }
            }
            else -> throw PsError("typecheck")
        }
        val out = when (name) {
            "ASCII85Decode" -> Filters.ascii85(input)
            "ASCIIHexDecode" -> Filters.asciiHex(input)
            "RunLengthDecode" -> Filters.runLength(input)
            "FlateDecode" -> Filters.flate(input)
            "LZWDecode" -> Filters.lzw(input, (params?.get("EarlyChange") as? PsInt)?.v ?: 1)
            "SubFileDecode" -> Filters.subFile(input, count, eod)
            "DCTDecode" -> return StreamPsFile(Filters.jpegUntilEoi(input), isDct = true)
            "NullEncode", "ASCII85Encode", "ASCIIHexEncode", "RunLengthEncode", "LZWEncode", "FlateEncode", "DCTEncode" -> input
            else -> throw PsError("undefined", name)
        }
        val pred = (params?.get("Predictor") as? PsInt)?.v ?: 1
        return StreamPsFile(if (pred >= 10 && name == "FlateDecode") pngPredict(out, params!!) else out)
    }

    private fun pngPredict(src: java.io.InputStream, p: PsDict): java.io.InputStream {
        val colors = (p["Colors"] as? PsInt)?.v ?: 1
        val bpc = (p["BitsPerComponent"] as? PsInt)?.v ?: 8
        val cols = (p["Columns"] as? PsInt)?.v ?: 1
        val bpp = max(1, colors * bpc / 8)
        val row = (colors * bpc * cols + 7) / 8
        val data = src.readBytes()
        val out = java.io.ByteArrayOutputStream()
        var prev = ByteArray(row)
        var i = 0
        while (i + 1 + row <= data.size) {
            val type = data[i].toInt(); val cur = data.copyOfRange(i + 1, i + 1 + row)
            for (x in 0 until row) {
                val a = if (x >= bpp) cur[x - bpp].toInt() and 0xFF else 0
                val b = prev[x].toInt() and 0xFF
                val c = if (x >= bpp) prev[x - bpp].toInt() and 0xFF else 0
                val v = cur[x].toInt() and 0xFF
                cur[x] = when (type) {
                    1 -> v + a; 2 -> v + b; 3 -> v + (a + b) / 2
                    4 -> { val pp = a + b - c; val pa = abs(pp - a); val pb = abs(pp - b); val pc = abs(pp - c); v + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                    else -> v
                }.toByte()
            }
            out.write(cur); prev = cur; i += 1 + row
        }
        return out.toByteArray().inputStream()
    }
}
