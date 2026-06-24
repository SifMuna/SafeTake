package app.safetake.ui

import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.safetake.SafeTakeApp
import app.safetake.crypto.SessionVault
import app.safetake.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MIN_CREDENTIAL_LENGTH = 4
const val MAX_PIN_LENGTH = 16

/**
 * Shown whenever the vault is locked. Handles both first-run credential setup
 * (choose PIN or password, enter twice, with a data-loss warning) and normal
 * unlock.
 */
@Composable
fun LockScreen(app: SafeTakeApp, onUnlocked: () -> Unit = {}) {
    if (app.keyManager.isInitialized) UnlockScreen(app, onUnlocked)
    else SetupScreen(app, onUnlocked)
}

@Composable
private fun UnlockScreen(app: SafeTakeApp, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val type by app.prefs.credentialType.collectAsState()
    val pinLength by app.prefs.pinLength.collectAsState()
    val showPinLength by app.prefs.showPinLength.collectAsState()
    val isPassword = type == Prefs.TYPE_PASSWORD

    // Biometric unlock is offered only if the user enabled it and the hardware is ready.
    val activity = remember { context.findFragmentActivity() }
    val biometricAvailable = remember {
        app.biometricVault.isEnabled && activity != null && BiometricAuth.canAuthenticate(context)
    }

    fun finishUnlock(key: javax.crypto.SecretKey) {
        SessionVault.unlockWith(key)
        scope.launch {
            withContext(Dispatchers.IO) { app.repository.loadIndex() }
            busy = false
            onUnlocked()
        }
    }

    fun promptBiometric() {
        if (busy) return
        val act = activity ?: return
        // Null here means the stored key was invalidated (e.g. new fingerprint enrolled).
        val cipher = app.biometricVault.decryptCipher() ?: run {
            error = "Biometrics changed — unlock with your ${if (isPassword) "password" else "PIN"}"
            return
        }
        error = null
        BiometricAuth.authenticate(
            activity = act,
            cipher = cipher,
            title = "Unlock SafeTake",
            subtitle = "Confirm it's you",
            onSuccess = { authorized ->
                busy = true
                scope.launch {
                    val key = withContext(Dispatchers.Default) {
                        runCatching { app.biometricVault.recoverDek(authorized) }.getOrNull()
                    }
                    if (key == null) {
                        error = "Biometric unlock failed — use your ${if (isPassword) "password" else "PIN"}"
                        busy = false
                    } else {
                        finishUnlock(key)
                    }
                }
            },
            onError = { /* user cancelled or fell back to PIN; leave the pad as-is */ },
        )
    }

    // Offer biometrics straight away so the common case is a single tap.
    LaunchedEffect(Unit) { if (biometricAvailable) promptBiometric() }

    fun submit(secret: String, clear: () -> Unit) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            val key = withContext(Dispatchers.Default) {
                app.keyManager.unlock(secret.toCharArray())
            }
            if (key == null) {
                error = if (isPassword) "Wrong password" else "Wrong PIN"
                clear()
                busy = false
            } else {
                // installs from before this pref existed may have a stale default
                if (!isPassword && secret.length != pinLength) {
                    app.prefs.setCredential(Prefs.TYPE_PIN, secret.length)
                }
                finishUnlock(key)
            }
        }
    }

    if (isPassword) {
        PasswordPad(
            title = "Enter password",
            subtitle = "SafeTake is locked",
            error = error,
            busy = busy,
            onBiometric = if (biometricAvailable) ::promptBiometric else null,
            onSubmit = ::submit,
        )
    } else {
        PinPad(
            title = "Enter PIN",
            subtitle = "SafeTake is locked",
            error = error,
            busy = busy,
            targetLength = if (showPinLength) pinLength else null,
            onBiometric = if (biometricAvailable) ::promptBiometric else null,
            onSubmit = ::submit,
        )
    }
}

private enum class SetupStage { Choose, Enter, Repeat }

@Composable
private fun SetupScreen(app: SafeTakeApp, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(SetupStage.Choose) }
    var type by remember { mutableStateOf(Prefs.TYPE_PIN) }
    var pinLength by remember { mutableIntStateOf(6) }
    var staged by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val isPassword = type == Prefs.TYPE_PASSWORD

    fun submit(secret: String, clear: () -> Unit) {
        if (busy) return
        error = null
        if (stage == SetupStage.Enter) {
            staged = secret
            stage = SetupStage.Repeat
            clear()
            return
        }
        if (secret != staged) {
            error = if (isPassword) "Passwords didn't match — start over"
            else "PINs didn't match — start over"
            staged = ""
            stage = SetupStage.Enter
            clear()
            return
        }
        busy = true
        scope.launch {
            val key = withContext(Dispatchers.Default) {
                app.keyManager.initialize(secret.toCharArray())
            }
            app.prefs.setCredential(type, if (isPassword) 0 else secret.length)
            SessionVault.unlockWith(key)
            withContext(Dispatchers.IO) { app.repository.loadIndex() }
            busy = false
            onUnlocked()
        }
    }

    when (stage) {
        SetupStage.Choose -> CredentialChooser(
            title = "Secure SafeTake",
            type = type,
            pinLength = pinLength,
            onTypeChange = { type = it },
            onPinLengthChange = { pinLength = it },
            onContinue = { stage = SetupStage.Enter },
        )
        else -> {
            val title = when {
                stage == SetupStage.Enter && isPassword -> "Create a password"
                stage == SetupStage.Enter -> "Create a PIN"
                isPassword -> "Repeat the password"
                else -> "Repeat the PIN"
            }
            if (isPassword) {
                PasswordPad(
                    title = title,
                    subtitle = SETUP_WARNING,
                    error = error,
                    busy = busy,
                    onSubmit = ::submit,
                )
            } else {
                PinPad(
                    title = title,
                    subtitle = SETUP_WARNING,
                    error = error,
                    busy = busy,
                    targetLength = pinLength,
                    enforceLength = true,
                    onSubmit = ::submit,
                )
            }
        }
    }
}

