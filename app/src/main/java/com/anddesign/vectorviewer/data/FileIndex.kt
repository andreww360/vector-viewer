package com.anddesign.vectorviewer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.anddesign.vectorviewer.format.VectorFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.coroutines.coroutineContext

/** Un archivo vectorial encontrado dentro de las carpetas guardadas. */
data class IndexedFile(
    val uri: String,
    val name: String,
    val folder: String,
    val path: String,
    val modified: Long,
    val size: Long,
) {
    val nameKey: String by lazy { FileIndex.normalize(name) }
    val searchKey: String by lazy { FileIndex.normalize("$name $folder $path") }
}

data class IndexState(
    val files: List<IndexedFile> = emptyList(),
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val updatedAt: Long = 0L,
    val error: String? = null,
)

/**
 * Índice de búsqueda global: recorre todas las carpetas guardadas (y sus subcarpetas)
 * con consultas directas a DocumentsContract, mucho más rápidas que DocumentFile.
 * El índice se guarda en disco, así que la búsqueda es instantánea al abrir la app
 * y se actualiza en segundo plano.
 */
object FileIndex {

    private const val MAX_FILES = 50_000
    private const val MAX_DEPTH = 15
    private const val STALE_MS = 10 * 60 * 1000L
    private const val MAX_RESULTS = 500

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()

    private var job: Job? = null
    private var cacheLoaded = false
    private var indexedKey = ""

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    /** Actualiza el índice si está desactualizado, si cambiaron las carpetas o si [force]. */
    fun ensureFresh(context: Context, force: Boolean = false) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            if (!cacheLoaded) {
                cacheLoaded = true
                loadCache(app)
            }
            val folders = Repos.library.folders.value
            val key = folders.joinToString("|") { it.uri }
            val stale = System.currentTimeMillis() - _state.value.updatedAt > STALE_MS
            if (force || stale || key != indexedKey) rebuild(app, folders, key)
        }
    }

    private suspend fun rebuild(context: Context, folders: List<SavedFolder>, key: String) {
        _state.update { it.copy(scanning = true, scannedCount = 0, error = null) }
        val out = ArrayList<IndexedFile>()
        val resolver = context.contentResolver
        try {
            for (folder in folders) {
                val tree = Uri.parse(folder.uri)
                val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
                val queue = ArrayDeque<Triple<String, String, Int>>()
                queue.add(Triple(rootId, "", 0))
                while (queue.isNotEmpty() && out.size < MAX_FILES) {
                    coroutineContext.ensureActive()
                    val (docId, path, depth) = queue.removeFirst()
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                    val before = out.size
                    runCatching {
                        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
                            while (c.moveToNext()) {
                                val id = c.getString(0)
                                val name = c.getString(1)
                                val mime = c.getString(2)
                                if (id != null && name != null) {
                                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                        if (depth < MAX_DEPTH && !name.startsWith(".")) {
                                            queue.add(Triple(id, if (path.isEmpty()) name else "$path / $name", depth + 1))
                                        }
                                    } else if (VectorFormat.isSupportedName(name)) {
                                        out.add(
                                            IndexedFile(
                                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                                                name = name,
                                                folder = folder.name,
                                                path = path,
                                                modified = if (c.isNull(3)) 0L else c.getLong(3),
                                                size = if (c.isNull(4)) -1L else c.getLong(4),
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (out.size != before) _state.update { it.copy(scannedCount = out.size) }
                }
            }
            val now = System.currentTimeMillis()
            val files = out.toList()
            indexedKey = key
            _state.value = IndexState(files = files, scanning = false, scannedCount = files.size, updatedAt = now)
            saveCache(context, files, now, key)
        } catch (e: CancellationException) {
            _state.update { it.copy(scanning = false) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(scanning = false, error = e.message) }
        }
    }

    /**
     * Todas las palabras deben aparecer en el nombre, la carpeta o la ruta (sin importar
     * mayúsculas ni tildes). Primero las coincidencias en el nombre, luego las más nuevas.
     */
    fun search(files: List<IndexedFile>, query: String, format: VectorFormat?): List<IndexedFile> {
        val terms = normalize(query).split(SPACES).filter { it.isNotBlank() }
        val filtered = files.asSequence()
            .filter { format == null || VectorFormat.fromExtension(it.name) == format }
            .filter { f -> terms.all { f.searchKey.contains(it) } }
        val sorted = if (terms.isEmpty()) {
            filtered.sortedByDescending { it.modified }
        } else {
            filtered.sortedWith(
                compareBy<IndexedFile> { f -> if (terms.all { f.nameKey.contains(it) }) 0 else 1 }
                    .thenByDescending { it.modified }
            )
        }
        return sorted.take(MAX_RESULTS).toList()
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "search_index.json")

    private fun loadCache(context: Context) {
        runCatching {
            val f = cacheFile(context)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("files")
            val files = List(arr.length()) { i ->
                val a = arr.getJSONArray(i)
                IndexedFile(a.getString(0), a.getString(1), a.getString(2), a.getString(3), a.getLong(4), a.getLong(5))
            }
            indexedKey = o.optString("key")
            _state.value = IndexState(files = files, scannedCount = files.size, updatedAt = o.optLong("updatedAt"))
        }
    }

    private fun saveCache(context: Context, files: List<IndexedFile>, updatedAt: Long, key: String) {
        runCatching {
            val arr = JSONArray()
            files.forEach { f ->
                arr.put(JSONArray().put(f.uri).put(f.name).put(f.folder).put(f.path).put(f.modified).put(f.size))
            }
            val o = JSONObject().put("updatedAt", updatedAt).put("key", key).put("files", arr)
            cacheFile(context).writeText(o.toString())
        }
    }
}
