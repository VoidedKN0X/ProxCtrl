package com.vknxworks.proxctrl.data.notify

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.settings.appDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Things ProxCtrl can notify about. Each can be switched off. */
enum class NotifyEvent(@StringRes val label: Int, @StringRes val summary: Int) {
    GUEST_DISK(R.string.ev_guest_disk, R.string.ev_guest_disk_sub),
    NODE_MEMORY(R.string.ev_node_memory, R.string.ev_node_memory_sub),
    NODE_CPU(R.string.ev_node_cpu, R.string.ev_node_cpu_sub),
    GUEST_STOPPED(R.string.ev_guest_stopped, R.string.ev_guest_stopped_sub),
    TASK_FAILED(R.string.ev_task_failed, R.string.ev_task_failed_sub),
    BACKUP_FAILED(R.string.ev_backup_failed, R.string.ev_backup_failed_sub),
    STORAGE_FULL(R.string.ev_storage_full, R.string.ev_storage_full_sub),
    ZFS_DEGRADED(R.string.ev_zfs, R.string.ev_zfs_sub),
    SMART_FAILED(R.string.ev_smart, R.string.ev_smart_sub),
    NODE_UNREACHABLE(R.string.ev_unreachable, R.string.ev_unreachable_sub),
    UPDATES(R.string.ev_updates, R.string.ev_updates_sub),
}

data class NotifySettings(
    /** Check the server in the background with WorkManager. */
    val polling: Boolean = false,
    val intervalMinutes: Int = 15,
    /** Receive Proxmox's own notifications through UnifiedPush (e.g. ntfy). */
    val push: Boolean = false,
    val disabled: Set<NotifyEvent> = emptySet(),
    /** Node CPU use (percent) that counts as high. */
    val cpuPercent: Int = 90,
    /** How long node CPU or memory must stay high before a notification. */
    val sustainMinutes: Int = 10,
) {
    fun on(e: NotifyEvent): Boolean = e !in disabled

    companion object {
        val INTERVALS = listOf(15, 30, 60)
        val SUSTAIN = listOf(5, 10, 15, 30)
        val CPU = listOf(70, 80, 90, 95)
    }
}

class NotifySettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val polling = booleanPreferencesKey("notify_polling")
        val interval = intPreferencesKey("notify_interval")
        val push = booleanPreferencesKey("notify_push")
        val disabled = stringSetPreferencesKey("notify_disabled")
        val cpu = intPreferencesKey("notify_cpu")
        val sustain = intPreferencesKey("notify_sustain")
    }

    val settings: StateFlow<NotifySettings> = context.appDataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, NotifySettings())

    /** The saved settings, read directly (for the background worker, before the flow has a value). */
    suspend fun current(): NotifySettings = context.appDataStore.data.first().toSettings()

    private fun Preferences.toSettings() = NotifySettings(
        polling = this[K.polling] ?: false,
        intervalMinutes = (this[K.interval] ?: 15).let { if (it in NotifySettings.INTERVALS) it else 15 },
        push = this[K.push] ?: false,
        disabled = this[K.disabled].orEmpty().mapNotNull { n -> NotifyEvent.entries.firstOrNull { it.name == n } }.toSet(),
        cpuPercent = (this[K.cpu] ?: 90).coerceIn(50, 99),
        sustainMinutes = (this[K.sustain] ?: 10).coerceIn(1, 55),
    )

    suspend fun update(transform: (NotifySettings) -> NotifySettings) {
        context.appDataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.polling] = s.polling
            p[K.interval] = s.intervalMinutes
            p[K.push] = s.push
            p[K.disabled] = s.disabled.map { it.name }.toSet()
            p[K.cpu] = s.cpuPercent
            p[K.sustain] = s.sustainMinutes
        }
    }
}
