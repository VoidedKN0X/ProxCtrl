package com.vknxworks.proxctrl.ui.storage

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.storage.ContentItem
import com.vknxworks.proxctrl.data.storage.IsoUpload
import com.vknxworks.proxctrl.data.storage.PickedFile
import com.vknxworks.proxctrl.data.storage.StorageApi
import com.vknxworks.proxctrl.data.storage.StorageInfo
import com.vknxworks.proxctrl.ui.components.ConfirmDialog
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.EmptyState
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.UsageBar
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.components.usageColor
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.theme.Gutter
import com.vknxworks.proxctrl.ui.theme.Health

/** The order content types are shown in. */
private val CONTENT_ORDER = listOf("backup", "iso", "vztmpl", "images", "rootdir", "import", "snippets")

/**
 * What's on one storage, by type: backups (deletable), ISO images (upload from the phone), container
 * templates (download from the Proxmox list), and disk images.
 */
@Composable
fun StorageContentScreen(node: String, storage: String) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()
    val uploader = rememberIsoUploader()

    var info by remember { mutableStateOf<StorageInfo?>(null) }
    var items by remember { mutableStateOf<List<ContentItem>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ContentItem?>(null) }
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(node, storage, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            info = StorageApi.list(cl, node).firstOrNull { it.id == storage }
            items = StorageApi.content(cl, node, storage)
            error = null
        } catch (e: PveError) {
            error = e
        }
        refreshing = false
    }

    val pickIso = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val f = IsoUpload.describe(context.contentResolver, uri)
            uploader.reset()
            pickError = null
            picked = f
        }
    }

    val writable = server?.readOnly == false
    val canTemplates = writable && c.sessions.canWrite("/storage/$storage", StorageApi.PRIV_TEMPLATE)
    val holdsIso = info?.holds("iso") == true
    val holdsTemplates = info?.holds("vztmpl") == true

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = storage,
            actions = {
                if (writable && holdsTemplates) {
                    IconButton(onClick = { nav.navigate(StorageTemplatesRoute(node, storage)) }, enabled = canTemplates) {
                        Icon(Icons.Filled.Download, stringResource(R.string.storage_templates_download))
                    }
                }
                if (writable && holdsIso) {
                    IconButton(onClick = { com.vknxworks.proxctrl.data.lock.AppLock.expectReturn(); pickIso.launch(arrayOf("*/*")) }, enabled = canTemplates && !uploader.busy) {
                        Icon(Icons.Filled.Upload, stringResource(R.string.storage_upload_iso))
                    }
                }
            },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)
                info?.let { s -> StorageSummary(s, settings.diskWarnPercent) }
                pickError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Gutter + 4.dp))
                }
                val err = error
                val list = items
                when {
                    list == null && err != null -> ErrorText(err)
                    list == null -> LoadingBox()
                    list.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Inventory2,
                        title = stringResource(R.string.storage_content_empty),
                        body = stringResource(R.string.storage_content_empty_hint),
                        modifier = Modifier.heightIn(min = 320.dp),
                    )
                    else -> {
                        val types = list.map { it.content }.distinct().sortedBy { t -> CONTENT_ORDER.indexOf(t).let { if (it < 0) 99 else it } }
                        if (types.size > 1) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.storage_filter_all, list.size)) })
                                types.forEach { t ->
                                    FilterChip(
                                        selected = filter == t,
                                        onClick = { filter = if (filter == t) null else t },
                                        label = { Text(stringResource(R.string.storage_filter_type, stringResource(contentLabel(t)), list.count { it.content == t })) },
                                    )
                                }
                            }
                        }
                        types.filter { filter == null || it == filter }.forEach { t ->
                            val inType = list.filter { it.content == t }.sortedWith(compareByDescending<ContentItem> { it.ctime }.thenBy { it.name })
                            Group(stringResource(contentLabel(t))) {
                                inType.forEach { item ->
                                    val canDelete = writable && item.content == "backup" && !item.isProtected && (
                                        c.sessions.canWrite("/storage/$storage", StorageApi.PRIV_ALLOCATE) ||
                                            (
                                                c.sessions.canWrite("/storage/$storage", StorageApi.PRIV_ALLOCATE_SPACE) &&
                                                    item.vmid != null && c.sessions.canWrite("/vms/${item.vmid}", "VM.Backup")
                                                )
                                        )
                                    ContentRow(
                                        item = item,
                                        showDelete = writable && item.content == "backup",
                                        canDelete = canDelete && !tracker.busy,
                                        onDelete = { deleting = item },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.storage_delete_backup_title),
            body = stringResource(
                R.string.storage_delete_backup_body,
                item.name,
                if (item.ctime > 0) storageDateTime(item.ctime) else "?",
                formatBytes(item.size),
            ),
            confirmLabel = stringResource(R.string.delete),
            icon = Icons.Filled.Delete,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                val cl = client ?: return@ConfirmDialog
                tracker.run(context.getString(R.string.storage_delete_backup_task, item.name), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                    StorageApi.delete(cl, node, storage, item.volid)
                }
            },
        )
    }

    picked?.let { f ->
        val cl = client
        if (cl != null) {
            IsoUploadDialog(
                client = cl,
                node = node,
                storage = storage,
                file = f,
                uploader = uploader,
                onUploaded = { reload++ },
                onDismiss = { picked = null; uploader.reset() },
            )
        }
    }
}

@Composable
private fun StorageSummary(s: StorageInfo, warnAt: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter + 4.dp, vertical = 8.dp)) {
        Text(
            listOfNotNull(
                s.type,
                if (s.shared) stringResource(R.string.storage_shared) else null,
                when {
                    !s.enabled -> stringResource(R.string.storage_disabled)
                    !s.active -> stringResource(R.string.storage_not_active)
                    else -> null
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.total > 0) {
            val f = s.fraction
            Spacer(Modifier.height(8.dp))
            UsageBar(f, usageColor(f, warnAt, MaterialTheme.colorScheme.primary), height = 8.dp)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.storage_usage_free, formatBytes(s.used), formatBytes(s.total), percent(f ?: 0.0), formatBytes(s.avail)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    "backup" -> Icons.Outlined.Inventory2
    "iso" -> Icons.Outlined.Album
    "vztmpl" -> Icons.Outlined.Layers
    "images", "rootdir", "import" -> Icons.Outlined.SdCard
    else -> Icons.Outlined.Description
}

@Composable
private fun ContentRow(item: ContentItem, showDelete: Boolean, canDelete: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(item.content), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.isProtected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Lock, stringResource(R.string.storage_protected), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                when (item.verification) {
                    "ok" -> {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.CheckCircle, stringResource(R.string.storage_verified), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    "failed" -> {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Error, stringResource(R.string.storage_verify_failed), Modifier.size(16.dp), tint = Health.critical)
                    }
                }
            }
            val detail = listOfNotNull(
                item.vmid?.let { stringResource(R.string.storage_item_guest, it) },
                if (item.ctime > 0) storageDateTime(item.ctime) else null,
                if (item.size > 0) formatBytes(item.size) else null,
                item.format.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.notes.isNotBlank()) {
                Text(item.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showDelete) {
            Box {
                IconButton(onClick = onDelete, enabled = canDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        stringResource(if (item.isProtected) R.string.storage_delete_protected else R.string.delete),
                        tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}
