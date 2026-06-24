package app.safetake

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.safetake.crypto.SessionVault
import kotlinx.coroutines.launch
import app.safetake.ui.CameraScreen
import app.safetake.ui.GalleryScreen
import app.safetake.ui.LockScreen
import app.safetake.ui.SafeTakeTheme
import app.safetake.ui.SettingsScreen
import app.safetake.ui.ViewerScreen

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val app = application as SafeTakeApp
        setContent {
            SafeTakeTheme {
                val unlocked by SessionVault.unlocked.collectAsState()
                if (!unlocked) {
                    LockScreen(app)
                } else {
                    SafeTakeNav(app)
                }
            }
        }
    }
}

@Composable
private fun SafeTakeNav(app: SafeTakeApp) {
    val nav = rememberNavController()
    // Hosted at the nav level (not per-screen) so an Undo snackbar started in the
    // viewer survives the pop back to the gallery and stays interactive there.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun deleteWithUndo(ids: Set<String>) {
        if (ids.isEmpty()) return
        app.repository.requestDelete(ids) // hide now; commit only if not undone
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = if (ids.size == 1) "Item deleted" else "${ids.size} items deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) app.repository.undoDelete(ids)
            else app.repository.commitDelete(ids)
        }
    }

    NavHost(navController = nav, startDestination = "gallery") {
        composable("gallery") {
            GalleryScreen(
                repository = app.repository,
                snackbarHostState = snackbarHostState,
                onOpenCamera = { nav.navigate("camera") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenItem = { id -> nav.navigate("viewer/$id") },
                onDelete = ::deleteWithUndo,
            )
        }
        composable("camera") {
            CameraScreen(
                repository = app.repository,
                onClose = { nav.popBackStack() },
            )
        }
        composable("viewer/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ViewerScreen(
                repository = app.repository,
                itemId = id,
                onClose = { nav.popBackStack() },
                onDelete = ::deleteWithUndo,
            )
        }
        composable("settings") {
            SettingsScreen(
                app = app,
                onClose = { nav.popBackStack() },
            )
        }
    }
}
