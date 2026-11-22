package com.anddesign.vectorviewer.ui.folder

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.anddesign.vectorviewer.R
import com.anddesign.vectorviewer.format.VectorFormat
import com.anddesign.vectorviewer.ui.components.DocThumbnail
import com.anddesign.vectorviewer.ui.components.FormatBadge
import com.anddesign.vectorviewer.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class FolderEntry(
    val file: DocumentFile,
    val uri: Uri,
    val name: String,
    val isDir: Boolean,
    val modified: Long,
)

/** Galería de una carpeta (local o en la nube) con miniaturas, búsqueda y orden. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(treeUri: String, title: String, onOpenFile: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val root = remember(treeUri) { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }
    val stack = remember(treeUri) { mutableStateListOf<DocumentFile>().apply { root?.let { add(it) } } }
    val current = stack.lastOrNull()
    var query by rememberSaveable { mutableStateOf("") }
    var sortByDate by rememberSaveable { mutableStateOf(false) }

    val entries by produceState<List<FolderEntry>?>(null, current) {
        value = null
        value = current?.let { dir ->
            withContext(Dispatchers.IO) {
                runCatching {
                    dir.listFiles().mapNotNull { f ->
                        val name = f.name ?: return@mapNotNull null
                        val isDir = f.isDirectory
                        if (!isDir && !VectorFormat.isSupportedName(name)) return@mapNotNull null
                        FolderEntry(f, f.uri, name, isDir, f.lastModified())
                    }
                }.getOrDefault(emptyList())
            }
        } ?: emptyList()
    }

    fun goUp() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
    }
    BackHandler(enabled = stack.size > 1) { goUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.name ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { goUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { sortByDate = !sortByDate }) {
                        Icon(
                            if (sortByDate) Icons.Outlined.SortByAlpha else Icons.Outlined.Schedule,
                            contentDescription = stringResource(if (sortByDate) R.string.sort_name else R.string.sort_date),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_folder)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val list = entries
            val order = if (sortByDate) {
                compareByDescending<FolderEntry> { it.isDir }.thenByDescending { it.modified }
            } else {
                compareByDescending<FolderEntry> { it.isDir }.thenBy { it.name.lowercase() }
            }
            val filtered = list.orEmpty()
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .sortedWith(order)

            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_folder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.uri.toString() }) { e ->
                    Card(onClick = { if (e.isDir) stack.add(e.file) else onOpenFile(e.uri.toString()) }) {
                        Column(Modifier.padding(8.dp)) {
                            if (e.isDir) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = Accent, modifier = Modifier.size(56.dp))
                                }
                            } else {
                                DocThumbnail(e.uri.toString(), Modifier.fillMaxWidth().aspectRatio(1f), version = e.modified)
                            }
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (!e.isDir) FormatBadge(VectorFormat.fromExtension(e.name).badge, Modifier.padding(end = 6.dp))
                                Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
