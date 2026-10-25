package com.vknxworks.proxctrl.ui.tasks

import com.vknxworks.proxctrl.ui.components.CtrlProgressBar
import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.tasks.ProxTask
import com.vknxworks.proxctrl.data.tasks.StatusFilter
import com.vknxworks.proxctrl.ui.theme.Health
import com.vknxworks.proxctrl.ui.components.relativeTime
import com.vknxworks.proxctrl.ui.theme.Gutter

/** Running, OK, warnings or failed, as an icon. */
@Composable
fun TaskStatusIcon(task: ProxTask, modifier: Modifier = Modifier) {
    when {
        task.isRunning -> CircularProgressIndicator(modifier.size(20.dp), strokeWidth = 2.dp)
        task.isOk -> Icon(Icons.Outlined.CheckCircle, stringResource(R.string.tasks_status_ok), modifier.size(20.dp), tint = Health.ok)
        task.hasWarnings -> Icon(Icons.Outlined.Warning, stringResource(R.string.tasks_status_warnings), modifier.size(20.dp), tint = Health.warn)
        else -> Icon(Icons.Outlined.Error, stringResource(R.string.tasks_status_error), modifier.size(20.dp), tint = Health.critical)
    }
}

/** One finished (or running) task in a list. */
@Composable
fun TaskRow(task: ProxTask, guestName: String?, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskStatusIcon(task)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                taskDescription(context, task.type, task.id, guestName),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.tasks_row_detail,
                    relativeTime(task.start * 1000),
                    if (task.isRunning) stringResource(R.string.tasks_running_for, formatDuration(task.duration())) else formatDuration(task.duration()),
                    task.fullUser,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.isError || task.hasWarnings) {
                Text(
                    task.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val PERCENT = Regex("(\\d{1,3}(?:\\.\\d+)?)\\s?%")

/** The last percentage in a log line ("INFO:  42% (8.0 GiB of 19.0 GiB)…"), as 0..1. */
internal fun progressOf(line: String?): Float? =
    line?.let { PERCENT.findAll(it).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull() }
        ?.takeIf { it in 0f..100f }?.div(100f)

/** A running task with its latest log line, a progress bar when the log shows one, and Stop. */
@Composable
fun RunningTaskCard(task: ProxTask, guestName: String?, lastLine: String?, canStop: Boolean, onStop: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, com.vknxworks.proxctrl.ui.theme.CtrlColors.Outline),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(taskDescription(context, task.type, task.id, guestName), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.tasks_running_detail, task.node, formatDuration(task.duration()), task.fullUser),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (canStop) {
                    Spacer(Modifier.width(8.dp))
                    CtrlSecondaryButton(onClick = onStop) {
                        Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.tasks_stop))
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
            val progress = progressOf(lastLine)
            if (progress != null) {
                CtrlProgressBar(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                CtrlProgressBar(modifier = Modifier.fillMaxWidth())
            }
            if (!lastLine.isNullOrBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    lastLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A filter chip that opens a menu of choices; null is "all". */
@Composable
fun <T> FilterMenuChip(
    label: String,
    selected: T?,
    choices: List<T>,
    allLabel: String,
    nameOf: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(if (selected == null) label else nameOf(selected), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open && !com.vknxworks.proxctrl.ui.components.LocalAppCovered.current, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { open = false; onSelect(null) })
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(nameOf(choice)) }, onClick = { open = false; onSelect(choice) })
            }
        }
    }
}

/** The history filters: status, type, user and (unless fixed) guest. */
@Composable
fun TaskFilterRow(
    status: StatusFilter,
    type: String?,
    user: String?,
    vmid: Int?,
    types: List<String>,
    users: List<String>,
    guests: List<Pair<Int, String>>?,
    onStatus: (StatusFilter) -> Unit,
    onType: (String?) -> Unit,
    onUser: (String?) -> Unit,
    onGuest: (Int?) -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val statuses = listOf(StatusFilter.OK, StatusFilter.WARNING, StatusFilter.ERROR)
        val statusName: (StatusFilter) -> String = {
            when (it) {
                StatusFilter.OK -> context.getString(R.string.tasks_status_ok)
                StatusFilter.WARNING -> context.getString(R.string.tasks_status_warnings)
                StatusFilter.ERROR -> context.getString(R.string.tasks_status_error)
                StatusFilter.ALL -> context.getString(R.string.tasks_filter_all)
            }
        }
        FilterMenuChip(
            label = stringResource(R.string.tasks_filter_status),
            selected = status.takeIf { it != StatusFilter.ALL },
            choices = statuses,
            allLabel = stringResource(R.string.tasks_filter_all),
            nameOf = statusName,
            onSelect = { onStatus(it ?: StatusFilter.ALL) },
        )
        FilterMenuChip(
            label = stringResource(R.string.tasks_filter_type),
            selected = type,
            choices = types,
            allLabel = stringResource(R.string.tasks_filter_all),
            nameOf = { taskTypeName(context, it) },
            onSelect = onType,
        )
        FilterMenuChip(
            label = stringResource(R.string.tasks_filter_user),
            selected = user,
            choices = users,
            allLabel = stringResource(R.string.tasks_filter_all),
            nameOf = { it },
            onSelect = onUser,
        )
        if (guests != null) {
            FilterMenuChip(
                label = stringResource(R.string.tasks_filter_guest),
                selected = vmid,
                choices = guests.map { it.first },
                allLabel = stringResource(R.string.tasks_filter_all),
                nameOf = { id -> guests.firstOrNull { it.first == id }?.let { "${it.first}  ${it.second}" } ?: id.toString() },
                onSelect = onGuest,
            )
        }
    }
}

/** Status colours for log lines that say ERROR or WARN. */
@Composable
internal fun logLineColor(text: String): Color = when {
    text.contains("error", ignoreCase = true) || text.startsWith("TASK ERROR") -> MaterialTheme.colorScheme.error
    text.startsWith("WARN") || text.contains("warning:", ignoreCase = true) -> Health.warn
    else -> MaterialTheme.colorScheme.onSurface
}
