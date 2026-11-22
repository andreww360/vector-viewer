package com.anddesign.vectorviewer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.SizeF
import java.io.Closeable

/**
 * Abstracción común para todos los formatos.
 *
 * Coordenadas del documento: puntos (1/72 in). Así la medición en mm/cm/in
 * es igual para todos los motores.
 *
 * [render] dibuja la página en [bitmap] aplicando [matrix] (documento en pt → píxeles
 * del bitmap). El visor usa esto para renderizar solo la zona visible a la
 * resolución de pantalla, por eso el zoom se mantiene nítido.
 */
interface VectorDocument : Closeable {
    val pageCount: Int

    /** true si la escala física es fiable (permite la herramienta de medición). */
    val hasRealScale: Boolean

    /** true si solo mostramos una vista previa incrustada en lugar del vector real. */
    val isPreviewOnly: Boolean

    /** Texto informativo sobre cómo se interpretó el archivo. */
    val engineNote: String?

    fun pageSize(page: Int): SizeF

    fun render(page: Int, bitmap: Bitmap, matrix: Matrix)

    /**
     * Dibuja la página como vector en un Canvas en puntos (p. ej. el de un PDF o de impresión).
     * Devuelve false si el formato solo puede rasterizarse.
     */
    fun drawVector(page: Int, canvas: Canvas): Boolean = false

    override fun close() {}
}
