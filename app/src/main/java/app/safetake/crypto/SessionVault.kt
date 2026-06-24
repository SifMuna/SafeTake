package app.safetake.crypto

import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the unwrapped DEK in memory while the app is unlocked. The key never
 * touches disk; locking drops the reference and notifies listeners.
 */
object SessionVault {

    @Volatile
    private var dek: SecretKey? = null

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    private val lockListeners = mutableListOf<() -> Unit>()

    fun unlockWith(key: SecretKey) {
        dek = key
        _unlocked.value = true
    }

    fun key(): SecretKey = dek ?: throw IllegalStateException("vault is locked")

    fun keyOrNull(): SecretKey? = dek

    fun lock() {
        dek = null
        _unlocked.value = false
        synchronized(lockListeners) { lockListeners.toList() }.forEach { it() }
    }

    fun addLockListener(listener: () -> Unit) {
        synchronized(lockListeners) { lockListeners.add(listener) }
    }
}
