package com.anddesign.vectorviewer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.anddesign.vectorviewer.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Miniaturas en memoria (LRU) + disco. Máx. 2 renders en paralelo para no saturar el teléfono. */
object ThumbnailCache {

    private const val MAX_BYTES_FOR_THUMB = 80L * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(2)

    private fun cacheKey(uri: String, version: Long) = "$uri#$version"

    fun peek(uri: String, version: Long = 0L): Bitmap? = memory.get(cacheKey(uri, version))

    suspend fun load(context: Context, uri: String, version: Long = 0L, maxSide: Int = 360): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = cacheKey(uri, version)
            memory.get(key)?.let { return@withContext it }

            val disk = File(context.cacheDir, "thumbs/${FileUtils.key(key)}.png")
            if (disk.exists()) {
                BitmapFactory.decodeFile(disk.path)?.let {
                    memory.put(key, it)
                    return@withContext it
                }
            }

            gate.withPermit {
                val bmp = runCatching {
                    val loaded = DocumentLoader.load(context, Uri.parse(uri), MAX_BYTES_FOR_THUMB)
                    loaded.document.use { doc -> Rendering.renderPage(doc, 0, maxSide) }
                }.getOrNull()
                if (bmp != null) {
                    memory.put(key, bmp)
                    disk.parentFile?.mkdirs()
                    runCatching { disk.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                bmp
            }
        }
}
