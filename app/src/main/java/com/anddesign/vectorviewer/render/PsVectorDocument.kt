package com.anddesign.vectorviewer.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.SizeF
import com.anddesign.vectorviewer.ps.ClipNode
import com.anddesign.vectorviewer.ps.DrawOp
import com.anddesign.vectorviewer.ps.FillOp
import com.anddesign.vectorviewer.ps.ImageOp
import com.anddesign.vectorviewer.ps.PsPath
import com.anddesign.vectorviewer.ps.ShadeOp
import com.anddesign.vectorviewer.ps.StrokeOp
import com.anddesign.vectorviewer.ps.TextOp
import java.util.IdentityHashMap

/**
 * EPS / Illustrator antiguo dibujado en vector con el motor PostScript propio (paquete ps).
 * Las coordenadas de PostScript (pt, Y hacia arriba) se llevan a la página (pt, Y hacia abajo)
 * con origen en la esquina superior izquierda del BoundingBox.
 */
class PsVectorDocument(
    private val ops: List<DrawOp>,
    private val llx: Float, private val lly: Float, private val urx: Float, private val ury: Float,
    override val engineNote: String?,
) : VectorDocument {

    override val pageCount = 1
    override val hasRealScale = true
    override val isPreviewOnly = false

    private val base = Matrix().apply { setValues(floatArrayOf(1f, 0f, -llx, 0f, -1f, ury, 0f, 0f, 1f)) }
    private val paths = IdentityHashMap<PsPath, Path>()
    private val bitmaps = IdentityHashMap<ImageOp, Bitmap?>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 1000f }

    override fun pageSize(page: Int) = SizeF(urx - llx, ury - lly)

    override fun render(page: Int, bitmap: Bitmap, matrix: Matrix) {
        val c = Canvas(bitmap)
        c.concat(matrix)
        draw(c)
    }

    override fun drawVector(page: Int, canvas: Canvas): Boolean {
        draw(canvas)
        return true
    }

    @Synchronized
    private fun draw(c: Canvas) {
        c.save()
        c.concat(base)
        for (op in ops) {
            c.save()
            try {
                applyClip(c, op.clip)
                when (op) {
                    is FillOp -> { fill.color = op.argb; fill.shader = null; c.drawPath(path(op.path, op.evenOdd), fill) }
                    is StrokeOp -> drawStroke(c, op)
                    is ImageOp -> drawImage(c, op)
                    is ShadeOp -> drawShade(c, op)
                    is TextOp -> drawText(c, op)
                }
            } catch (e: RuntimeException) {
                // una operación problemática no debe impedir dibujar el resto
            }
            c.restore()
        }
        c.restore()
    }

    private fun applyClip(c: Canvas, clip: ClipNode?) {
        var n = clip
        while (n != null) { c.clipPath(path(n.path, n.evenOdd)); n = n.parent }
    }

    private fun path(p: PsPath, evenOdd: Boolean): Path {
        val ap = paths.getOrPut(p) {
            val a = Path()
            p.forEach { op, v, i ->
                when (op) {
                    PsPath.MOVE -> a.moveTo(v[i].toFloat(), v[i + 1].toFloat())
                    PsPath.LINE -> a.lineTo(v[i].toFloat(), v[i + 1].toFloat())
                    PsPath.CURVE -> a.cubicTo(v[i].toFloat(), v[i + 1].toFloat(), v[i + 2].toFloat(), v[i + 3].toFloat(), v[i + 4].toFloat(), v[i + 5].toFloat())
                    else -> a.close()
                }
            }
            a
        }
        ap.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        return ap
    }

    private fun mat(m: DoubleArray) = Matrix().apply {
        setValues(floatArrayOf(m[0].toFloat(), m[2].toFloat(), m[4].toFloat(), m[1].toFloat(), m[3].toFloat(), m[5].toFloat(), 0f, 0f, 1f))
    }

    private fun drawStroke(c: Canvas, op: StrokeOp) {
        c.concat(mat(op.ctm))
        stroke.color = op.argb
        stroke.strokeWidth = op.width.toFloat() // 0 = línea fina de 1 px, como en PostScript
        stroke.strokeCap = when (op.cap) { 1 -> Paint.Cap.ROUND; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
        stroke.strokeJoin = when (op.join) { 1 -> Paint.Join.ROUND; 2 -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
        stroke.strokeMiter = op.miter.toFloat().coerceAtLeast(1f)
        val d = op.dash
        stroke.pathEffect = if (d != null && d.isNotEmpty()) {
            val arr = (if (d.size % 2 == 1) d + d else d).map { it.toFloat().coerceAtLeast(0.01f) }.toFloatArray()
            DashPathEffect(arr, op.dashPhase.toFloat())
        } else null
        c.drawPath(path(op.path, false), stroke)
    }

    private fun drawImage(c: Canvas, op: ImageOp) {
        val bmp = bitmaps.getOrPut(op) {
            when {
                op.argb != null -> Bitmap.createBitmap(op.argb, op.w, op.h, Bitmap.Config.ARGB_8888)
                op.jpeg != null -> BitmapFactory.decodeByteArray(op.jpeg, 0, op.jpeg.size)
                else -> null
            }
        } ?: return
        c.concat(mat(op.matrix))
        // cuadrado unidad con la fila 0 de la imagen arriba
        c.concat(Matrix().apply { setValues(floatArrayOf(1f / bmp.width, 0f, 0f, 0f, -1f / bmp.height, 1f, 0f, 0f, 1f)) })
        c.drawBitmap(bmp, 0f, 0f, bmpPaint)
    }

    private fun drawShade(c: Canvas, op: ShadeOp) {
        c.concat(mat(op.ctm))
        val k = op.coords
        val shader = if (!op.radial) {
            LinearGradient(k[0].toFloat(), k[1].toFloat(), k[2].toFloat(), k[3].toFloat(), op.colors, op.stops, Shader.TileMode.CLAMP)
        } else if (Build.VERSION.SDK_INT >= 29) {
            RadialGradient(
                k[0].toFloat(), k[1].toFloat(), k[2].toFloat().coerceAtLeast(0f),
                k[3].toFloat(), k[4].toFloat(), k[5].toFloat().coerceAtLeast(0.01f),
                op.colors.map { android.graphics.Color.pack(it) }.toLongArray(),
                op.stops, Shader.TileMode.CLAMP,
            )
        } else {
            RadialGradient(k[3].toFloat(), k[4].toFloat(), k[5].toFloat().coerceAtLeast(0.01f), op.colors, op.stops, Shader.TileMode.CLAMP)
        }
        fill.shader = shader
        c.drawPaint(fill)
        fill.shader = null
    }

    private fun drawText(c: Canvas, op: TextOp) {
        c.concat(mat(op.matrix))
        c.scale(1f, -1f)
        val n = op.fontName.lowercase()
        val style = when {
            "bold" in n && ("italic" in n || "oblique" in n) -> Typeface.BOLD_ITALIC
            "bold" in n || "black" in n || "heavy" in n -> Typeface.BOLD
            "italic" in n || "oblique" in n -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val family = when {
            "times" in n || "serif" in n && "sans" !in n || "garamond" in n || "georgia" in n -> Typeface.SERIF
            "courier" in n || "mono" in n -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        textPaint.typeface = Typeface.create(family, style)
        textPaint.color = op.argb
        c.drawText(op.text, 0f, 0f, textPaint)
    }
}
