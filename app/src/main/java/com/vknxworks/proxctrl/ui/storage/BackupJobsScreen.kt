package com.vknxworks.proxctrl.ui.storage

import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import com.vknxworks.proxctrl.data.storage.JobEdit
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
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
    var editing by remember { mutableStateOf<BackupJob?>(null) }
    val guestList by c.guests.state.collectAsStateWithLifecycle()

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
    // Changing a job needs Sys.Modify on / and Datastore.Allocate on the storage the job writes to.
    fun canEdit(job: BackupJob): Boolean = server?.readOnly == false && access.permissions.has("/", BackupJobApi.PRIV_MODIFY) &&
        (job.storage.isBlank() || access.permissions.has("/storage/${job.storage}", BackupJobApi.PRIV_STORAGE))

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
                        icon = Icons.Outlined.Schedule,
                        title = stringResource(R.string.storage_jobs_empty),
                        body = stringResource(R.string.storage_jobs_empty_hint),
                        modifier = Modifier.heightIn(min = 320.dp),
                    )
                    else -> Group(
                        footer = stringResource(R.string.storage_jobs_footer) +
                            if (server?.readOnly == false && list.none { canEdit(it) }) "\n" + stringResource(R.string.job_edit_not_allowed) else "",
                    ) {
                        list.forEachIndexed { i, job ->
                            JobRow(
                                job = job,
                                lastRun = lastRuns?.get(job.id),
                                lastRunLoading = lastRuns == null,
                                showRun = server?.readOnly == false,
                                canRun = canRun && !tracker.busy,
                                onRun = { running = job },
                                canEdit = canEdit(job) && !tracker.busy,
                                onEdit = { editing = job },
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
            icon = Icons.Outlined.PlayArrow,
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

    editing?.let { job ->
        JobEditDialog(
            job = job,
            guests = guestList.guests.filter { !it.isTemplate }.sortedBy { it.vmid }.map { it.vmid.toString() to it.name },
            onDismiss = { editing = null },
            onSave = { edit ->
                editing = null
                val cl = client ?: return@JobEditDialog
                tracker.run(context.getString(R.string.job_edit_task, jobTitle(job)), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                    BackupJobApi.update(cl, job, edit)
                    null
                }
            },
        )
    }
}

private fun jobTitle(job: BackupJob): String = job.comment.ifBlank { job.id }

@Composable
private fun modeLabel(mode: String): String = when (mode) {
    "snapshot" -> stringResource(R.string.job_mode_snapshot)
    "suspend" -> stringResource(R.string.job_mode_suspend)
    "stop" -> stringResource(R.string.job_mode_stop)
    else -> mode
}

@Composable
private fun compressLabel(compress: String): String = when (compress) {
    "0" -> stringResource(R.string.job_compress_none)
    "zstd" -> "ZSTD"
    "gzip" -> "GZIP"
    "lzo" -> "LZO"
    else -> compress
}

private enum class SelectionKind { ALL, CHOSEN, EXCEPT }

/**
 * Changes a scheduled backup job: on or off, its name, schedule, mode, compression and which guests
 * it covers. Proxmox checks the schedule and shows its own message if it can't read it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun JobEditDialog(job: BackupJob, guests: List<Pair<String, String>>, onDismiss: () -> Unit, onSave: (JobEdit) -> Unit) {
    val start = remember(job) { JobEdit.from(job) }
    var enabled by remember { mutableStateOf(start.enabled) }
    var comment by remember { mutableStateOf(start.comment) }
    var schedule by remember { mutableStateOf(start.schedule) }
    var mode by remember { mutableStateOf(start.mode) }
    var compress by remember { mutableStateOf(start.compress) }
    val pool = job.selection as? JobSelection.Pool
    var kind by remember {
        mutableStateOf(
            when (job.selection) {
                is JobSelection.Guests -> SelectionKind.CHOSEN
                is JobSelection.AllExcept -> SelectionKind.EXCEPT
                else -> SelectionKind.ALL
            },
        )
    }
    var picked by remember {
        mutableStateOf(
            when (val sel = job.selection) {
                is JobSelection.Guests -> sel.vmids.toSet()
                is JobSelection.AllExcept -> sel.vmids.toSet()
                else -> emptySet()
            },
        )
    }
    // Guests the job names that this sign-in can't see (or that are gone) stay in the list, by ID.
    val rows = remember(guests, job) {
        val known = guests.map { it.first }.toSet()
        val extra = when (val sel = job.selection) {
            is JobSelection.Guests -> sel.vmids
            is JobSelection.AllExcept -> sel.vmids
            else -> emptyList()
        }.filter { it !in known }.map { it to "" }
        (guests + extra).sortedBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }
    }
    val ids = rows.map { it.first }.filter { it in picked }
    val selection: JobSelection = when {
        pool != null -> pool
        kind == SelectionKind.ALL -> JobSelection.All
        kind == SelectionKind.CHOSEN -> JobSelection.Guests(ids)
        // "All except nobody" is simply all guests.
        ids.isEmpty() -> JobSelection.All
        else -> JobSelection.AllExcept(ids)
    }
    val valid = schedule.isNotBlank() && !(kind == SelectionKind.CHOSEN && pool == null && ids.isEmpty())
    CtrlDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.job_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                GroupSwitch(title = stringResource(R.string.job_edit_enabled), checked = enabled, onChange = { enabled = it })
                CtrlTextField(
                    value = comment,
                    onValueChange = { comment = it.take(512) },
                    label = { Text(stringResource(R.string.job_edit_comment)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlTextField(
                    value = schedule,
                    onValueChange = { schedule = it.take(128) },
                    label = { Text(stringResource(R.string.job_edit_schedule)) },
                    supportingText = { Text(stringResource(R.string.job_edit_schedule_hint)) },
                    isError = schedule.isBlank(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.job_edit_mode), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    (BackupJobApi.MODES + mode).distinct().forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(modeLabel(m)) })
                    }
                }
                Text(stringResource(R.string.job_edit_compress), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    (BackupJobApi.COMPRESSIONS + compress).distinct().forEach { z ->
                        FilterChip(selected = compress == z, onClick = { compress = z }, label = { Text(compressLabel(z)) })
                    }
                }
                Text(stringResource(R.string.job_edit_guests), style = MaterialTheme.typography.labelLarge)
                if (pool != null) {
                    Text(stringResource(R.string.job_edit_pool, pool.pool), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == SelectionKind.ALL, onClick = { kind = SelectionKind.ALL }, label = { Text(stringResource(R.string.storage_job_all)) })
                        FilterChip(selected = kind == SelectionKind.CHOSEN, onClick = { kind = SelectionKind.CHOSEN }, label = { Text(stringResource(R.string.job_edit_chosen)) })
                        FilterChip(selected = kind == SelectionKind.EXCEPT, onClick = { kind = SelectionKind.EXCEPT }, label = { Text(stringResource(R.string.job_edit_except)) })
                    }
                    if (kind != SelectionKind.ALL) {
                        if (kind == SelectionKind.CHOSEN && ids.isEmpty()) {
                            Text(stringResource(R.string.job_edit_pick_one), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        rows.forEach { (id, name) ->
                            val on = id in picked
                            Row(
                                Modifier.fillMaxWidth().toggleable(value = on, role = Role.Checkbox, onValueChange = { picked = if (it) picked + id else picked - id }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(listOf(id, name).filter { it.isNotBlank() }.joinToString("  "), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(JobEdit(enabled, schedule, comment, mode, compress, selection)) }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun selectionText(s: JobSelection): String = when (s) {
    JobSelection.All -> stringResource(R.string.storage_job_all)
    is JobSelection.AllExcept -> stringResource(R.string.storage_job_all_except, s.vmids.joinToString(", "))
    is JobSelection.Guests -> if (s.vmids.isEmpty()) stringResource(R.string.storage_job_no_guests) else stringResource(R.string.storage_job_guests, s.vmids.joinToString(", "))
    is JobSelection.Pool -> stringResource(R.string.storage_job_pool, s.pool)
}

@Composable
private fun JobRow(
    job: BackupJob,
    lastRun: JobRun?,
    lastRunLoading: Boolean,
    showRun: Boolean,
    canRun: Boolean,
    onRun: () -> Unit,
    canEdit: Boolean,
    onEdit: () -> Unit,
) {
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
                        lastRun.running -> Triple(Icons.Outlined.Schedule, MaterialTheme.colorScheme.primary, stringResource(R.string.storage_job_last_running))
                        lastRun.ok -> Triple(Icons.Outlined.CheckCircle, Health.ok, stringResource(R.string.storage_job_last_ok))
                        lastRun.warnings -> Triple(Icons.Outlined.Warning, Health.warn, lastRun.status)
                        else -> Triple(Icons.Outlined.Error, Health.critical, lastRun.status.ifBlank { stringResource(R.string.storage_job_last_failed) })
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
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CtrlSecondaryButton(onClick = onRun, enabled = canRun) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.storage_job_run))
                }
                Spacer(Modifier.width(8.dp))
                CtrlSecondaryButton(onClick = onEdit, enabled = canEdit) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.job_edit))
                }
            }
        }
    }
}
