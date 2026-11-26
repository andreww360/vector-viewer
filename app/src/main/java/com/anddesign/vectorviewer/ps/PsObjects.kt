package com.anddesign.vectorviewer.ps

/*
 * Motor PostScript propio de AND Vector Viewer (sin Ghostscript).
 * Este paquete es Kotlin puro (sin dependencias de Android) para poder probarlo en la JVM.
 */

sealed class PsObj {
    open val isExec: Boolean get() = false
    abstract val typeName: String
}

object PsNull : PsObj() { override val typeName = "nulltype" }
object PsMark : PsObj() { override val typeName = "marktype" }

class PsInt(val v: Int) : PsObj() {
    override val typeName get() = "integertype"
    override fun toString() = v.toString()
}

class PsReal(val v: Double) : PsObj() {
    override val typeName get() = "realtype"
    override fun toString() = v.toString()
}

class PsBool private constructor(val v: Boolean) : PsObj() {
    override val typeName get() = "booleantype"
    companion object {
        val TRUE = PsBool(true)
        val FALSE = PsBool(false)
        fun of(b: Boolean) = if (b) TRUE else FALSE
    }
}

class PsName(val name: String, override val isExec: Boolean, val immediate: Boolean = false) : PsObj() {
    override val typeName get() = "nametype"
    override fun toString() = if (isExec) name else "/$name"
}

class PsString(val buf: ByteArray, val off: Int, val len: Int, override val isExec: Boolean = false) : PsObj() {
    constructor(s: String) : this(s.toByteArray(Charsets.ISO_8859_1), 0, s.length)
    override val typeName get() = "stringtype"
    fun text() = String(buf, off, len, Charsets.ISO_8859_1)
    fun bytes() = buf.copyOfRange(off, off + len)
    operator fun get(i: Int) = buf[off + i].toInt() and 0xFF
    fun withExec(e: Boolean) = PsString(buf, off, len, e)
    override fun toString() = "(" + text() + ")"
}

class PsArray(val items: Array<PsObj>, val off: Int, val len: Int, override val isExec: Boolean = false) : PsObj() {
    constructor(list: List<PsObj>, exec: Boolean = false) : this(list.toTypedArray(), 0, list.size, exec)
    override val typeName get() = if (packed) "packedarraytype" else "arraytype"
    var packed = false
    operator fun get(i: Int) = items[off + i]
    operator fun set(i: Int, o: PsObj) { items[off + i] = o }
    fun withExec(e: Boolean) = PsArray(items, off, len, e)
    fun toList(): List<PsObj> = (0 until len).map { items[off + it] }
}

class PsDict(cap: Int = 16) : PsObj() {
    override val typeName get() = "dicttype"
    val map = LinkedHashMap<Any, PsObj>(maxOf(cap, 4))
    var maxLen = maxOf(cap, 1)
    operator fun get(k: Any): PsObj? = map[k]
    operator fun set(k: Any, v: PsObj) { map[k] = v }
    fun put(k: String, v: PsObj) { map[k] = v }
}

class PsOperator(val name: String, val fn: (PsInterpreter) -> Unit) : PsObj() {
    override val isExec get() = true
    override val typeName get() = "operatortype"
    override fun toString() = "--$name--"
}

class PsSave(val gsDepth: Int) : PsObj() {
    override val typeName get() = "savetype"
}

class PsFontId : PsObj() { override val typeName get() = "fonttype" }

class PsGState(val gs: GState) : PsObj() { override val typeName get() = "gstatetype" }

/** Archivo PostScript (la fuente principal, un filtro o el decodificador eexec). */
abstract class PsFile(override val isExec: Boolean = false) : PsObj() {
    override val typeName get() = "filetype"
    private var pushback = -1
    var closed = false
    protected abstract fun readRaw(): Int

    fun read(): Int {
        if (pushback >= 0) { val c = pushback; pushback = -1; return c }
        if (closed) return -1
        val c = readRaw()
        if (c < 0) closed = true
        return c
    }

    fun unread(c: Int) { if (c >= 0) pushback = c }

    open fun asExec(): PsFile = this
    open fun available(): Int = if (closed) -1 else 1
}

class ByteArrayPsFile(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) : PsFile(true) {
    override fun readRaw(): Int = if (pos < end) data[pos++].toInt() and 0xFF else -1
    override fun available() = if (closed) -1 else end - pos
    val position get() = pos
}

/** Envuelve un InputStream (filtros). */
class StreamPsFile(private val s: java.io.InputStream, val isDct: Boolean = false) : PsFile(false) {
    override fun readRaw(): Int = try { s.read() } catch (e: Exception) { -1 }
}

class PsFileInputStream(private val f: PsFile) : java.io.InputStream() {
    override fun read(): Int = f.read()
}

/** Error de PostScript (undefined, typecheck, ...). Se captura con "stopped". */
class PsError(val errName: String, val command: String = "") : RuntimeException(errName) {
    override fun fillInStackTrace(): Throwable = this
}

class PsStop : RuntimeException() { override fun fillInStackTrace(): Throwable = this }
class PsExit : RuntimeException() { override fun fillInStackTrace(): Throwable = this }
class PsQuit : RuntimeException() { override fun fillInStackTrace(): Throwable = this }
class PsLimit(msg: String) : RuntimeException(msg) { override fun fillInStackTrace(): Throwable = this }
