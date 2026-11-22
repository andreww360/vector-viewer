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
import com.anddesign.vectorviewer.ui.folder.FolderScreen
import com.anddesign.vectorviewer.ui.home.HomeScreen
import com.anddesign.vectorviewer.ui.theme.VectorViewerTheme
import com.anddesign.vectorviewer.ui.viewer.ViewerScreen

sealed interface Screen {
    data object Home : Screen
    data class Viewer(val uri: String) : Screen
    data class Folder(val treeUri: String, val name: String) : Screen
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
    val screen = nav.stack.last()
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    when (screen) {
        Screen.Home -> HomeScreen(
            onOpenFile = { nav.push(Screen.Viewer(it)) },
            onOpenFolder = { uri, name -> nav.push(Screen.Folder(uri, name)) },
        )
        is Screen.Viewer -> ViewerScreen(screen.uri, onBack = { nav.pop() })
        is Screen.Folder -> FolderScreen(
            treeUri = screen.treeUri,
            title = screen.name,
            onOpenFile = { nav.push(Screen.Viewer(it)) },
            onBack = { nav.pop() },
        )
    }
}
