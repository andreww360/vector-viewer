package com.anddesign.vectorviewer.parsers

import android.graphics.RectF
import com.anddesign.vectorviewer.ps.AiLegacy
import com.anddesign.vectorviewer.ps.DrawOp
import com.anddesign.vectorviewer.ps.PsInterpreter

/** Ejecuta el motor PostScript en un hilo con pila grande y con límite de tiempo. */
object PsEngine {

    /** Devuelve (operaciones de dibujo, cantidad de errores) o null si no dibujó nada. */
    fun run(program: ByteArray, bbox: RectF, illustrator: Boolean, timeoutMs: Long = 25_000): Pair<List<DrawOp>, Int>? {
        var result: Pair<List<DrawOp>, Int>? = null
        val t = Thread(null, {
            try {
                val ip = PsInterpreter(deadlineMs = timeoutMs - 2_000)
                if (illustrator) AiLegacy.install(ip)
                var extra = 0
                try { ip.run(program) } catch (e: OutOfMemoryError) { throw e } catch (e: Throwable) { extra = 1 }
                if (ip.gfx.ops.isNotEmpty()) result = Pair(ip.gfx.ops.toList(), ip.errorCount + extra)
            } catch (e: Throwable) {
                // StackOverflow, memoria o límite: se usa la vista previa
            }
        }, "ps-engine", 256L * 1024 * 1024)
        t.start()
        t.join(timeoutMs)
        if (t.isAlive) { t.interrupt(); return null }
        return result
    }
}
