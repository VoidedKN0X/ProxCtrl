package com.vknxworks.proxctrl.data.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Saved [CheckState] per server, in no-backup storage. */
object CheckStore {
    private val _lastCheck = MutableStateFlow<Map<String, CheckState>>(emptyMap())

    /** The last check of each server, for the notification settings page. */
    val lastCheck: StateFlow<Map<String, CheckState>> = _lastCheck.asStateFlow()

    private fun file(context: Context, serverId: String) =
        File(File(context.noBackupFilesDir, "notify").apply { mkdirs() }, "$serverId.json")

    suspend fun read(context: Context, serverId: String): CheckState = withContext(Dispatchers.IO) {
        val s = runCatching { PveJson.decodeFromString(CheckState.serializer(), file(context, serverId).readText()) }.getOrNull() ?: CheckState()
        _lastCheck.value = _lastCheck.value + (serverId to s)
        s
    }

    suspend fun write(context: Context, serverId: String, state: CheckState) = withContext(Dispatchers.IO) {
        runCatching { file(context, serverId).writeText(PveJson.encodeToString(CheckState.serializer(), state)) }
        _lastCheck.value = _lastCheck.value + (serverId to state)
    }

    fun forget(context: Context, serverId: String) {
        runCatching { file(context, serverId).delete() }
        _lastCheck.value = _lastCheck.value - serverId
    }
}

/**
 * Background check of every saved server. Runs every 15, 30 or 60 minutes while polling is on
 * (Android doesn't allow less than 15), only with a network connection, and once right away when
 * a push from Proxmox arrives or "Check now" is tapped.
 */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val notify = c.notifySettings.current()
        if (!notify.polling && !inputData.getBoolean(KEY_FORCE, false)) return Result.success()
        val app = c.settings.settings.value
        val servers = c.sessions.servers.value
        val checker = Checker(applicationContext)
        val config = CheckConfig(notify, app.diskWarnPercent, app.memWarnPercent, manyServers = servers.size > 1)
        for (server in servers) {
            val prev = CheckStore.read(applicationContext, server.id)
            val result = runCatching { checker.check(server, c.sessions.clientFor(server), config, prev) }.getOrNull() ?: continue
            result.cancel.forEach { Notifier.cancel(applicationContext, it) }
            result.post.forEach { Notifier.post(applicationContext, it) }
            CheckStore.write(applicationContext, server.id, result.state)
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "proxctrl-check"
        private const val ONCE = "proxctrl-check-now"
        const val KEY_FORCE = "force"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Starts, changes or stops the periodic check to match [settings]. */
        fun schedule(context: Context, settings: NotifySettings) {
            val wm = WorkManager.getInstance(context)
            if (!settings.polling) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<NotifyWorker>(settings.intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(PERIODIC)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One check now (from "Check now" or after a push), even when polling is off. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotifyWorker>()
                .setConstraints(constraints)
                .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
