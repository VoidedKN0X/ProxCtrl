package com.vknxworks.proxctrl.ui.storage

import com.vknxworks.proxctrl.ui.theme.isSystemDark
import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.vknxworks.proxctrl.data.lock.AppLock
import com.vknxworks.proxctrl.ui.lock.LockOverlays
import com.vknxworks.proxctrl.ui.lock.LockScreen
import androidx.compose.foundation.layout.Box
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.overview.NodeEntry
import com.vknxworks.proxctrl.data.storage.IsoUpload
import com.vknxworks.proxctrl.data.storage.PickedFile
import com.vknxworks.proxctrl.data.storage.StorageApi
import com.vknxworks.proxctrl.data.storage.StorageInfo
import com.vknxworks.proxctrl.ui.components.PillButton
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.theme.Gutter
import com.vknxworks.proxctrl.ui.theme.ProxCtrlTheme
import kotlinx.serialization.builtins.ListSerializer
import com.vknxworks.proxctrl.ui.components.WindowPrivacy

/**
 * Receives a file shared from another app ("Share" → ProxCtrl) and uploads it as an ISO image to a
 * storage of the active server. Everything else about the upload is the same as from a storage's page.
 */
class ShareIsoActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dark = isSystemDark()
        enableEdgeToEdge(
            statusBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(dark),
            navigationBarStyle = com.vknxworks.proxctrl.ui.theme.signalBarStyle(dark),
        )
        val uri = sharedUri(intent)
        val c = container
        // Same app lock as the rest of ProxCtrl: locked on open if set, and "Confirm changes" for the upload.
        AppLock.attach(this, c)
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            val locked by AppLock.state.collectAsStateWithLifecycle()
            val secure = settings.hideScreenshots || locked != AppLock.State.UNLOCKED || AppLock.confirm != null
            WindowPrivacy(this, secure, hideRecents = settings.hideRecents || c.vault.enabled || AppLock.legacy)
            val lockState by AppLock.state.collectAsStateWithLifecycle()
            com.vknxworks.proxctrl.ui.theme.SignalSystemBars(this)
            ProxCtrlTheme(settings.theme, settings.dynamicColor) {
                Box(Modifier.fillMaxSize()) {
                    if (lockState == AppLock.State.UNLOCKED) {
                        ShareIsoScreen(uri, onClose = { finish() })
                        LockOverlays(this@ShareIsoActivity)
                    } else {
                        LockScreen(this@ShareIsoActivity)
                    }
                }
            }
        }
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: intent.data ?: return null
        // Only content:// from another app: a file:// address could point ProxCtrl at its own private files.
        return uri.takeIf { IsoUpload.isForeignContentUri(this, it) }
    }
}

@Composable
private fun ShareIsoScreen(uri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val uploader = rememberIsoUploader()

    val file: PickedFile? = remember(uri) { uri?.let { IsoUpload.describe(context.contentResolver, it) } }
    var name by remember(file) { mutableStateOf(file?.name.orEmpty()) }
    var targets by remember { mutableStateOf<List<StorageInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<StorageInfo?>(null) }

    LaunchedEffect(server?.id) {
        val cl = client ?: return@LaunchedEffect
        try {
            val nodes = cl.get("/nodes", ListSerializer(NodeEntry.serializer())).filter { it.isOnline }.map { it.node }
            val all = nodes.flatMap { n -> runCatching { StorageApi.list(cl, n) }.getOrDefault(emptyList()) }
                .filter { it.enabled && it.active && it.holds("iso") && c.sessions.canWrite("/storage/${it.id}", StorageApi.PRIV_TEMPLATE) }
                // A shared storage shows once per node; once is enough.
                .distinctBy { if (it.shared) it.id else "${it.node}/${it.id}" }
            targets = all
            selected = all.maxByOrNull { it.avail }
            if (all.isEmpty()) loadError = context.getString(R.string.storage_share_no_storage)
        } catch (e: PveError) {
            targets = emptyList()
            loadError = errorMessage(context, e, server?.host.orEmpty(), server?.auth)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter, vertical = 16.dp),
    ) {
        Text(stringResource(R.string.storage_share_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        when {
            file == null -> Text(stringResource(R.string.storage_share_no_file), color = MaterialTheme.colorScheme.error)
            server == null || client == null -> Text(stringResource(R.string.storage_share_no_server))
            server.readOnly -> Text(stringResource(R.string.storage_share_read_only, server.name))
            else -> {
                Text(stringResource(R.string.storage_share_server, server.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                TargetPicker(targets, selected, enabled = !uploader.busy && uploader.state !is UploadState.Done) { selected = it }
                loadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                IsoUploadBody(file, name, { name = it }, uploader.state, selected?.id)
                Spacer(Modifier.height(20.dp))
                val target = selected
                if (uploader.state is UploadState.Done) {
                    PillButton(stringResource(R.string.done), onClick = onClose, modifier = Modifier.fillMaxWidth())
                } else {
                    PillButton(
                        text = stringResource(if (uploader.state is UploadState.Failed) R.string.retry else R.string.storage_upload),
                        onClick = { if (target != null) uploader.start(client, target.node, target.id, file, name) },
                        icon = Icons.Outlined.Upload,
                        enabled = target != null && !uploader.busy && IsoUpload.isIsoName(name),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (!uploader.busy && uploader.state !is UploadState.Done) {
            Spacer(Modifier.height(8.dp))
            CtrlSecondaryButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
        if (file != null && file.size >= 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.storage_share_size_note, formatBytes(file.size)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TargetPicker(targets: List<StorageInfo>?, selected: StorageInfo?, enabled: Boolean, onSelect: (StorageInfo) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val many = targets.orEmpty().map { it.node }.distinct().size > 1
    fun label(s: StorageInfo): String = if (many && !s.shared) "${s.node} / ${s.id}" else s.id
    val text = when {
        targets == null -> stringResource(R.string.loading)
        selected == null -> stringResource(R.string.storage_pick)
        selected.total > 0 -> stringResource(R.string.storage_choice, label(selected), formatBytes(selected.avail))
        else -> label(selected)
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled && !targets.isNullOrEmpty()) open = it }) {
        CtrlTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.storage_share_storage)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            targets.orEmpty().forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(if (s.total > 0) stringResource(R.string.storage_choice, label(s), formatBytes(s.avail)) else label(s))
                    },
                    onClick = { onSelect(s); open = false },
                )
            }
        }
    }
}
