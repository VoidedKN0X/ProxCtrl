package com.vknxworks.proxctrl.ui.node

import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.node.KernelState
import com.vknxworks.proxctrl.data.node.NodeApi
import com.vknxworks.proxctrl.data.node.PackageUpdate
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.nav.ConsoleRoute
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Gutter

/**
 * Available package updates with old and new versions and their changelogs, whether a newer kernel
 * waits for a reboot, and refreshing the package database. Upgrading itself is done in the shell.
 */
@Composable
fun NodeUpdatesScreen(node: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()
    val path = NodeApi.aclPath(node)
    val canList = access.permissions.has(path, NodeApi.PRIV_MODIFY)
    val canRefresh = c.sessions.canWrite(path, NodeApi.PRIV_MODIFY)
    val canShell = c.sessions.canWrite(path, NodeApi.PRIV_CONSOLE)

    var updates by remember { mutableStateOf<List<PackageUpdate>?>(null) }
    var kernel by remember { mutableStateOf<KernelState?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var changelogFor by remember { mutableStateOf<PackageUpdate?>(null) }

    LaunchedEffect(server?.id, node, reload) {
        val cl = client ?: return@LaunchedEffect
        if (canList) {
            try {
                updates = NodeApi.updates(cl, node)
                error = null
            } catch (e: PveError) {
                error = e
            }
        }
        runCatching { NodeApi.kernelState(cl, node) }.onSuccess { kernel = it }
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.node_updates_title, node))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)

                kernel?.takeIf { it.rebootRequired }?.let { k -> RebootCard(k) }

                Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
                    if (server?.readOnly == false) {
                        CtrlSecondaryButton(
                            onClick = {
                                val cl = client ?: return@CtrlSecondaryButton
                                tracker.run(
                                    context.getString(R.string.node_updates_task_refresh),
                                    cl,
                                    server?.host.orEmpty(),
                                    server?.auth,
                                    onFinished = { reload++ },
                                ) { NodeApi.refreshPackages(cl, node) }
                            },
                            enabled = canRefresh && !tracker.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.node_updates_refresh))
                        }
                    }
                    if (canShell) {
                        if (server?.readOnly == false) Spacer(Modifier.width(8.dp))
                        CtrlSecondaryButton(
                            onClick = { nav.navigate(ConsoleRoute(kind = "node", node = node, title = context.getString(R.string.node_shell_title, node))) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.node_shell))
                        }
                    }
                }

                val list = updates
                val err = error
                when {
                    !canList -> Text(
                        stringResource(R.string.node_updates_no_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Gutter),
                    )
                    list == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list == null && err != null -> Text(
                        errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    list != null && list.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.CheckCircle,
                        title = stringResource(R.string.node_updates_none),
                        body = stringResource(R.string.node_updates_none_hint),
                        modifier = Modifier.heightIn(min = 280.dp),
                    )
                    list != null -> Group(
                        title = pluralStringResource(R.plurals.node_updates_count, list.size, list.size),
                        footer = stringResource(R.string.node_updates_footer),
                    ) {
                        list.forEach { u -> UpdateRow(u, onClick = { changelogFor = u }) }
                    }
                }
            }
        }
    }

    changelogFor?.let { u ->
        ChangelogDialog(node, u, onDismiss = { changelogFor = null })
    }
}

@Composable
private fun RebootCard(k: KernelState) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, com.vknxworks.proxctrl.ui.theme.CtrlColors.Outline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.RestartAlt, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.node_reboot_required), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.node_reboot_required_body, k.newestInstalled.orEmpty(), k.running),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UpdateRow(u: PackageUpdate, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(u.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (u.oldVersion.isNotBlank()) stringResource(R.string.node_update_versions, u.oldVersion, u.newVersion)
            else stringResource(R.string.node_update_new, u.newVersion),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val meta = listOf(u.title, u.origin).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun ChangelogDialog(node: String, u: PackageUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val server = active?.server
    var text by remember(u) { mutableStateOf<String?>(null) }
    var failed by remember(u) { mutableStateOf<String?>(null) }
    LaunchedEffect(u) {
        val cl = active?.client ?: return@LaunchedEffect
        try {
            text = NodeApi.changelog(cl, node, u.name, u.newVersion).trim().ifBlank { context.getString(R.string.node_changelog_empty) }
        } catch (e: PveError) {
            failed = errorMessage(context, e, server?.host.orEmpty(), server?.auth)
        }
    }
    CtrlDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.node_changelog_title, u.name)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.node_update_versions, u.oldVersion.ifBlank { "—" }, u.newVersion),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.size(8.dp))
                val t = text
                when {
                    failed != null -> Text(failed.orEmpty(), color = MaterialTheme.colorScheme.error)
                    t == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> SelectionContainer {
                        // Changelogs can be long; the first part covers the new version.
                        Text(t.take(20_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
