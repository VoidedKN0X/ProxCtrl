package com.vknxworks.proxctrl.ui.storage

import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlProgressBar
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.storage.IsoUpload
import com.vknxworks.proxctrl.data.storage.PickedFile
import com.vknxworks.proxctrl.data.tasks.TaskApi
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where an ISO upload stands. */
sealed interface UploadState {
    data object Idle : UploadState
    data class Sending(val sent: Long, val total: Long) : UploadState
    /** The file is on the server; Proxmox's task is moving it into the storage. */
    data object Finishing : UploadState
    data object Done : UploadState
    data class Failed(val message: String) : UploadState
}

/** Runs one ISO upload and keeps its progress for the screen. */
@Stable
class IsoUploader(private val scope: CoroutineScope, private val context: Context) {
    var state by mutableStateOf<UploadState>(UploadState.Idle)
        private set

    val busy: Boolean get() = state is UploadState.Sending || state is UploadState.Finishing

    fun start(client: PveClient, node: String, storage: String, file: PickedFile, fileName: String, onDone: () -> Unit = {}) {
        if (busy) return
        state = UploadState.Sending(0, file.size)
        scope.launch {
            try {
                val upid = IsoUpload.upload(client, context.contentResolver, node, storage, file, fileName) { sent, total ->
                    state = UploadState.Sending(sent, total)
                }
                state = UploadState.Finishing
                if (upid != null) {
                    val result = TaskApi.await(client, upid)
                    if (!result.ok) {
                        state = UploadState.Failed(result.exitStatus ?: context.getString(R.string.storage_upload_failed))
                        return@launch
                    }
                }
                state = UploadState.Done
                onDone()
            } catch (e: PveError) {
                state = UploadState.Failed(errorMessage(context, e, client.server.host, client.server.auth))
            } catch (e: java.io.IOException) {
                state = UploadState.Failed(e.message ?: context.getString(R.string.storage_upload_failed))
            } catch (e: SecurityException) {
                state = UploadState.Failed(context.getString(R.string.storage_upload_cant_read))
            }
        }
    }

    fun reset() {
        if (!busy) state = UploadState.Idle
    }
}

@Composable
fun rememberIsoUploader(): IsoUploader {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember { IsoUploader(scope, context) }
}

/** File name field plus the upload's progress or result. */
@Composable
fun IsoUploadBody(file: PickedFile, fileName: String, onFileName: (String) -> Unit, state: UploadState, storage: String?) {
    Column {
        Text(
            if (file.size >= 0) stringResource(R.string.storage_upload_file_size, file.name, formatBytes(file.size)) else file.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        CtrlTextField(
            value = fileName,
            onValueChange = onFileName,
            singleLine = true,
            enabled = state is UploadState.Idle || state is UploadState.Failed,
            label = { Text(stringResource(R.string.storage_upload_name)) },
            isError = !IsoUpload.isIsoName(fileName),
            supportingText = { Text(stringResource(R.string.storage_upload_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        when (state) {
            is UploadState.Sending -> {
                Spacer(Modifier.height(8.dp))
                if (state.total > 0) {
                    CtrlProgressBar(progress = { (state.sent.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.storage_upload_progress, formatBytes(state.sent), formatBytes(state.total)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    CtrlProgressBar(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.storage_upload_progress_unknown, formatBytes(state.sent)), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.storage_upload_keep_open), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UploadState.Finishing -> {
                Spacer(Modifier.height(8.dp))
                CtrlProgressBar(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.storage_upload_finishing), style = MaterialTheme.typography.bodySmall)
            }
            UploadState.Done -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.storage_upload_done, fileName, storage.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            is UploadState.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            UploadState.Idle -> Unit
        }
    }
}

/** Upload one picked ISO to a known storage (from the storage's content screen). */
@Composable
fun IsoUploadDialog(
    client: PveClient,
    node: String,
    storage: String,
    file: PickedFile,
    uploader: IsoUploader,
    onUploaded: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(file) { mutableStateOf(file.name) }
    val state = uploader.state
    CtrlDialog(
        onDismissRequest = { if (!uploader.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !uploader.busy, dismissOnClickOutside = !uploader.busy),
        icon = { Icon(Icons.Outlined.Upload, null) },
        title = { Text(stringResource(R.string.storage_upload_to, storage)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                IsoUploadBody(file, name, { name = it }, state, storage)
            }
        },
        confirmButton = {
            when (state) {
                UploadState.Done -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
                else -> TextButton(
                    onClick = { uploader.start(client, node, storage, file, name, onUploaded) },
                    enabled = !uploader.busy && IsoUpload.isIsoName(name),
                ) { Text(stringResource(if (state is UploadState.Failed) R.string.retry else R.string.storage_upload)) }
            }
        },
        dismissButton = {
            if (!uploader.busy && state !is UploadState.Done) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