const val SETUP_WARNING =
    "Encrypted media is protected with this.\nThere is no recovery — if you forget it, encrypted items are gone."

/** Picks PIN vs password, and the PIN's length. Reused by setup and change-credential. */
@Composable
fun CredentialChooser(
    title: String,
    type: String,
    pinLength: Int,
    onTypeChange: (String) -> Unit,
    onPinLengthChange: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    val isPassword = type == Prefs.TYPE_PASSWORD
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                SETUP_WARNING,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("PIN", selected = !isPassword) { onTypeChange(Prefs.TYPE_PIN) }
                ChoiceChip("Password", selected = isPassword) { onTypeChange(Prefs.TYPE_PASSWORD) }
            }
            Spacer(Modifier.height(24.dp))

            // fixed-size slot so switching PIN/password doesn't shift the layout
            Box(modifier = Modifier.height(180.dp), contentAlignment = Alignment.Center) {
                if (!isPassword) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "PIN length",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                        AndroidView(
                            factory = { ctx ->
                                NumberPicker(ctx).apply {
                                    minValue = MIN_CREDENTIAL_LENGTH
                                    maxValue = MAX_PIN_LENGTH
                                    wrapSelectorWheel = false
                                    setTextColor(textColor)
                                    setOnValueChangedListener { _, _, v -> onPinLengthChange(v) }
                                }
                            },
                            update = { it.value = pinLength },
                        )
                    }
                } else {
                    Text(
                        "Any characters, at least $MIN_CREDENTIAL_LENGTH.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(onClick = onContinue) { Text("Continue") }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * Numeric keypad. [targetLength] is how many indicator dots to show up front
 * (null shows only typed digits, hiding the PIN's length). With
 * [enforceLength], input is capped at [targetLength] and submit requires
 * exactly that many digits — used when creating a PIN. Without it (unlocking),
 * any length >= [MIN_CREDENTIAL_LENGTH] can be submitted, so a stale stored
 * length can never lock the user out.
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String?,
    error: String?,
    busy: Boolean,
    targetLength: Int? = null,
    enforceLength: Boolean = false,
    onBiometric: (() -> Unit)? = null,
    onSubmit: (pin: String, clear: () -> Unit) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val submitLength = if (enforceLength && targetLength != null) targetLength
    else MIN_CREDENTIAL_LENGTH

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))

            // fixed height so the keypad never shifts as dots come and go
            Box(modifier = Modifier.height(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(maxOf(pin.length, targetLength ?: 0)) { i ->
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(
                                    color = if (i < pin.length) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape,
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.height(28.dp)) {
                when {
                    busy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))

            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
            )
            fun type(digit: String) {
                if (!enforceLength || targetLength == null || pin.length < targetLength) pin += digit
            }
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { digit ->
                        DigitKey(digit, enabled = !busy) { type(digit) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { pin = pin.dropLast(1) },
                    enabled = pin.isNotEmpty() && !busy,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace")
                }
                DigitKey("0", enabled = !busy) { type("0") }
                IconButton(
                    onClick = { onSubmit(pin) { pin = "" } },
                    enabled = pin.length >= submitLength && !busy,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Confirm",
                        tint = if (pin.length >= submitLength) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onBiometric != null) {
                Spacer(Modifier.height(12.dp))
                BiometricButton(enabled = !busy, onClick = onBiometric)
            }
        }
    }
}

/** Fingerprint affordance shown on the lock pads when biometric unlock is enabled. */
@Composable
private fun BiometricButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(
            Icons.Default.Fingerprint,
            contentDescription = "Unlock with biometrics",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
    }
}

/** Alphanumeric counterpart of [PinPad]; the field masks input with dots. */
@Composable
fun PasswordPad(
    title: String,
    subtitle: String?,
    error: String?,
    busy: Boolean,
    onBiometric: (() -> Unit)? = null,
    onSubmit: (password: String, clear: () -> Unit) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val canSubmit = password.length >= MIN_CREDENTIAL_LENGTH

    fun submit() {
        if (canSubmit && !busy) onSubmit(password) { password = "" }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.height(28.dp)) {
                when {
                    busy -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))

            Button(onClick = ::submit, enabled = canSubmit && !busy) {
                Text("Confirm")
            }
            if (onBiometric != null) {
                Spacer(Modifier.height(12.dp))
                BiometricButton(enabled = !busy, onClick = onBiometric)
            }
        }
    }
}

@Composable
private fun DigitKey(digit: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit, fontSize = 26.sp)
        }
    }
}
