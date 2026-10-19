package com.vknxworks.proxctrl.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.data.tasks.TaskApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the task card shows: one write action and the Proxmox task it started. */
data class TrackedTask(
    val label: String,
    val running: Boolean = true,
    val ok: Boolean = false,
    val warnings: Boolean = false,
    /** Proxmox's exit status or an error message. */
    val message: String? = null,
    /** The end of the task log, shown when it failed or had warnings. */
    val log: List<String> = emptyList(),
)

/**
 * Runs one write action at a time and follows the task it starts until it finishes, so the screen can
 * show "Taking snapshot…" and then the result. The task keeps running on the server if the screen closes.
 */
@Stable
class TaskTracker(
    private val scope: CoroutineScope,
    private val context: Context,
) {
    var current by mutableStateOf<TrackedTask?>(null)
        private set
    private var job: Job? = null

    val busy: Boolean get() = current?.running == true

    /**
     * Starts [action] (which returns the task's UPID, or null when Proxmox finished it straight away)
     * and waits for the task. [onFinished] runs afterwards either way, to reload the screen.
     */
    fun run(
        label: String,
        client: PveClient,
        host: String,
        auth: AuthMethod?,
        onFinished: (ok: Boolean) -> Unit = {},
        action: suspend () -> String?,
    ) {
        if (busy) return
        current = TrackedTask(label)
        job = scope.launch {
            try {
                val upid = action()
                if (upid.isNullOrBlank() || !upid.startsWith("UPID:")) {
                    current = TrackedTask(label, running = false, ok = true)
                    onFinished(true)
                    return@launch
                }
                val state = TaskApi.await(client, upid)
                val log = if (!state.ok || state.warnings) runCatching { TaskApi.logTail(client, upid) }.getOrDefault(emptyList()) else emptyList()
                current = TrackedTask(label, running = false, ok = state.ok, warnings = state.warnings, message = state.exitStatus, log = log)
                onFinished(state.ok)
            } catch (e: PveError) {
                current = TrackedTask(label, running = false, ok = false, message = errorMessage(context, e, host, auth))
                onFinished(false)
            }
        }
    }

    fun dismiss() {
        if (!busy) current = null
    }
}

@Composable
fun rememberTaskTracker(): TaskTracker {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember { TaskTracker(scope, context) }
}

/** The running or finished task, as a card. */
@Composable
fun TaskCard(tracker: TaskTracker, modifier: Modifier = Modifier) {
    val t = tracker.current
    AnimatedVisibility(visible = t != null, modifier = modifier) {
        val task = t ?: return@AnimatedVisibility
        val (color, onColor) = when {
            task.running -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            task.ok && !task.warnings -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            task.ok -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
        Surface(
            color = color,
            contentColor = onColor,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = com.vknxworks.proxctrl.ui.theme.Gutter, vertical = 6.dp),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        task.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = onColor)
                        task.ok && !task.warnings -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp))
                        task.ok -> Icon(Icons.Filled.Warning, null, Modifier.size(20.dp))
                        else -> Icon(Icons.Filled.Error, null, Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when {
                            task.running -> stringResource(R.string.task_running, task.label)
                            task.ok && !task.warnings -> stringResource(R.string.task_done, task.label)
                            task.ok -> stringResource(R.string.task_warnings, task.label)
                            else -> stringResource(R.string.task_failed, task.label)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (!task.running) {
                        IconButton(onClick = tracker::dismiss) { Icon(Icons.Filled.Close, stringResource(R.string.dismiss)) }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                }
                if (task.running) {
                    Text(stringResource(R.string.task_keeps_running), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 32.dp, end = 12.dp))
                }
                val msg = task.message?.takeIf { !task.ok || task.warnings }
                if (!msg.isNullOrBlank() && msg != "OK") {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 32.dp, end = 12.dp))
                }
                if (task.log.isNotEmpty()) {
                    Text(
                        task.log.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 32.dp, end = 12.dp, top = 6.dp),
                    )
                }
            }
        }
    }
}
