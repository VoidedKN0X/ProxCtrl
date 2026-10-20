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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** Node memory use (percent) from which it's flagged. */
    val memWarnPercent: Int = 85,
    /** A guest's newest backup older than this many days is flagged. */
    val backupMaxAgeDays: Int = 7,
    /** FLAG_SECURE on the whole app: blank screenshots, screen recordings and Recents card. On by default. */
    val hideContent: Boolean = true,
    /** App lock: ask for the fingerprint or screen lock when opening the app. */
    val lockOnOpen: Boolean = false,
    /** App lock: ask before anything that changes something on a server. */
    val lockChanges: Boolean = false,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val guestSort = stringPreferencesKey("guest_sort")
        val guestDesc = booleanPreferencesKey("guest_sort_desc")
        val guestCompact = booleanPreferencesKey("guest_compact")
        val diskWarn = intPreferencesKey("disk_warn_percent")
        val memWarn = intPreferencesKey("mem_warn_percent")
        val backupAge = intPreferencesKey("backup_max_age_days")
        val hideContent = booleanPreferencesKey("hide_content")
        val lockOnOpen = booleanPreferencesKey("lock_on_open")
        val lockChanges = booleanPreferencesKey("lock_changes")
    }

    val settings: StateFlow<AppSettings> = context.appDataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** The stored settings as they're read and changed, without [settings]' placeholder defaults at start-up. */
    val stored: Flow<AppSettings> = context.appDataStore.data.map { it.toSettings() }

    init {
        // Versions before 1.0.2 stored the choice for the old opt-in stats under keys starting with
        // "usage_". Nothing uses them any more, so they're deleted from the phone.
        scope.launch {
            runCatching {
                context.appDataStore.edit { p ->
                    p.asMap().keys.filter { it.name.startsWith("usage_") }.forEach { p.remove(it) }
                }
            }
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        theme = enumOr(this[K.theme], ThemeChoice.SYSTEM),
        dynamicColor = this[K.dynamic] ?: false,
        guestSort = enumOr(this[K.guestSort], GuestSort.ID),
        guestSortDescending = this[K.guestDesc] ?: false,
        guestCompact = this[K.guestCompact] ?: false,
        diskWarnPercent = (this[K.diskWarn] ?: 80).coerceIn(50, 99),
        memWarnPercent = (this[K.memWarn] ?: 85).coerceIn(50, 99),
        backupMaxAgeDays = (this[K.backupAge] ?: 7).coerceIn(1, 90),
        hideContent = this[K.hideContent] ?: true,
        lockOnOpen = this[K.lockOnOpen] ?: false,
        lockChanges = this[K.lockChanges] ?: false,
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
            p[K.memWarn] = s.memWarnPercent
            p[K.backupAge] = s.backupMaxAgeDays
            p[K.hideContent] = s.hideContent
            p[K.lockOnOpen] = s.lockOnOpen
            p[K.lockChanges] = s.lockChanges
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
