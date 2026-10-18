package com.vknxworks.proxctrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.proxctrl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App-wide preferences (not per server). Server settings and secrets live elsewhere, encrypted. */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "proxctrl")

enum class ThemeChoice(@StringRes val label: Int) {
    SYSTEM(R.string.theme_system),
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light),
    BLACK(R.string.theme_black),
}

enum class GuestSort(@StringRes val label: Int) {
    ID(R.string.sort_id),
    NAME(R.string.sort_name),
    CPU(R.string.sort_cpu),
    MEMORY(R.string.sort_memory),
    DISK(R.string.sort_disk),
    UPTIME(R.string.sort_uptime),
}

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val dynamicColor: Boolean = false,
    val guestSort: GuestSort = GuestSort.ID,
    val guestSortDescending: Boolean = false,
    /** One line per guest instead of the detailed rows. */
    val guestCompact: Boolean = false,
    /** Disk use (percent) from which a guest or storage is flagged. */
    val diskWarnPercent: Int = 80,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val guestSort = stringPreferencesKey("guest_sort")
        val guestDesc = booleanPreferencesKey("guest_sort_desc")
        val guestCompact = booleanPreferencesKey("guest_compact")
        val diskWarn = intPreferencesKey("disk_warn_percent")
    }

    val settings: StateFlow<AppSettings> = context.appDataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        theme = enumOr(this[K.theme], ThemeChoice.SYSTEM),
        dynamicColor = this[K.dynamic] ?: false,
        guestSort = enumOr(this[K.guestSort], GuestSort.ID),
        guestSortDescending = this[K.guestDesc] ?: false,
        guestCompact = this[K.guestCompact] ?: false,
        diskWarnPercent = (this[K.diskWarn] ?: 80).coerceIn(50, 99),
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appDataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.theme] = s.theme.name
            p[K.dynamic] = s.dynamicColor
            p[K.guestSort] = s.guestSort.name
            p[K.guestDesc] = s.guestSortDescending
            p[K.guestCompact] = s.guestCompact
            p[K.diskWarn] = s.diskWarnPercent
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
