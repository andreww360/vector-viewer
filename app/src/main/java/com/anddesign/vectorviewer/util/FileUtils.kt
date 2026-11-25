package com.anddesign.vectorviewer.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.anddesign.vectorviewer.data.OneDrive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

object FileUtils {

    data class Meta(val name: String, val size: Long)

    fun key(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(20)

    fun meta(context: Context, uri: Uri): Meta {
        var name: String? = null
        var size = -1L
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            }
        } else if (uri.scheme == OneDrive.SCHEME) {
            name = uri.getQueryParameter("name")
            size = uri.getQueryParameter("size")?.toLongOrNull() ?: -1L
        } else if (uri.scheme == "file") {
            uri.path?.let { p -> File(p).let { f -> name = f.name; size = f.length() } }
        }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        return Meta(finalName, size)
    }

    private fun cacheFileFor(context: Context, uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        return File(dir, key(uri.toString()) + "_" + safe)
    }

    /**
     * Copia el documento a la caché local. Necesario porque PdfRenderer exige un
     * descriptor "seekable" y los proveedores en la nube suelen entregar un stream.
     * Si el origen ya no es accesible (permiso vencido, sin conexión) se usa la copia previa.
     */
    fun copyToCache(context: Context, uri: Uri, name: String): File {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: throw IOException("Ruta inválida"))
            if (f.canRead()) return f
        }
        if (uri.scheme == OneDrive.SCHEME) {
            val id = OneDrive.itemIdFromUri(uri) ?: throw IOException("Archivo de OneDrive no válido")
            return OneDrive.downloadToCache(context, id, name, uri.getQueryParameter("mod")?.toLongOrNull() ?: 0L)
        }
        val target = cacheFileFor(context, uri, name)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("El proveedor no devolvió datos")
            val tmp = File(target.path + ".tmp")
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            if (target.exists() && target.length() > 0) return target
            throw e
        }
        return target
    }

    fun formatBytes(n: Long): String {
        if (n < 0) return "—"
        if (n < 1024) return "$n B"
        val kb = n / 1024.0
        if (kb < 1024) return "%.0f KB".format(Locale.US, kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(Locale.US, mb)
        return "%.2f GB".format(Locale.US, mb / 1024.0)
    }

    fun safeBaseName(name: String): String =
        name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "export" }
}
