package com.anddesign.vectorviewer.render

import android.util.Log

/**
 * Motor CorelDRAW nativo (libcdr + librevenge, compilado en GitHub Actions con native/build-cdr.sh).
 * Si la librería no viene en el APK (p. ej. una compilación local sin NDK), la app usa la
 * miniatura incrustada como antes.
 */
object CdrNative {

    val available: Boolean by lazy {
        try {
            System.loadLibrary("cdrbridge")
            true
        } catch (t: Throwable) {
            Log.w("CdrNative", "Motor CDR no disponible: ${t.message}")
            false
        }
    }

    /** Convierte un CDR/CMX a SVG: un elemento por página. null si no se pudo interpretar. */
    @JvmStatic
    external fun convertToSvg(path: String): Array<ByteArray>?

    /** Deja el SVG de librevenge (con prefijo "svg:") en SVG estándar para AndroidSVG. */
    fun normalizeSvg(raw: String): String {
        var s = raw
            .replace("<svg:", "<")
            .replace("</svg:", "</")
            .replace("xmlns:svg=", "xmlns=")
        if (!s.contains("xmlns=\"http://www.w3.org/2000/svg\"")) {
            s = s.replaceFirst("<svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"")
        }
        if (s.contains("xlink:") && !s.contains("xmlns:xlink")) {
            s = s.replaceFirst("<svg", "<svg xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
        }
        return s
    }
}
