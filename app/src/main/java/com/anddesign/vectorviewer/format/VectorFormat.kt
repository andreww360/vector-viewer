package com.anddesign.vectorviewer.format

enum class VectorFormat(val label: String, val badge: String, val mimeType: String) {
    PDF("PDF", "PDF", "application/pdf"),
    AI("Adobe Illustrator", "AI", "application/illustrator"),
    EPS("Encapsulated PostScript", "EPS", "application/postscript"),
    SVG("SVG", "SVG", "image/svg+xml"),
    CDR("CorelDRAW", "CDR", "application/x-cdr"),
    DXF("AutoCAD DXF", "DXF", "image/vnd.dxf"),
    PLT("HPGL / Plotter de corte", "PLT", "application/vnd.hp-hpgl"),
    UNKNOWN("?", "?", "application/octet-stream");

    companion object {
        private val EXTENSIONS = setOf(
            "pdf", "ai", "eps", "epsf", "epsi", "ps",
            "svg", "svgz", "cdr", "cmx", "dxf", "plt", "hpgl", "hpg"
        )

        fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

        fun fromExtension(name: String): VectorFormat = when (extensionOf(name)) {
            "pdf" -> PDF
            "ai" -> AI
            "eps", "epsf", "epsi", "ps" -> EPS
            "svg", "svgz" -> SVG
            "cdr", "cmx" -> CDR
            "dxf" -> DXF
            "plt", "hpgl", "hpg" -> PLT
            else -> UNKNOWN
        }

        fun isSupportedName(name: String?): Boolean = name != null && extensionOf(name) in EXTENSIONS

        fun fromBadge(badge: String): VectorFormat = entries.firstOrNull { it.badge == badge } ?: UNKNOWN
    }
}
