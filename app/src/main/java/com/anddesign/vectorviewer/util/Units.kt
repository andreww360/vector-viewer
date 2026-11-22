package com.anddesign.vectorviewer.util

import java.util.Locale

/** Todas las medidas internas están en puntos tipográficos (1 pt = 1/72 in = 0,3528 mm). */
enum class LengthUnit(val label: String, private val perPoint: Double, private val decimals: Int) {
    MM("mm", 25.4 / 72.0, 1),
    CM("cm", 2.54 / 72.0, 2),
    IN("in", 1.0 / 72.0, 2);

    fun value(pt: Double): Double = pt * perPoint

    fun format(pt: Double): String =
        "%.${decimals}f %s".format(Locale.US, value(pt), label)

    fun formatSize(wPt: Double, hPt: Double): String =
        "%.${decimals}f × %.${decimals}f %s".format(Locale.US, value(wPt), value(hPt), label)

    fun next(): LengthUnit = entries[(ordinal + 1) % entries.size]
}

/** Fondo detrás de la mesa de trabajo. CHECKER muestra transparencias (útil para vinilo/PPF). */
enum class Backdrop {
    WHITE, CHECKER, DARK;

    fun next(): Backdrop = entries[(ordinal + 1) % entries.size]
}
