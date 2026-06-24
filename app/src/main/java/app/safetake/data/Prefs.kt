package app.safetake.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Non-secret app settings. The shape of the unlock credential (not the
 * credential itself) is a policy choice, not a secret, so plain
 * SharedPreferences is fine.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _credentialType = MutableStateFlow(sp.getString(KEY_CREDENTIAL_TYPE, TYPE_PIN)!!)
    private val _pinLength = MutableStateFlow(sp.getInt(KEY_PIN_LENGTH, 6))
    private val _showPinLength = MutableStateFlow(sp.getBoolean(KEY_SHOW_PIN_LENGTH, true))

    /** [TYPE_PIN] or [TYPE_PASSWORD]. */
    val credentialType: StateFlow<String> = _credentialType

    /** Digit count of the current PIN; meaningless when the credential is a password. */
    val pinLength: StateFlow<Int> = _pinLength

    /** Whether the lock screen shows how many digits the PIN has. */
    val showPinLength: StateFlow<Boolean> = _showPinLength

    /** Records the shape of the credential set in KeyManager (called after init/change). */
    fun setCredential(type: String, pinLength: Int) {
        sp.edit().putString(KEY_CREDENTIAL_TYPE, type).putInt(KEY_PIN_LENGTH, pinLength).apply()
        _credentialType.value = type
        _pinLength.value = pinLength
    }

    fun setShowPinLength(value: Boolean) {
        sp.edit().putBoolean(KEY_SHOW_PIN_LENGTH, value).apply()
        _showPinLength.value = value
    }

    companion object {
        const val TYPE_PIN = "pin"
        const val TYPE_PASSWORD = "password"

        private const val KEY_CREDENTIAL_TYPE = "credential_type"
        private const val KEY_PIN_LENGTH = "pin_length"
        private const val KEY_SHOW_PIN_LENGTH = "show_pin_length"
    }
}
