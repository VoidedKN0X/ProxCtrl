package com.vknxworks.proxctrl.data.lock

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.vknxworks.proxctrl.AppContainer
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.WriteGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The optional app lock: fingerprint, face or the phone's screen lock (PIN, pattern, password).
 *
 * - "Lock when opening the app": the app is covered until unlocked on a cold start and whenever it
 *   comes back after more than [AWAY_MS] in the background.
 * - "Confirm changes": every request that could change something on a server asks first, through
 *   [WriteGuard]. A successful check covers the next [WRITE_GRACE_MS], so a bulk action asks once.
 *
 * Nothing about the check is stored; Android does the authentication.
 */
object AppLock {
    enum class State { CHECKING, LOCKED, UNLOCKED }

    private const val AWAY_MS = 30_000L
    private const val WRITE_GRACE_MS = 60_000L

    /** What the device offers: biometrics with the screen lock as fallback. */
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private val _state = MutableStateFlow(State.CHECKING)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var lockOnOpen = false
    @Volatile private var confirmChanges = false
    @Volatile private var settingsLoaded = false
    @Volatile private var installed = false
    @Volatile private var foreground: WeakReference<FragmentActivity>? = null
    @Volatile private var startedActivities = 0

    /** Elapsed-realtime when the app last went to the background; 0 = cold start. */
    @Volatile private var backgroundedAt = 0L
    @Volatile private var lastUnlockAt = 0L
    private val promptLock = Mutex()

    /** Whether this phone can check anything at all (a screen lock is set). */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS ||
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Hooks the lock into [activity] (call from onCreate). The first call also starts following the
     * settings and installs the write check.
     */
    fun attach(activity: FragmentActivity, c: AppContainer) {
        if (!installed) {
            installed = true
            WriteGuard.check = { checkWrite() }
            c.appScope.launch {
                c.settings.stored.collect { s ->
                    lockOnOpen = s.lockOnOpen
                    confirmChanges = s.lockChanges
                    val first = !settingsLoaded
                    settingsLoaded = true
                    when {
                        !s.lockOnOpen -> _state.value = State.UNLOCKED
                        // The lock was just turned on in Settings: don't lock the user out mid-session.
                        !first && _state.value == State.UNLOCKED -> Unit
                        else -> if (_state.value != State.UNLOCKED) _state.value = State.LOCKED
                    }
                }
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                startedActivities++
                foreground = WeakReference(activity)
                val away = backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > AWAY_MS
                // Entering the phone's PIN in the unlock prompt itself also leaves the app for a moment.
                val justUnlocked = SystemClock.elapsedRealtime() - lastUnlockAt < 5_000
                if (lockOnOpen && settingsLoaded && away && !justUnlocked && _state.value == State.UNLOCKED) _state.value = State.LOCKED
                backgroundedAt = 0L
            }

            override fun onStop(owner: LifecycleOwner) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) backgroundedAt = SystemClock.elapsedRealtime()
                if (foreground?.get() === activity) foreground = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (foreground?.get() === activity) foreground = null
            }
        })
    }

    /** Shows the prompt to unlock the app; does nothing when it isn't locked. */
    fun unlock(activity: FragmentActivity) {
        if (_state.value != State.LOCKED) return
        if (!isAvailable(activity)) {
            // The screen lock was removed since the app lock was turned on: there's nothing to check against.
            _state.value = State.UNLOCKED
            return
        }
        prompt(
            activity,
            activity.getString(R.string.lock_prompt_title),
            activity.getString(R.string.lock_prompt_subtitle),
        ) { ok ->
            if (ok) {
                lastUnlockAt = SystemClock.elapsedRealtime()
                _state.value = State.UNLOCKED
            }
        }
    }

    /** The [WriteGuard] check: asks before a change when "Confirm changes" is on. */
    private suspend fun checkWrite(): Boolean {
        if (!confirmChanges) return true
        return promptLock.withLock {
            if (SystemClock.elapsedRealtime() - lastUnlockAt < WRITE_GRACE_MS && lastUnlockAt != 0L) return@withLock true
            val activity = foreground?.get() ?: return@withLock false
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withLock false
            if (!isAvailable(activity)) return@withLock true
            val ok = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    prompt(
                        activity,
                        activity.getString(R.string.lock_confirm_title),
                        activity.getString(R.string.lock_confirm_subtitle),
                    ) { result -> if (cont.isActive) cont.resume(result) }
                }
            }
            if (ok) lastUnlockAt = SystemClock.elapsedRealtime()
            ok
        }
    }

    private fun prompt(activity: FragmentActivity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                // A finger that didn't match: the prompt stays open for another try.
                override fun onAuthenticationFailed() = Unit
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { onResult(false) }
    }
}
