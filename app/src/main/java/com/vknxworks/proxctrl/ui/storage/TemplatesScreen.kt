package com.vknxworks.proxctrl.ui.storage

import com.vknxworks.proxctrl.ui.components.CtrlTextField
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.storage.Appliance
import com.vknxworks.proxctrl.data.storage.StorageApi
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.theme.Gutter

/** Proxmox's container template list (system, TurnKey…), to download one to a storage. */
@Composable
fun TemplatesScreen(node: String, storage: String) {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()

    var list by remember { mutableStateOf<List<Appliance>?>(null) }
    var have by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var downloading by remember { mutableStateOf<Appliance?>(null) }
    var pullingOci by remember { mutableStateOf(false) }

    LaunchedEffect(node, storage, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            list = StorageApi.appliances(cl, node)
            error = null
        } catch (e: PveError) {
            error = e
        }
        // Templates already on the storage are marked, so they aren't downloaded twice.
        runCatching { StorageApi.content(cl, node, storage) }.onSuccess { items ->
            have = items.filter { it.content == "vztmpl" }.map { it.name }.toSet()
        }
    }

    val canDownload = server?.readOnly == false && c.sessions.canWrite("/storage/$storage", StorageApi.PRIV_TEMPLATE)
    // OCI images (Proxmox VE 9.1 and newer) are pulled by the node itself, which also needs Sys.AccessNetwork.
    val ociSupported = StorageApi.supportsOci(access.version?.version.orEmpty())
    val canPullOci = canDownload && c.sessions.canWrite("/nodes/$node", StorageApi.PRIV_NETWORK)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.storage_templates_title, storage))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            if (access.error != null) ConnectionBanner()
            TaskCard(tracker)
            CtrlTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(stringResource(R.string.storage_templates_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
            )
            if (ociSupported) {
                Group(stringResource(R.string.oci_group), footer = stringResource(R.string.oci_footer)) {
                    GroupRow(
                        title = stringResource(R.string.oci_pull),
                        subtitle = when {
                            server?.readOnly != false -> stringResource(R.string.oci_read_only)
                            !canPullOci -> stringResource(R.string.oci_not_allowed)
                            else -> stringResource(R.string.oci_pull_subtitle)
                        },
                        icon = Icons.Outlined.CloudDownload,
                        showChevron = canPullOci,
                        onClick = if (canPullOci && !tracker.busy) ({ pullingOci = true }) else null,
                    )
                }
            }
            if (!canDownload) {
                Text(
                    stringResource(R.string.not_allowed_action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Gutter + 4.dp),
                )
            }
            val err = error
            val all = list
            when {
                all == null && err != null -> ErrorText(err)
                all == null -> LoadingBox()
                all.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Layers,
                    title = stringResource(R.string.storage_templates_empty),
                    body = stringResource(R.string.storage_templates_empty_hint),
                    modifier = Modifier.heightIn(min = 320.dp),
                )
                else -> {
                    val q = query.trim().lowercase()
                    val shown = if (q.isEmpty()) all else all.filter { a ->
                        a.template.lowercase().contains(q) || a.headline.lowercase().contains(q) || a.os.lowercase().contains(q)
                    }
                    if (shown.isEmpty()) {
                        Text(
                            stringResource(R.string.storage_templates_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 12.dp),
                        )
                    }
                    shown.groupBy { it.section.ifBlank { "other" } }.forEach { (section, entries) ->
                        Group(section.replaceFirstChar { it.uppercase() }) {
                            entries.forEach { a ->
                                TemplateRow(a, downloaded = a.template in have, enabled = canDownload && !tracker.busy) { downloading = a }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pullingOci) {
        OciPullDialog(
            storage = storage,
            onDismiss = { pullingOci = false },
            onPull = { reference, filename ->
                pullingOci = false
                val cl = client ?: return@OciPullDialog
                tracker.run(context.getString(R.string.oci_task, reference), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                    StorageApi.pullOciImage(cl, node, storage, reference, filename)
                }
            },
        )
    }

    downloading?.let { a ->
        ConfirmDialog(
            title = stringResource(R.string.storage_templates_confirm_title),
            body = stringResource(R.string.storage_templates_confirm_body, a.template, storage),
            confirmLabel = stringResource(R.string.storage_templates_download_short),
            danger = false,
            icon = Icons.Outlined.Download,
            onDismiss = { downloading = null },
            onConfirm = {
                downloading = null
                val cl = client ?: return@ConfirmDialog
                tracker.run(context.getString(R.string.storage_templates_task, a.template), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                    StorageApi.downloadTemplate(cl, node, storage, a.template)
                }
            },
        )
    }
}

@Composable
private fun TemplateRow(a: Appliance, downloaded: Boolean, enabled: Boolean, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled && !downloaded, onClick = onDownload)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.headline.ifBlank { a.template }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(a.template, a.version.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (downloaded) {
            Icon(Icons.Outlined.CheckCircle, stringResource(R.string.storage_templates_have), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(
                Icons.Outlined.Download,
                stringResource(R.string.storage_templates_download_short),
                Modifier.size(22.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

/** Asks for the image to pull: its reference (registry/repository:tag) and, optionally, a file name. */
@Composable
private fun OciPullDialog(storage: String, onDismiss: () -> Unit, onPull: (reference: String, filename: String?) -> Unit) {
    var reference by rememberSaveable { mutableStateOf("") }
    var filename by rememberSaveable { mutableStateOf("") }
    val ref = reference.trim()
    val valid = StorageApi.OCI_REFERENCE.matches(ref)
    val plain = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri)
    CtrlDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CloudDownload, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.oci_pull)) },
        text = {
            Column {
                Text(stringResource(R.string.oci_dialog_body, storage), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                CtrlTextField(
                    value = reference,
                    onValueChange = { reference = it.trim() },
                    label = { Text(stringResource(R.string.oci_reference)) },
                    placeholder = { Text("docker.io/library/debian:13") },
                    supportingText = { Text(stringResource(if (ref.isEmpty() || valid) R.string.oci_reference_hint else R.string.oci_reference_invalid)) },
                    isError = ref.isNotEmpty() && !valid,
                    singleLine = true,
                    keyboardOptions = plain,
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlTextField(
                    value = filename,
                    onValueChange = { filename = it.take(255) },
                    label = { Text(stringResource(R.string.oci_filename)) },
                    supportingText = { Text(stringResource(R.string.oci_filename_hint)) },
                    singleLine = true,
                    keyboardOptions = plain,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPull(ref, filename.trim().ifEmpty { null }) }, enabled = valid) { Text(stringResource(R.string.oci_pull_confirm)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
