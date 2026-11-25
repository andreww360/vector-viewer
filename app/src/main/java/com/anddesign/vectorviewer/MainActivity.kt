package com.anddesign.vectorviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.anddesign.vectorviewer.data.OneDrive
import com.anddesign.vectorviewer.ui.folder.FolderScreen
import com.anddesign.vectorviewer.ui.onedrive.OneDriveScreen
import kotlinx.coroutines.launch
import com.anddesign.vectorviewer.ui.home.HomeScreen
import com.anddesign.vectorviewer.ui.search.SearchScreen
import com.anddesign.vectorviewer.ui.theme.VectorViewerTheme
import com.anddesign.vectorviewer.ui.viewer.ViewerScreen

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data class Viewer(val uri: String) : Screen
    data class Folder(val treeUri: String, val name: String) : Screen
    data class OneDriveFolder(val folderId: String, val name: String) : Screen
}

/** Navegación simple por pila; sobrevive a la rotación. */
class AppNavViewModel : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Home)

    fun push(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

class MainActivity : ComponentActivity() {

    private val nav: AppNavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            VectorViewerTheme {
                AppRoot(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Abrir con…" / "Compartir con…" desde otras apps (Archivos, Drive, Gmail, WhatsApp…). */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Regreso del inicio de sesión de Microsoft (msauth://…)
        if (OneDrive.isRedirect(intent.data)) {
            val redirect = intent.data ?: return
            lifecycleScope.launch {
                val error = OneDrive.completeSignIn(this@MainActivity, redirect)
                if (error == null) {
                    nav.push(Screen.OneDriveFolder("root", ""))
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.onedrive_error) + ": " + error, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) nav.push(Screen.Viewer(uri.toString()))
    }
}

@Composable
private fun AppRoot(nav: AppNavViewModel) {
    val context = LocalContext.current
    val screen = nav.stack.last()

    fun openFolder(uri: String, name: String) {
        if (uri.startsWith("${OneDrive.SCHEME}://folder/")) {
            nav.push(Screen.OneDriveFolder(uri.substringAfter("://folder/"), name.substringAfterLast(" / ")))
        } else {
            nav.push(Screen.Folder(uri, name))
        }
    }

    fun openOneDrive() {
        if (OneDrive.isSignedIn(context)) nav.push(Screen.OneDriveFolder("root", "")) else OneDrive.startSignIn(context)
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    when (screen) {
        Screen.Home -> HomeScreen(
            onOpenFile = { nav.push(Screen.Viewer(it)) },
            onOpenFolder = { uri, name -> openFolder(uri, name) },
            onOpenSearch = { nav.push(Screen.Search) },
            onOpenOneDrive = { openOneDrive() },
        )
        Screen.Search -> SearchScreen(
            onOpenFile = { nav.push(Screen.Viewer(it)) },
            onBack = { nav.pop() },
        )
        is Screen.Viewer -> ViewerScreen(screen.uri, onBack = { nav.pop() })
        is Screen.OneDriveFolder -> OneDriveScreen(
            folderId = screen.folderId,
            title = screen.name,
            onOpenFile = { nav.push(Screen.Viewer(it)) },
            onBack = { nav.pop() },
        )
        is Screen.Folder -> FolderScreen(
            treeUri = screen.treeUri,
            title = screen.name,
            onOpenFile = { nav.push(Screen.Viewer(it)) },
            onOpenSearch = { nav.push(Screen.Search) },
            onBack = { nav.pop() },
        )
    }
}
