package app.safetake

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.safetake.crypto.SessionVault
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
    NavHost(navController = nav, startDestination = "gallery") {
        composable("gallery") {
            GalleryScreen(
                repository = app.repository,
                onOpenCamera = { nav.navigate("camera") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenItem = { id -> nav.navigate("viewer/$id") },
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
