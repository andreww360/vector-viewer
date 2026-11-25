package com.anddesign.vectorviewer.ui.search

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anddesign.vectorviewer.R
import com.anddesign.vectorviewer.data.FileIndex
import com.anddesign.vectorviewer.data.IndexedFile
import com.anddesign.vectorviewer.data.Repos
import com.anddesign.vectorviewer.format.VectorFormat
import com.anddesign.vectorviewer.ui.components.DocThumbnail
import com.anddesign.vectorviewer.ui.components.FormatBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val FORMAT_FILTERS = listOf("AI", "PDF", "EPS", "SVG", "CDR", "DXF", "PLT")

/** Búsqueda global en todas las carpetas guardadas (con subcarpetas) y en los recientes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenFile: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val index by FileIndex.state.collectAsStateWithLifecycle()
    val folders by Repos.library.folders.collectAsStateWithLifecycle()
    val recents by Repos.library.recents.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var formatFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val recentsLabel = stringResource(R.string.recents)

    LaunchedEffect(folders) { FileIndex.ensureFresh(context) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Carpeta"
            Repos.library.addFolder(uri.toString(), name)
        }
    }

    // Índice de carpetas + recientes que no estén dentro de ellas (p. ej. abiertos desde Drive)
    val all = remember(index.files, recents, recentsLabel) {
        val known = index.files.mapTo(HashSet()) { it.uri }
        index.files + recents.filter { it.uri !in known }.map {
            IndexedFile(it.uri, it.name, recentsLabel, "", it.openedAt, -1L)
        }
    }

    val results by produceState<List<IndexedFile>?>(null, all, query, formatFilter) {
        delay(120)
        val format = formatFilter?.let { VectorFormat.fromBadge(it) }
        value = withContext(Dispatchers.Default) { FileIndex.search(all, query, format) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
                        }
                    }
                    IconButton(onClick = { FileIndex.ensureFresh(context, force = true) }, enabled = !index.scanning) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh_index))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (index.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = formatFilter == null,
                        onClick = { formatFilter = null },
                        label = { Text(stringResource(R.string.filter_all)) },
                    )
                }
                items(FORMAT_FILTERS) { badge ->
                    FilterChip(
                        selected = formatFilter == badge,
                        onClick = { formatFilter = if (formatFilter == badge) null else badge },
                        label = { Text(badge) },
                    )
                }
            }

            Text(
                text = if (index.scanning) {
                    stringResource(R.string.indexing, index.scannedCount)
                } else {
                    stringResource(R.string.index_status, index.files.size, folders.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            val list = results
            when {
                folders.isEmpty() && recents.isEmpty() -> NoFolders { addFolder.launch(null) }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        if (query.isBlank()) stringResource(R.string.empty_folder) else stringResource(R.string.no_results, query),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (folders.isEmpty()) {
                        Button(onClick = { addFolder.launch(null) }) { Text(stringResource(R.string.add_folder)) }
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(list, key = { it.uri }) { f -> ResultRow(f) { onOpenFile(f.uri) } }
                    if (folders.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { addFolder.launch(null) }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                                Text("  " + stringResource(R.string.add_folder))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(f: IndexedFile, onClick: () -> Unit) {
    val location = listOf(f.folder, f.path).filter { it.isNotBlank() }.joinToString(" / ")
    val date = if (f.modified > 0) " · " + DateUtils.getRelativeTimeSpanString(f.modified).toString() else ""
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { DocThumbnail(f.uri, Modifier.size(52.dp), version = f.modified) },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(VectorFormat.fromExtension(f.name).badge, Modifier.padding(end = 6.dp))
                Text(
                    location + date,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

@Composable
private fun NoFolders(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.search_no_folders),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd) { Text(stringResource(R.string.add_folder)) }
    }
}
