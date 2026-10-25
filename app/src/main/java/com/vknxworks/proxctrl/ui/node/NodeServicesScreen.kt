package com.vknxworks.proxctrl.ui.node

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.node.NodeApi
import com.vknxworks.proxctrl.data.node.NodeService
import com.vknxworks.proxctrl.data.node.ServiceAction
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.PollWhileVisible
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.theme.Gutter

private const val POLL_MS = 10_000L

/** The node's services with their state, and start, stop and restart (Sys.Modify). */
@Composable
fun NodeServicesScreen(node: String) {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()

    var services by remember { mutableStateOf<List<NodeService>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<NodeService, ServiceAction>?>(null) }

    PollWhileVisible(Triple(server?.id, node, reload), POLL_MS) {
        val cl = client ?: return@PollWhileVisible false
        val ok = try {
            services = NodeApi.services(cl, node)
            error = null
            true
        } catch (e: PveError) {
            error = e
            false
        }
        refreshing = false
        ok
    }

    val canModify = c.sessions.canWrite(NodeApi.aclPath(node), NodeApi.PRIV_MODIFY)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.node_services_title, node))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)
                val list = services
                val err = error
                when {
                    list == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list == null && err != null -> Text(
                        errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    list != null -> {
                        val footer = when {
                            server?.readOnly == true -> null
                            !canModify -> stringResource(R.string.not_allowed_action)
                            else -> stringResource(R.string.node_services_footer)
                        }
                        Group(footer = footer) {
                            list.forEach { s ->
                                ServiceRow(
                                    service = s,
                                    showMenu = server?.readOnly == false,
                                    enabled = canModify && !tracker.busy,
                                    onAction = { action -> pending = s to action },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { (s, action) ->
        val title = when (action) {
            ServiceAction.START -> R.string.node_service_start_title
            ServiceAction.STOP -> R.string.node_service_stop_title
            ServiceAction.RESTART -> R.string.node_service_restart_title
        }
        val body = buildString {
            append(context.getString(
                when (action) {
                    ServiceAction.START -> R.string.node_service_start_body
                    ServiceAction.STOP -> R.string.node_service_stop_body
                    ServiceAction.RESTART -> R.string.node_service_restart_body
                },
            ))
            if (s.carriesConnection && action != ServiceAction.START) {
                append("\n\n")
                append(context.getString(if (action == ServiceAction.STOP) R.string.node_service_connection_stop else R.string.node_service_connection_restart))
            }
        }
        ConfirmDialog(
            title = stringResource(title, s.service),
            body = body,
            confirmLabel = stringResource(action.label()),
            danger = action != ServiceAction.START,
            icon = if (s.carriesConnection && action != ServiceAction.START) Icons.Outlined.Warning else action.icon(),
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                val cl = client ?: return@ConfirmDialog
                tracker.run(
                    context.getString(R.string.node_service_task, context.getString(action.label()), s.service),
                    cl,
                    server?.host.orEmpty(),
                    server?.auth,
                    onFinished = { reload++ },
                ) { NodeApi.serviceAction(cl, node, s.service, action) }
            },
        )
    }
}

private fun ServiceAction.label(): Int = when (this) {
    ServiceAction.START -> R.string.node_service_start
    ServiceAction.STOP -> R.string.node_service_stop
    ServiceAction.RESTART -> R.string.node_service_restart
}

private fun ServiceAction.icon() = when (this) {
    ServiceAction.START -> Icons.Outlined.PlayArrow
    ServiceAction.STOP -> Icons.Outlined.Stop
    ServiceAction.RESTART -> Icons.Outlined.RestartAlt
}

@Composable
private fun ServiceRow(service: NodeService, showMenu: Boolean, enabled: Boolean, onAction: (ServiceAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            when {
                service.isFailed -> "failed"
                service.isRunning -> "running"
                else -> "stopped"
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(service.service, style = MaterialTheme.typography.bodyLarge)
            val state = when {
                service.isFailed -> stringResource(R.string.node_service_failed)
                service.isRunning -> stringResource(R.string.node_service_running)
                service.unitState == "masked" -> stringResource(R.string.node_service_masked)
                service.unitState == "disabled" -> stringResource(R.string.node_service_disabled)
                service.unitState == "not-found" -> stringResource(R.string.node_service_not_installed)
                else -> stringResource(R.string.node_service_stopped)
            }
            Text(
                listOf(state, service.description).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (service.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (showMenu) {
            val installed = service.unitState != "not-found" && service.unitState != "masked"
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled && installed) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions))
                }
                DropdownMenu(expanded = menu && !com.vknxworks.proxctrl.ui.components.LocalAppCovered.current, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.node_service_start)) },
                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                        enabled = !service.isRunning,
                        onClick = { menu = false; onAction(ServiceAction.START) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.node_service_restart)) },
                        leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                        onClick = { menu = false; onAction(ServiceAction.RESTART) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (service.isEssential) R.string.node_service_stop_essential else R.string.node_service_stop))
                        },
                        leadingIcon = { Icon(Icons.Outlined.Stop, null) },
                        // Proxmox refuses to stop pveproxy, pvedaemon and pve-cluster.
                        enabled = service.isRunning && !service.isEssential,
                        onClick = { menu = false; onAction(ServiceAction.STOP) },
                    )
                }
            }
        }
    }
}
