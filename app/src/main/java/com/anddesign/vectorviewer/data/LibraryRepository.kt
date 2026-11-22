package com.anddesign.vectorviewer.data

import android.content.Context
import com.anddesign.vectorviewer.util.Backdrop
import com.anddesign.vectorviewer.util.LengthUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: String,
    val name: String,
    val format: String,
    val openedAt: Long,
    val favorite: Boolean = false,
)

data class SavedFolder(val uri: String, val name: String)

object Repos {
    lateinit var library: LibraryRepository
        private set

    fun init(context: Context) {
        library = LibraryRepository(context.applicationContext)
    }
}

/** Recientes, favoritos, carpetas guardadas y preferencias. Persistido en SharedPreferences. */
class LibraryRepository(context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _recents = MutableStateFlow(loadRecents())
    val recents: StateFlow<List<RecentFile>> = _recents.asStateFlow()

    private val _folders = MutableStateFlow(loadFolders())
    val folders: StateFlow<List<SavedFolder>> = _folders.asStateFlow()

    var unit: LengthUnit
        get() = runCatching { LengthUnit.valueOf(prefs.getString(KEY_UNIT, null) ?: "") }.getOrDefault(LengthUnit.MM)
        set(value) = prefs.edit().putString(KEY_UNIT, value.name).apply()

    var backdrop: Backdrop
        get() = runCatching { Backdrop.valueOf(prefs.getString(KEY_BACKDROP, null) ?: "") }.getOrDefault(Backdrop.CHECKER)
        set(value) = prefs.edit().putString(KEY_BACKDROP, value.name).apply()

    fun addRecent(uri: String, name: String, format: String) {
        _recents.update { list ->
            val fav = list.firstOrNull { it.uri == uri }?.favorite ?: false
            (listOf(RecentFile(uri, name, format, System.currentTimeMillis(), fav)) +
                list.filterNot { it.uri == uri }).take(MAX_RECENTS)
        }
        saveRecents()
    }

    fun toggleFavorite(uri: String) {
        _recents.update { list -> list.map { if (it.uri == uri) it.copy(favorite = !it.favorite) else it } }
        saveRecents()
    }

    fun removeRecent(uri: String) {
        _recents.update { list -> list.filterNot { it.uri == uri } }
        saveRecents()
    }

    fun addFolder(uri: String, name: String) {
        _folders.update { list -> listOf(SavedFolder(uri, name)) + list.filterNot { it.uri == uri } }
        saveFolders()
    }

    fun removeFolder(uri: String) {
        _folders.update { list -> list.filterNot { it.uri == uri } }
        saveFolders()
    }

    private fun loadRecents(): List<RecentFile> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_RECENTS, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            RecentFile(
                uri = o.getString("uri"),
                name = o.getString("name"),
                format = o.optString("format"),
                openedAt = o.optLong("openedAt"),
                favorite = o.optBoolean("favorite"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveRecents() {
        val arr = JSONArray()
        _recents.value.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri).put("name", it.name).put("format", it.format)
                    .put("openedAt", it.openedAt).put("favorite", it.favorite)
            )
        }
        prefs.edit().putString(KEY_RECENTS, arr.toString()).apply()
    }

    private fun loadFolders(): List<SavedFolder> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_FOLDERS, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SavedFolder(o.getString("uri"), o.getString("name"))
        }
    }.getOrDefault(emptyList())

    private fun saveFolders() {
        val arr = JSONArray()
        _folders.value.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }

    private companion object {
        const val MAX_RECENTS = 60
        const val KEY_RECENTS = "recents"
        const val KEY_FOLDERS = "folders"
        const val KEY_UNIT = "unit"
        const val KEY_BACKDROP = "backdrop"
    }
}
