package com.anddesign.vectorviewer.parsers

import android.graphics.Path
import com.anddesign.vectorviewer.render.DocumentException
import com.anddesign.vectorviewer.render.PathDocument

/**
 * HPGL / PLT: el archivo que reciben los plotters de corte (Roland, Graphtec, Summa, Mimaki…).
 * Ideal para revisar un corte de vinilo o PPF antes de mandarlo a la máquina.
 * Unidades: 40 por milímetro (1016 por pulgada).
 */
object HpglParser {

    private const val UNITS_PER_MM = 40f
    private val COMMAND = Regex("([A-Za-z]{2})([^A-Za-z;]*)")
    private val SEPARATORS = Regex("[,\\s]+")

    fun parse(text: String): PathDocument {
        val path = Path()
        var x = 0f
        var y = 0f
        var penDown = false
        var absolute = true
        var strokes = 0

        for (m in COMMAND.findAll(text)) {
            val cmd = m.groupValues[1].uppercase()
            val nums = m.groupValues[2].trim().split(SEPARATORS).mapNotNull { it.toFloatOrNull() }
            when (cmd) {
                "IN" -> {
                    x = 0f; y = 0f; penDown = false; absolute = true
                    path.moveTo(0f, 0f)
                }
                "PU", "PD", "PA", "PR" -> {
                    when (cmd) {
                        "PU" -> penDown = false
                        "PD" -> penDown = true
                        "PA" -> absolute = true
                        "PR" -> absolute = false
                    }
                    var i = 0
                    while (i + 1 < nums.size) {
                        val nx = if (absolute) nums[i] else x + nums[i]
                        val ny = if (absolute) nums[i + 1] else y + nums[i + 1]
                        if (penDown) {
                            if (strokes == 0) path.moveTo(x, y)
                            path.lineTo(nx, ny)
                            strokes++
                        } else {
                            path.moveTo(nx, ny)
                        }
                        x = nx
                        y = ny
                        i += 2
                    }
                }
                "CI" -> nums.firstOrNull()?.let { r ->
                    path.addCircle(x, y, r, Path.Direction.CCW)
                    path.moveTo(x, y)
                    strokes++
                }
            }
        }

        if (strokes == 0) throw DocumentException("El archivo PLT no contiene trazos")
        return PathDocument.fromRaw(
            path,
            unitToPt = 72f / 25.4f / UNITS_PER_MM,
            flipY = true,
            note = "HPGL · $strokes segmentos de corte",
        )
    }
}
