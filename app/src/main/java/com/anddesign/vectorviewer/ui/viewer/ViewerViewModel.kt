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
import com.anddesign.vectorviewer.render.Exporter
import com.anddesign.vectorviewer.render.LoadedDocument
import com.anddesign.vectorviewer.render.Rendering
import com.anddesign.vectorviewer.util.LengthUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    private suspend fun <T> working(block: suspend () -> T): T {
        busy = true
        try {
            return withContext(Dispatchers.Default) { block() }
        } finally {
            busy = false
        }
    }

    private fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** PNG transparente de la página actual. */
    suspend fun exportPng(context: Context, dpi: Int): Uri {
        val d = loaded ?: error("Documento no cargado")
        val p = page
        return shareUri(context, working { Exporter.exportPng(context, d, p, dpi, suffix = "_${dpi}dpi") })
    }

    /** PDF a escala real; el archivo se usa también para imprimir. */
    suspend fun exportPdfFile(context: Context): File {
        val d = loaded ?: error("Documento no cargado")
        val p = page
        return working { Exporter.exportPdf(context, d, p) }
    }

    fun uriFor(context: Context, file: File): Uri = shareUri(context, file)

    /** Imagen con fondo blanco y tamaño moderado (máx. 2400 px) para que Claude la analice. */
    suspend fun exportForClaude(context: Context): Uri {
        val d = loaded ?: error("Documento no cargado")
        val p = page
        return shareUri(context, working { Exporter.exportPng(context, d, p, dpi = 200, maxSide = 2400f, opaque = true, suffix = "_claude") })
    }

    /** Texto de contexto que acompaña la imagen enviada a Claude. */
    fun claudeContext(unit: LengthUnit): String {
        val d = loaded ?: return ""
        val s = d.document.pageSize(page)
        val size = if (d.document.hasRealScale) unit.formatSize(s.width.toDouble(), s.height.toDouble()) else "tamaño desconocido"
        return "Archivo: ${d.name} (${d.format.label}). Mesa de trabajo: $size."
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
    }
}
