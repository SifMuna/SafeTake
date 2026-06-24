package app.safetake

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import app.safetake.crypto.SessionVault
import app.safetake.ui.LockScreen
import app.safetake.ui.SafeTakeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Share-sheet target: the only way media gets into the vault from other apps.
 * Requires the PIN if the vault is locked, then stores each shared item per
 * the encryption setting.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val app = application as SafeTakeApp
        val uris = extractUris()
        if (uris.isEmpty()) {
            finish()
            return
        }
        setContent {
            SafeTakeTheme {
                val unlocked by SessionVault.unlocked.collectAsState()
                if (!unlocked) {
                    LockScreen(app)
                } else {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Adding to SafeTake…")
                        }
                    }
                    LaunchedEffect(Unit) {
                        val imported = withContext(Dispatchers.IO) {
                            uris.count { uri ->
                                runCatching { app.repository.importShared(uri) }.isSuccess
                            }
                        }
                        val failed = uris.size - imported
                        Toast.makeText(
                            this@ShareReceiverActivity,
                            if (failed == 0) "$imported item(s) added to SafeTake"
                            else "$imported added, $failed failed",
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }
            }
        }
    }

    private fun extractUris(): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                .orEmpty().filterNotNull()
        else -> emptyList()
    }
}
