package app.safetake.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.safetake.SafeTakeApp
import app.safetake.crypto.SessionVault
import app.safetake.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: SafeTakeApp, onClose: () -> Unit) {
    var changingCredential by remember { mutableStateOf(false) }
    if (changingCredential) {
        ChangeCredentialScreen(app, onClose = { changingCredential = false })
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val credentialType by app.prefs.credentialType.collectAsState()
    val showPinLength by app.prefs.showPinLength.collectAsState()

    // Biometric unlock: only surfaced when the device actually has strong biometrics.
    val biometricCapable = remember { BiometricAuth.canAuthenticate(context) }
    var biometricOn by remember { mutableStateOf(app.biometricVault.isEnabled) }

    fun setBiometric(want: Boolean) {
        val activity = context.findFragmentActivity()
        val dek = SessionVault.keyOrNull()
        if (!want) {
            app.biometricVault.disable()
            biometricOn = false
            return
        }
        if (activity == null || dek == null) return
        val cipher = runCatching { app.biometricVault.encryptCipher() }.getOrNull() ?: run {
            Toast.makeText(context, "Biometrics unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        BiometricAuth.authenticate(
            activity = activity,
            cipher = cipher,
            title = "Enable biometric unlock",
            subtitle = "Confirm it's you",
            onSuccess = { authorized ->
                scope.launch {
                    withContext(Dispatchers.IO) { app.biometricVault.storeWrappedDek(authorized, dek) }
                    biometricOn = true
                    Toast.makeText(context, "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { /* cancelled; switch stays off */ },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (credentialType == Prefs.TYPE_PIN) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show PIN length", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "The lock screen shows how many digits your PIN has.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = showPinLength,
                        onCheckedChange = { app.prefs.setShowPinLength(it) },
                    )
                }
                HorizontalDivider()
            }

            if (biometricCapable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unlock with biometrics", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Use your fingerprint or face to unlock, in addition to your " +
                                "PIN or password. Your PIN always still works.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = biometricOn,
                        onCheckedChange = { setBiometric(it) },
                    )
                }
                HorizontalDivider()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { changingCredential = true }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text("Change PIN or password", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Your media is untouched; only the lock changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

private enum class ChangeStage { Current, Choose, New, Repeat }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeCredentialScreen(app: SafeTakeApp, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentType by app.prefs.credentialType.collectAsState()
    val currentPinLength by app.prefs.pinLength.collectAsState()
    val showPinLength by app.prefs.showPinLength.collectAsState()

    var stage by remember { mutableStateOf(ChangeStage.Current) }
    var newType by remember { mutableStateOf(currentType) }
    var newPinLength by remember { mutableIntStateOf(if (currentPinLength >= MIN_CREDENTIAL_LENGTH) currentPinLength else 6) }
    var currentSecret by remember { mutableStateOf("") }
    var newSecret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit(secret: String, clear: () -> Unit) {
        error = null
        when (stage) {
            ChangeStage.Current -> {
                currentSecret = secret
                stage = ChangeStage.Choose
                clear()
            }
            ChangeStage.New -> {
                newSecret = secret
                stage = ChangeStage.Repeat
                clear()
            }
            ChangeStage.Repeat -> {
                if (secret != newSecret) {
                    error = "Entries didn't match — try again"
                    stage = ChangeStage.New
                    clear()
                    return
                }
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        app.keyManager.changePin(currentSecret.toCharArray(), secret.toCharArray())
                    }
                    busy = false
                    if (ok) {
                        app.prefs.setCredential(
                            newType,
                            if (newType == Prefs.TYPE_PASSWORD) 0 else secret.length,
                        )
                        Toast.makeText(context, "Lock updated", Toast.LENGTH_SHORT).show()
                        onClose()
                    } else {
                        error = "Current entry was wrong — start over"
                        stage = ChangeStage.Current
                        clear()
                    }
                }
            }
            ChangeStage.Choose -> Unit // chooser has no text entry; submit is never called
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change PIN or password") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (stage) {
                ChangeStage.Current -> {
                    if (currentType == Prefs.TYPE_PASSWORD) {
                        PasswordPad(
                            title = "Current password",
                            subtitle = null,
                            error = error,
                            busy = busy,
                            onSubmit = ::submit,
                        )
                    } else {
                        PinPad(
                            title = "Current PIN",
                            subtitle = null,
                            error = error,
                            busy = busy,
                            targetLength = if (showPinLength) currentPinLength else null,
                            onSubmit = ::submit,
                        )
                    }
                }
                ChangeStage.Choose -> CredentialChooser(
                    title = "New lock",
                    type = newType,
                    pinLength = newPinLength,
                    onTypeChange = { newType = it },
                    onPinLengthChange = { newPinLength = it },
                    onContinue = { stage = ChangeStage.New },
                )
                else -> {
                    val isPassword = newType == Prefs.TYPE_PASSWORD
                    val title = when {
                        stage == ChangeStage.New && isPassword -> "New password"
                        stage == ChangeStage.New -> "New PIN"
                        isPassword -> "Repeat new password"
                        else -> "Repeat new PIN"
                    }
                    if (isPassword) {
                        PasswordPad(
                            title = title,
                            subtitle = "Your media stays in the vault; only the lock changes.",
                            error = error,
                            busy = busy,
                            onSubmit = ::submit,
                        )
                    } else {
                        PinPad(
                            title = title,
                            subtitle = "Your media stays in the vault; only the lock changes.",
                            error = error,
                            busy = busy,
                            targetLength = newPinLength,
                            enforceLength = true,
                            onSubmit = ::submit,
                        )
                    }
                }
            }
        }
    }
}
