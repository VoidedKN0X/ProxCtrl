package com.vknxworks.proxctrl

import android.content.Context
import com.vknxworks.proxctrl.data.guests.GuestRepository
import com.vknxworks.proxctrl.data.lock.AppLock
import com.vknxworks.proxctrl.data.lock.LockPrefs
import com.vknxworks.proxctrl.data.lock.LockVault
import com.vknxworks.proxctrl.data.notify.CheckStore
import com.vknxworks.proxctrl.data.widgets.WidgetRefresher
import com.vknxworks.proxctrl.data.notify.NotifySettingsRepository
import com.vknxworks.proxctrl.data.overview.OverviewRepository
import com.vknxworks.proxctrl.data.server.ServerStore
import com.vknxworks.proxctrl.data.server.Sessions
import com.vknxworks.proxctrl.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context, appScope)

    /**
     * Shared HTTP client. Each server gets a copy with its own certificate pin (see httpFor), so a
     * pinned certificate is only ever accepted for the server it belongs to. Redirects are not
     * followed: the Proxmox API never redirects, and following one could send the token or ticket
     * to another host.
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "ProxCtrl/${BuildConfig.VERSION_NAME} (Android)")
                    .build(),
            )
        }
        .build()

    /** The app lock's PIN, fingerprint key and the key that seals server logins while locked. */
    val vault = LockVault(context, "proxctrl")
    val lockPrefs = LockPrefs(context)

    /** Server logins are sealed with the app PIN: the lock is on and background checks are off. */
    fun sealing(): Boolean = vault.enabled && !lockPrefs.backgroundChecks

    /** Background work (notifications, widgets, tile) can't read the logins right now. */
    fun backgroundBlocked(): Boolean = sealing() && !vault.isOpen

    val serverStore = ServerStore(context, vault, ::sealing)
    val sessions = Sessions(serverStore, http, appScope, initiallyLocked = sealing())

    /** Turns the app lock on with [pin]. [backgroundChecks]: keep notifications and widgets working while locked. */
    suspend fun enableLock(pin: String, backgroundChecks: Boolean) {
        // Unlocked first, so the lock screen doesn't flash up while the PIN is being saved.
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) {
            lockPrefs.backgroundChecks = backgroundChecks
            vault.setUp(pin)
            serverStore.resealAll(sealing())
        }
        // The old lock (phone's screen lock) is replaced by the PIN; "Confirm changes" stays as it was.
        settings.update { it.copy(lockOnOpen = false) }
    }

    /** Turns the app lock off (after the PIN was confirmed): logins go back to Keystore-only encryption. */
    suspend fun disableLock() {
        withContext(Dispatchers.Default) { serverStore.resealAll(false) }
        settings.update { it.copy(lockOnOpen = false, lockChanges = false) }
        vault.disable()
        lockPrefs.backgroundChecks = false
        sessions.setLocked(false)
    }

    /** Switches "Background checks while locked" (only while unlocked, so every login can be re-encrypted). */
    suspend fun setBackgroundChecks(on: Boolean) {
        withContext(Dispatchers.Default) {
            lockPrefs.backgroundChecks = on
            serverStore.resealAll(sealing())
        }
    }

    /** "Forgot PIN?": sealed logins can't be decrypted any more, so remove every server and start over. */
    suspend fun forgetEverything() {
        withContext(Dispatchers.Default) {
            serverStore.servers.value.forEach { CheckStore.forget(context, it.id) }
            serverStore.removeAll()
        }
        AppLock.markUnlocked()
        settings.update { it.copy(lockOnOpen = false, lockChanges = false) }
        vault.disable()
        lockPrefs.backgroundChecks = false
        sessions.setLocked(false)
        WidgetRefresher.refreshSoon(context)
    }

    /** Locked: wipe the key from memory and drop the clients that hold the logins. */
    fun onLocked() {
        vault.close()
        if (sealing()) sessions.setLocked(true)
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
        sessions.setLocked(false)
    }
    val guests = GuestRepository(context, sessions, appScope)
    val overview = OverviewRepository(context, sessions, guests, appScope)
    val notifySettings = NotifySettingsRepository(context, appScope)
}

val Context.container: AppContainer
    get() = (applicationContext as ProxCtrlApp).container
