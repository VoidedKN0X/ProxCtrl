package com.vknxworks.proxctrl

import android.content.Context
import com.vknxworks.proxctrl.data.guests.GuestRepository
import com.vknxworks.proxctrl.data.notify.NotifySettingsRepository
import com.vknxworks.proxctrl.data.overview.OverviewRepository
import com.vknxworks.proxctrl.data.server.ServerStore
import com.vknxworks.proxctrl.data.server.Sessions
import com.vknxworks.proxctrl.data.settings.SettingsRepository
import com.vknxworks.proxctrl.data.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context, appScope)

    /** Opt-in usage stats, with its own HTTP client (never [http] or a server's pinned copy of it). */
    val telemetry = Telemetry(context, settings, appScope)

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

    val serverStore = ServerStore(context)
    val sessions = Sessions(serverStore, http, appScope)
    val guests = GuestRepository(context, sessions, appScope)
    val overview = OverviewRepository(context, sessions, guests, appScope)
    val notifySettings = NotifySettingsRepository(context, appScope)
}

val Context.container: AppContainer
    get() = (applicationContext as ProxCtrlApp).container
