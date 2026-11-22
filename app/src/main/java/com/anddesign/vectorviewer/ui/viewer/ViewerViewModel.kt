package com.anddesign.vectorviewer.ui.viewer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anddesign.vectorviewer.data.Repos
import com.anddesign.vectorviewer.render.DocumentLoader
import com.anddesign.vectorviewer.render.LoadedDocument
import com.anddesign.vectorviewer.render.Rendering
import com.anddesign.vectorviewer.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ViewerViewModel(app: Application, private val uriString: String) : AndroidViewModel(app) {

    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
        data class Ready(val doc: LoadedDocument) : UiState
    }

    var state by mutableStateOf<UiState>(UiState.Loading)
        private set
    var page by mutableIntStateOf(0)
        private set
    /** Página completa a resolución media: se muestra mientras se hace zoom/paneo. */
    var base by mutableStateOf<Bitmap?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var loadJob: Job? = null
    private val loaded: LoadedDocument? get() = (state as? UiState.Ready)?.doc

    init {
        ensureLoaded()
    }

    fun ensureLoaded() {
        if (state is UiState.Ready || loadJob?.isActive == true) return
        state = UiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val d = DocumentLoader.load(getApplication(), Uri.parse(uriString))
                state = UiState.Ready(d)
                if (page >= d.document.pageCount) page = 0
                Repos.library.addRecent(uriString, d.name, d.format.badge)
                renderBase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = UiState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Libera memoria al salir del visor (el ViewModel queda vivo hasta que se cierre la actividad). */
    fun release() {
        loadJob?.cancel()
        loaded?.document?.close()
        base = null
        state = UiState.Loading
    }

    fun goToPage(p: Int) {
        val d = loaded ?: return
        if (p !in 0 until d.document.pageCount || p == page) return
        page = p
        base = null
        viewModelScope.launch { renderBase() }
    }

    private suspend fun renderBase() {
        val d = loaded ?: return
        val p = page
        val bmp = withContext(Dispatchers.Default) { Rendering.renderPage(d.document, p, BASE_SIDE) }
        if (p == page && loaded === d) base = bmp
    }

    /** Renderiza solo lo visible, a resolución de pantalla, para un zoom siempre nítido. */
    suspend fun renderViewport(p: Int, width: Int, height: Int, scale: Float, ox: Float, oy: Float): Bitmap? {
        val d = loaded ?: return null
        if (width <= 0 || height <= 0) return null
        return withContext(Dispatchers.Default) {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(ox, oy)
            }
            d.document.render(p, bmp, m)
            bmp
        }
    }

    /** Exporta la página actual a PNG transparente y devuelve un content:// para compartir. */
    suspend fun exportPng(context: Context, dpi: Int): Uri {
        val d = loaded ?: error("Documento no cargado")
        val p = page
        busy = true
        try {
            val file = withContext(Dispatchers.Default) {
                val size = d.document.pageSize(p)
                val scale = minOf(
                    dpi / 72f,
                    MAX_EXPORT_SIDE / max(size.width, size.height),
                    sqrt(MAX_EXPORT_PIXELS / (size.width * size.height)),
                )
                val bmp = Bitmap.createBitmap(
                    max(1, (size.width * scale).roundToInt()),
                    max(1, (size.height * scale).roundToInt()),
                    Bitmap.Config.ARGB_8888,
                )
                d.document.render(p, bmp, Matrix().apply { setScale(scale, scale) })
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val suffix = if (d.document.pageCount > 1) "_p${p + 1}" else ""
                val out = File(dir, "${FileUtils.safeBaseName(d.name)}${suffix}_${dpi}dpi.png")
                withContext(Dispatchers.IO) {
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                bmp.recycle()
                out
            }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } finally {
            busy = false
        }
    }

    /** content:// de la copia local, para "Compartir" y "Abrir con". */
    fun localFileUri(context: Context): Uri? {
        val d = loaded ?: return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", d.localFile)
        }.getOrNull()
    }

    override fun onCleared() {
        loaded?.document?.close()
    }

    private companion object {
        const val BASE_SIDE = 2048
        const val MAX_EXPORT_SIDE = 8000f
        const val MAX_EXPORT_PIXELS = 48_000_000f
    }
}
