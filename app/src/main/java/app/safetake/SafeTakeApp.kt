package app.safetake

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.safetake.crypto.BiometricVault
import app.safetake.crypto.KeyManager
import app.safetake.crypto.SessionVault
import app.safetake.data.MediaRepository
import app.safetake.data.Prefs

class SafeTakeApp : Application() {

    lateinit var keyManager: KeyManager
        private set
    lateinit var biometricVault: BiometricVault
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var repository: MediaRepository
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { SessionVault.lock() }

    override fun onCreate() {
        super.onCreate()
        keyManager = KeyManager(filesDir)
        biometricVault = BiometricVault(filesDir)
        prefs = Prefs(this)
        repository = MediaRepository(this)
        sweepTemps()
        SessionVault.addLockListener {
            repository.clearLoaded()
            sweepTemps()
        }
        // Auto-lock: backgrounding starts a grace timer; returning cancels it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                handler.postDelayed(lockRunnable, LOCK_GRACE_MS)
            }

            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(lockRunnable)
            }
        })
    }

    /** Removes plaintext temps (recording, playback, share) from the private cache. */
    fun sweepTemps() {
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("rec-") || f.name.startsWith("play-") ||
                f.name.startsWith("share-")
            ) f.delete()
        }
    }

    companion object {
        const val LOCK_GRACE_MS = 30_000L
    }
}
