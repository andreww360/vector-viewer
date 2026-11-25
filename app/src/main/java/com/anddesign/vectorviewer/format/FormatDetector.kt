package com.anddesign.vectorviewer.format

/**
 * Detecta el formato real leyendo los primeros bytes ("magic numbers").
 * La extensión solo se usa para desempatar: muchos proveedores en la nube
 * entregan .ai / .cdr como application/octet-stream.
 */
object FormatDetector {

    fun detect(header: ByteArray, fileName: String): VectorFormat {
        val byExt = VectorFormat.fromExtension(fileName)
        val ascii = String(header, Charsets.ISO_8859_1)

        fun magic(vararg bytes: Int): Boolean =
            header.size >= bytes.size && bytes.indices.all { (header[it].toInt() and 0xFF) == bytes[it] }

        return when {
            // Illustrator CS+ guarda un PDF válido; PDF normal también empieza así.
            ascii.startsWith("%PDF") ->
                if (byExt == VectorFormat.AI) VectorFormat.AI else VectorFormat.PDF

            // EPS "DOS binario" (con vista previa TIFF/WMF)
            magic(0xC5, 0xD0, 0xD3, 0xC6) ->
                if (byExt == VectorFormat.AI) VectorFormat.AI else VectorFormat.EPS

            // PostScript / EPS plano, o Illustrator ≤ 8
            ascii.startsWith("%!PS") ->
                if (byExt == VectorFormat.AI) VectorFormat.AI else VectorFormat.EPS

            // CorelDRAW ≤ X3 (RIFF "CDRx")
            ascii.startsWith("RIFF") && ascii.length >= 11 &&
                ascii.substring(8, 11).equals("CDR", ignoreCase = true) -> VectorFormat.CDR

            // Corel Presentation Exchange (CMX)
            ascii.startsWith("RIFF") && ascii.length >= 11 &&
                ascii.substring(8, 11).equals("CMX", ignoreCase = true) -> VectorFormat.CDR

            // CorelDRAW X4+ (contenedor ZIP)
            magic(0x50, 0x4B, 0x03, 0x04) && byExt == VectorFormat.CDR -> VectorFormat.CDR

            // SVGZ (gzip)
            magic(0x1F, 0x8B) && byExt == VectorFormat.SVG -> VectorFormat.SVG

            ascii.contains("<svg", ignoreCase = true) -> VectorFormat.SVG

            byExt == VectorFormat.DXF -> VectorFormat.DXF
            byExt == VectorFormat.PLT -> VectorFormat.PLT
            ascii.trimStart().startsWith("IN;") -> VectorFormat.PLT

            else -> byExt
        }
    }
}
