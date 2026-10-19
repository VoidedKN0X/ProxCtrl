package com.vknxworks.proxctrl.ui.storage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.overview.NodeEntry
import com.vknxworks.proxctrl.data.storage.BackupJob
import com.vknxworks.proxctrl.data.storage.BackupJobApi
import com.vknxworks.proxctrl.data.storage.JobRun
import com.vknxworks.proxctrl.data.storage.JobSelection
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.components.relativeTime
import com.vknxworks.proxctrl.ui.theme.Health
import kotlinx.serialization.builtins.ListSerializer

/** Scheduled backup jobs with their schedule, next and last run, and "Run now". */
@Composable
fun BackupJobsScreen() {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()

    var jobs by remember { mutableStateOf<List<BackupJob>?>(null) }
    var nodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastRuns by remember { mutableStateOf<Map<String, JobRun>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf<BackupJob?>(null) }

    LaunchedEffect(server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            val list = BackupJobApi.list(cl)
            jobs = list
            error = null
            val online = runCatching { cl.get("/nodes", ListSerializer(NodeEntry.serializer())) }.getOrDefault(emptyList())
                .filter { it.isOnline }.map { it.node }
            nodes = online
            refreshing = false
            // Matching tasks to jobs reads a few task logs: show the jobs first.
            lastRuns = runCatching { BackupJobApi.lastRuns(cl, online, list) }.getOrDefault(emptyMap())
        } catch (e: PveError) {
            error = e
        }
        refreshing = false
    }

    // "Run now" starts vzdump with the job's guests: it needs VM.Backup somewhere below /vms.
    val canRun = server?.readOnly == false && access.permissions.hasAnywhere("/vms", "VM.Backup")

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.storage_backup_jobs))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)
                val err = error
                val list = jobs
                when {
                    list == null && err != null -> ErrorText(err)
                    list == null -> LoadingBox()
                    list.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Schedule,
                        title = stringResource(R.string.storage_jobs_empty),
                        body = stringResource(R.string.storage_jobs_empty_hint),
                        modifier = Modifier.heightIn(min = 320.dp),
                    )
                    else -> Group(footer = stringResource(R.string.storage_jobs_footer)) {
                        list.forEachIndexed { i, job ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            JobRow(
                                job = job,
                                lastRun = lastRuns?.get(job.id),
                                lastRunLoading = lastRuns == null,
                                showRun = server?.readOnly == false,
                                canRun = canRun && !tracker.busy,
                                onRun = { running = job },
                            )
                        }
                    }
                }
            }
        }
    }

    running?.let { job ->
        ConfirmDialog(
            title = stringResource(R.string.storage_job_run_title),
            body = stringResource(R.string.storage_job_run_body, jobTitle(job)),
            confirmLabel = stringResource(R.string.storage_job_run),
            danger = false,
            icon = Icons.Filled.PlayArrow,
            onDismiss = { running = null },
            onConfirm = {
                running = null
                val cl = client ?: return@ConfirmDialog
                val targets = nodes
                tracker.run(context.getString(R.string.storage_job_run_task, jobTitle(job)), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                    // A job for every node starts one backup per node; the card follows the first.
                    BackupJobApi.runNow(cl, job, targets).firstOrNull()
                }
            },
        )
    }
}

private fun jobTitle(job: BackupJob): String = job.comment.ifBlank { job.id }

@Composable
private fun selectionText(s: JobSelection): String = when (s) {
    JobSelection.All -> stringResource(R.string.storage_job_all)
    is JobSelection.AllExcept -> stringResource(R.string.storage_job_all_except, s.vmids.joinToString(", "))
    is JobSelection.Guests -> if (s.vmids.isEmpty()) stringResource(R.string.storage_job_no_guests) else stringResource(R.string.storage_job_guests, s.vmids.joinToString(", "))
    is JobSelection.Pool -> stringResource(R.string.storage_job_pool, s.pool)
}

@Composable
private fun JobRow(job: BackupJob, lastRun: JobRun?, lastRunLoading: Boolean, showRun: Boolean, canRun: Boolean, onRun: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(jobTitle(job), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (job.enabled) R.string.storage_job_enabled else R.string.storage_job_disabled),
                style = MaterialTheme.typography.labelMedium,
                color = if (job.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val small = MaterialTheme.typography.bodySmall
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(selectionText(job.selection), style = small, color = muted)
        Text(
            listOfNotNull(
                job.storage.takeIf { it.isNotBlank() }?.let { stringResource(R.string.storage_job_to, it) },
                job.mode.takeIf { it.isNotBlank() },
                job.node?.let { stringResource(R.string.storage_job_node, it) },
            ).joinToString(" · "),
            style = small,
            color = muted,
        )
        Text(stringResource(R.string.storage_job_schedule, job.schedule.ifBlank { "?" }), style = small, color = muted)
        val next = job.nextRun
        Text(
            if (job.enabled && next != null && next > 0) {
                stringResource(R.string.storage_job_next, storageDateTime(next), relativeTime(next * 1000))
            } else {
                stringResource(R.string.storage_job_next_none)
            },
            style = small,
            color = muted,
        )
        Spacer(Modifier.width(4.dp))
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                lastRunLoading -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.storage_job_last_loading), style = small, color = muted)
                }
                lastRun == null -> Text(stringResource(R.string.storage_job_last_unknown), style = small, color = muted)
                else -> {
                    val (icon, tint, label) = when {
                        lastRun.running -> Triple(Icons.Filled.Schedule, MaterialTheme.colorScheme.primary, stringResource(R.string.storage_job_last_running))
                        lastRun.ok -> Triple(Icons.Filled.CheckCircle, Health.ok, stringResource(R.string.storage_job_last_ok))
                        lastRun.warnings -> Triple(Icons.Filled.Warning, Health.warn, lastRun.status)
                        else -> Triple(Icons.Filled.Error, Health.critical, lastRun.status.ifBlank { stringResource(R.string.storage_job_last_failed) })
                    }
                    Icon(icon, null, Modifier.size(16.dp), tint = tint)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.storage_job_last, label, relativeTime(lastRun.start * 1000)),
                        style = small,
                        color = if (!lastRun.running && !lastRun.ok && !lastRun.warnings) Health.critical else muted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (showRun) {
            FilledTonalButton(onClick = onRun, enabled = canRun, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.storage_job_run))
            }
        }
    }
}
