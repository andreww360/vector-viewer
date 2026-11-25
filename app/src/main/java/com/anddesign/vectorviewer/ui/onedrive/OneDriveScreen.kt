package com.anddesign.vectorviewer.ui.onedrive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anddesign.vectorviewer.R
import com.anddesign.vectorviewer.data.OneDrive
import com.anddesign.vectorviewer.data.Repos
import com.anddesign.vectorviewer.format.VectorFormat
import com.anddesign.vectorviewer.ui.components.FormatBadge
import com.anddesign.vectorviewer.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL

private data class Crumb(val id: String, val name: String)

/** OneDrive dentro de la app: carpetas, miniaturas, búsqueda en todo el OneDrive y carpetas favoritas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneDriveScreen(folderId: String, title: String, onOpenFile: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val rootName = stringResource(R.string.onedrive_root)
    val stack = remember(folderId) { mutableStateListOf(Crumb(folderId, title.ifBlank { rootName })) }
    val current = stack.last()
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val account by OneDrive.account.collectAsStateWithLifecycle()
    val favorites by Repos.library.favoriteFolders.collectAsStateWithLifecycle()
    val folderUri = "${OneDrive.SCHEME}://folder/${current.id}"
    val isFavorite = favorites.any { it.uri == folderUri }
    val pathLabel = (listOf("OneDrive") + stack.map { it.name }.filter { it != rootName }).joinToString(" / ")
    val addedMsg = stringResource(R.string.folder_fav_added)
    val removedMsg = stringResource(R.string.folder_fav_removed)

    // Estado: null = cargando
    val result by produceState<Result<List<OneDrive.Item>>?>(null, current.id, query, reload) {
        value = null
        val q = query.trim()
        value = runCatching {
            if (q.length >= 2) {
                delay(350) // espera a que termine de escribir
                OneDrive.search(context, q)
            } else {
                OneDrive.children(context, current.id)
            }
        }
    }

    fun goUp() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
    }
    BackHandler(enabled = stack.size > 1) { goUp() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            account?.email?.ifBlank { null } ?: pathLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { goUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        Repos.library.toggleFavoriteFolder(folderUri, current.name, pathLabel)
                        Toast.makeText(context, if (isFavorite) removedMsg else addedMsg, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.folder_favorite),
                            tint = if (isFavorite) Accent else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = {
                        OneDrive.signOut(context)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = stringResource(R.string.onedrive_sign_out))
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
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear)) }
                    }
                },
                placeholder = { Text(stringResource(R.string.onedrive_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val r = result
            val items = r?.getOrNull()
            when {
                r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                r.isFailure -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        r.exceptionOrNull()?.message ?: stringResource(R.string.onedrive_error),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.TextButton(onClick = { reload++ }) { Text(stringResource(R.string.retry)) }
                }
                items.isNullOrEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) stringResource(R.string.empty_folder) else stringResource(R.string.no_results, query),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> {
                    val sorted = items.sortedWith(compareByDescending<OneDrive.Item> { it.isFolder }.thenBy { it.name.lowercase() })
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(sorted, key = { it.id }) { item ->
                            ItemCard(item, showPath = query.isNotBlank()) {
                                if (item.isFolder) {
                                    query = ""
                                    stack.add(Crumb(item.id, item.name))
                                } else {
                                    onOpenFile(item.fileUri)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCard(item: OneDrive.Item, showPath: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (item.isFolder) Color.Transparent else Color(0xFFF1F1F1)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.isFolder) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = Accent, modifier = Modifier.size(56.dp))
                } else {
                    RemoteThumbnail(item.thumbnailUrl)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!item.isFolder) FormatBadge(VectorFormat.fromExtension(item.name).badge, Modifier.padding(end = 6.dp))
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            val sub = when {
                showPath && item.path.isNotBlank() -> item.path
                item.modified > 0 -> DateUtils.getRelativeTimeSpanString(item.modified).toString()
                else -> ""
            }
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val thumbCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Miniatura generada por OneDrive (no descarga el archivo completo). */
@Composable
private fun RemoteThumbnail(url: String?) {
    val bmp by produceState<Bitmap?>(url?.let { thumbCache.get(it) }, url) {
        if (url != null && value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }?.also { thumbCache.put(url, it) }
        }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
    } else {
        Icon(Icons.Outlined.Draw, contentDescription = null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(28.dp))
    }
}
