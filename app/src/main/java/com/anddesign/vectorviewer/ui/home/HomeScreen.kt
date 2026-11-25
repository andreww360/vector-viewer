package com.anddesign.vectorviewer.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Search
import com.anddesign.vectorviewer.data.FileIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anddesign.vectorviewer.R
import com.anddesign.vectorviewer.data.RecentFile
import com.anddesign.vectorviewer.data.Repos
import com.anddesign.vectorviewer.data.SavedFolder
import com.anddesign.vectorviewer.ui.components.DocThumbnail
import com.anddesign.vectorviewer.ui.components.FormatBadge
import com.anddesign.vectorviewer.ui.theme.Accent

private const val PKG_DRIVE = "com.google.android.apps.docs"
private const val PKG_ONEDRIVE = "com.microsoft.skydrive"

@Composable
fun HomeScreen(onOpenFile: (String) -> Unit, onOpenFolder: (String, String) -> Unit, onOpenSearch: () -> Unit) {
    val context = LocalContext.current
    val recents by Repos.library.recents.collectAsStateWithLifecycle()
    val folders by Repos.library.folders.collectAsStateWithLifecycle()

    // Mantiene el índice de búsqueda global al día en segundo plano
    LaunchedEffect(folders) { if (folders.isNotEmpty()) FileIndex.ensureFresh(context) }

    // Selector del sistema (SAF): muestra Teléfono, SD, USB, Google Drive, OneDrive, Dropbox…
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            onOpenFile(uri.toString())
        }
    }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Carpeta"
            Repos.library.addFolder(uri.toString(), name)
            onOpenFolder(uri.toString(), name)
        }
    }

    fun openFromCloud(pkg: String, @StringRes hint: Int) {
        if (!isInstalled(context, pkg)) {
            openPlayStore(context, pkg)
            return
        }
        Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
        // "*/*": muchos proveedores reportan .ai / .cdr como application/octet-stream
        openDoc.launch(arrayOf("*/*"))
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Header() }
            item { SearchBarButton(onOpenSearch) }

            item { SectionTitle(R.string.open_from) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SourceCard(Icons.Outlined.PhoneAndroid, R.string.src_phone, R.string.src_phone_sub, Modifier.weight(1f)) {
                            openDoc.launch(arrayOf("*/*"))
                        }
                        SourceCard(Icons.Outlined.AddToDrive, R.string.src_drive, R.string.src_drive_sub, Modifier.weight(1f)) {
                            openFromCloud(PKG_DRIVE, R.string.hint_drive)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SourceCard(Icons.Outlined.Cloud, R.string.src_onedrive, R.string.src_onedrive_sub, Modifier.weight(1f)) {
                            openFromCloud(PKG_ONEDRIVE, R.string.hint_onedrive)
                        }
                        SourceCard(Icons.Outlined.FolderOpen, R.string.src_folder, R.string.src_folder_sub, Modifier.weight(1f)) {
                            openTree.launch(null)
                        }
                    }
                    Text(
                        stringResource(R.string.other_providers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (folders.isNotEmpty()) {
                item { SectionTitle(R.string.saved_folders) }
                items(folders, key = { "folder:" + it.uri }) { f ->
                    FolderRow(f, onClick = { onOpenFolder(f.uri, f.name) }, onRemove = { Repos.library.removeFolder(f.uri) })
                }
            }

            val favorites = recents.filter { it.favorite }
            val others = recents.filterNot { it.favorite }
            if (favorites.isNotEmpty()) {
                item { SectionTitle(R.string.favorites) }
                items(favorites, key = { "fav:" + it.uri }) { r -> RecentRow(r, onOpenFile) }
            }
            item { SectionTitle(R.string.recents) }
            if (others.isEmpty() && favorites.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_recents),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(others, key = { "recent:" + it.uri }) { r -> RecentRow(r, onOpenFile) }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(bottom = 8.dp)) {
        Image(
            painter = painterResource(R.drawable.logo_and_dsgn),
            contentDescription = "AND DSGN",
            modifier = Modifier.height(56.dp).padding(bottom = 8.dp),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchBarButton(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.search_all), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(@StringRes res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourceCard(icon: ImageVector, @StringRes title: Int, @StringRes subtitle: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(title), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                stringResource(subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun FolderRow(folder: SavedFolder, onClick: () -> Unit, onRemove: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null, tint = Accent) },
            headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove))
                }
            },
        )
    }
}

@Composable
private fun RecentRow(item: RecentFile, onOpenFile: (String) -> Unit) {
    Card(onClick = { onOpenFile(item.uri) }, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { DocThumbnail(item.uri, Modifier.size(56.dp)) },
            headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormatBadge(item.format)
                    Text(
                        "  " + DateUtils.getRelativeTimeSpanString(item.openedAt).toString(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            trailingContent = {
                Row {
                    IconButton(onClick = { Repos.library.toggleFavorite(item.uri) }) {
                        Icon(
                            if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.favorite),
                            tint = if (item.favorite) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { Repos.library.removeRecent(item.uri) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove))
                    }
                }
            },
        )
    }
}

private fun persistRead(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun isInstalled(context: Context, pkg: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

private fun openPlayStore(context: Context, pkg: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
    }
}
