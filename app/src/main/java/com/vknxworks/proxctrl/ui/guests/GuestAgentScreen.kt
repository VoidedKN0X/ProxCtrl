package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.AgentApi
import com.vknxworks.proxctrl.data.guests.ExecResult
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Actions through a VM's QEMU guest agent. */
@Serializable data class GuestAgentRoute(val node: String, val vmid: Int, val name: String = "")

/** How long the screen waits for a command before offering to check again. */
private const val EXEC_WAIT_MS = 60_000L
private const val EXEC_POLL_MS = 700L

private enum class AgentConfirm { FREEZE, SHUTDOWN, RUN }

/**
 * A running VM's guest agent: whether it answers, freezing and thawing the file systems, a clean
 * shutdown asked of the guest's own system, and running one command with its output. Every action is
 * a change on the server, so read-only mode and "Confirm changes" apply.
 */
@Composable
fun GuestAgentScreen(node: String, vmid: Int, name: String) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val ref = remember(node, vmid) { GuestRef(node, GuestType.QEMU, vmid) }
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()
    val writable = server?.readOnly == false
    val perms = access.permissions

    var version by remember { mutableStateOf<String?>(null) }
    var agentError by remember { mutableStateOf<PveError?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<AgentConfirm?>(null) }

    var command by rememberSaveable { mutableStateOf("") }
    var execBusy by remember { mutableStateOf(false) }
    var execPid by remember { mutableStateOf<Long?>(null) }
    var execResult by remember { mutableStateOf<ExecResult?>(null) }
    var execError by remember { mutableStateOf<String?>(null) }

    val canFreeze = writable && AgentApi.canFreeze(perms, ref.aclPath)
    val canExec = writable && AgentApi.canExec(perms, ref.aclPath)
    val canShutdown = writable && AgentApi.canShutdown(perms, ref.aclPath)

    LaunchedEffect(ref, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            version = AgentApi.version(cl, ref)
            agentError = null
        } catch (e: PveError) {
            agentError = e
        }
        loaded = true
        // Asking for the freeze state is a POST in Proxmox's API, so it's skipped in read-only mode.
        if (canFreeze && agentError == null) frozen = runCatching { AgentApi.freezeStatus(cl, ref) }.getOrNull()
        refreshing = false
    }

    /** Waits for the command with [pid] to finish, for at most [EXEC_WAIT_MS]. */
    suspend fun follow(pid: Long) {
        val cl = client ?: return
        val until = System.currentTimeMillis() + EXEC_WAIT_MS
        while (true) {
            val r = AgentApi.execStatus(cl, ref, pid)
            execResult = r
            if (r.exited || System.currentTimeMillis() > until) return
            delay(EXEC_POLL_MS)
        }
    }

    fun run(argv: List<String>) {
        val cl = client ?: return
        execBusy = true
        execError = null
        execResult = null
        execPid = null
        scope.launch {
            try {
                val pid = AgentApi.exec(cl, ref, argv) ?: throw PveError.NotProxmox(null)
                execPid = pid
                follow(pid)
            } catch (e: PveError) {
                execError = errorMessage(context, e, server?.host.orEmpty(), server?.auth)
            }
            execBusy = false
        }
    }

    fun checkAgain() {
        val pid = execPid ?: return
        execBusy = true
        execError = null
        scope.launch {
            try {
                follow(pid)
            } catch (e: PveError) {
                execError = errorMessage(context, e, server?.host.orEmpty(), server?.auth)
            }
            execBusy = false
        }
    }

    val title = listOf(vmid.toString(), name).filter { it.isNotBlank() }.joinToString("  ")
    val argv = remember(command) { AgentApi.splitCommand(command) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.agent_title, title))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)
                val err = agentError
                Group(stringResource(R.string.agent_group)) {
                    GroupRow(
                        title = stringResource(R.string.agent_state),
                        subtitle = when {
                            !loaded -> stringResource(R.string.loading)
                            err != null -> stringResource(R.string.agent_not_answering, errorMessage(context, err, server?.host.orEmpty(), server?.auth))
                            version.isNullOrBlank() -> stringResource(R.string.agent_answering)
                            else -> stringResource(R.string.agent_answering_version, version.orEmpty())
                        },
                    )
                }

                Group(
                    stringResource(R.string.agent_fs_group),
                    footer = stringResource(if (canFreeze) R.string.agent_fs_hint else R.string.agent_fs_not_allowed),
                ) {
                    GroupRow(
                        title = stringResource(R.string.agent_fs_state),
                        subtitle = when (frozen) {
                            "frozen" -> stringResource(R.string.agent_fs_frozen)
                            "thawed" -> stringResource(R.string.agent_fs_thawed)
                            else -> stringResource(R.string.agent_fs_unknown)
                        },
                    )
                    if (canFreeze) {
                        GroupRow(
                            title = stringResource(R.string.agent_fs_freeze),
                            icon = Icons.Outlined.AcUnit,
                            onClick = if (tracker.busy) null else ({ confirm = AgentConfirm.FREEZE }),
                        )
                        GroupRow(
                            title = stringResource(R.string.agent_fs_thaw),
                            icon = Icons.Outlined.WaterDrop,
                            onClick = if (tracker.busy) null else ({
                                val cl = client
                                if (cl != null) {
                                    tracker.run(context.getString(R.string.agent_task_thaw), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                                        AgentApi.thaw(cl, ref)
                                        null
                                    }
                                }
                            }),
                        )
                    }
                }

                Group(
                    stringResource(R.string.agent_power_group),
                    footer = stringResource(if (canShutdown) R.string.agent_shutdown_hint else R.string.not_allowed_action),
                ) {
                    GroupRow(
                        title = stringResource(R.string.agent_shutdown),
                        icon = Icons.Outlined.PowerSettingsNew,
                        titleColor = if (canShutdown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = if (canShutdown && !tracker.busy) ({ confirm = AgentConfirm.SHUTDOWN }) else null,
                    )
                }

                Group(
                    stringResource(R.string.agent_exec_group),
                    footer = stringResource(if (canExec) R.string.agent_exec_hint else R.string.agent_exec_not_allowed),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        CtrlTextField(
                            value = command,
                            onValueChange = { command = it },
                            enabled = canExec && !execBusy,
                            label = { Text(stringResource(R.string.agent_exec_command)) },
                            placeholder = { Text(stringResource(R.string.agent_exec_placeholder)) },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CtrlSecondaryButton(
                            onClick = { confirm = AgentConfirm.RUN },
                            enabled = canExec && !execBusy && argv.isNotEmpty(),
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            androidx.compose.material3.Icon(Icons.Outlined.PlayArrow, null)
                            Text(stringResource(R.string.agent_exec_run), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }

                val result = execResult
                val failure = execError
                if (execBusy || result != null || failure != null) {
                    Group(stringResource(R.string.agent_exec_result)) {
                        when {
                            failure != null -> GroupRow(stringResource(R.string.agent_exec_failed), failure, titleColor = MaterialTheme.colorScheme.error)
                            result == null -> GroupRow(stringResource(R.string.agent_exec_starting))
                            !result.exited -> GroupRow(
                                title = stringResource(if (execBusy) R.string.agent_exec_running else R.string.agent_exec_still_running),
                                subtitle = if (execBusy) null else stringResource(R.string.agent_exec_check_again),
                                onClick = if (execBusy) null else ({ checkAgain() }),
                            )
                            result.signal != null -> GroupRow(stringResource(R.string.agent_exec_signal, result.signal))
                            else -> GroupRow(
                                title = stringResource(R.string.agent_exec_exit, result.exitCode ?: 0),
                                titleColor = if ((result.exitCode ?: 0) == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                        if (result != null && result.out.isNotEmpty()) {
                            Output(stringResource(if (result.outTruncated) R.string.agent_exec_stdout_cut else R.string.agent_exec_stdout), result.out)
                        }
                        if (result != null && result.err.isNotEmpty()) {
                            Output(stringResource(if (result.errTruncated) R.string.agent_exec_stderr_cut else R.string.agent_exec_stderr), result.err)
                        }
                        if (result != null && result.exited && result.out.isEmpty() && result.err.isEmpty()) {
                            GroupRow(stringResource(R.string.agent_exec_no_output))
                        }
                    }
                }
            }
        }
    }

    when (confirm) {
        AgentConfirm.FREEZE -> ConfirmDialog(
            title = stringResource(R.string.agent_fs_freeze_title, title),
            body = stringResource(R.string.agent_fs_freeze_body),
            confirmLabel = stringResource(R.string.agent_fs_freeze),
            icon = Icons.Outlined.AcUnit,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                val cl = client ?: return@ConfirmDialog
                tracker.run(context.getString(R.string.agent_task_freeze), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                    AgentApi.freeze(cl, ref)
                    null
                }
            },
        )
        AgentConfirm.SHUTDOWN -> ConfirmDialog(
            title = stringResource(R.string.agent_shutdown_title, title),
            body = stringResource(R.string.agent_shutdown_body),
            confirmLabel = stringResource(R.string.agent_shutdown_confirm),
            icon = Icons.Outlined.PowerSettingsNew,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                val cl = client ?: return@ConfirmDialog
                tracker.run(context.getString(R.string.agent_task_shutdown), cl, server?.host.orEmpty(), server?.auth, { scope.launch { c.guests.refresh() } }) {
                    AgentApi.shutdown(cl, ref)
                    null
                }
            },
        )
        AgentConfirm.RUN -> ConfirmDialog(
            title = stringResource(R.string.agent_exec_confirm_title, title),
            // Each argument on its own line, so it's clear how the line was split.
            body = stringResource(R.string.agent_exec_confirm_body, argv.joinToString("\n") { "· $it" }),
            confirmLabel = stringResource(R.string.agent_exec_run),
            danger = false,
            icon = Icons.Outlined.PlayArrow,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                if (argv.isNotEmpty()) run(argv)
            },
        )
        null -> Unit
    }
}

/** A block of command output: selectable, monospace, scrolling sideways instead of wrapping. */
@Composable
private fun Output(label: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                text.trimEnd(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            )
        }
    }
}
