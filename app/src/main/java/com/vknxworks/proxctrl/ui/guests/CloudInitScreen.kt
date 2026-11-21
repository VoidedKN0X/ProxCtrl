package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.CloudInitApi
import com.vknxworks.proxctrl.data.guests.CloudInitConfig
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.SecureScreen
import com.vknxworks.proxctrl.ui.components.TaskCard
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.components.rememberTaskTracker
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.serialization.Serializable

/** A VM's cloud-init settings. */
@Serializable data class CloudInitRoute(val node: String, val vmid: Int, val name: String = "")

/** One setting being edited: its configuration key, what the dialog calls it and how it's typed. */
private data class CiEdit(
    val key: String,
    val label: String,
    val value: String,
    val hint: String,
    val secret: Boolean = false,
    val multiline: Boolean = false,
)

/**
 * Cloud-init settings of a VM: user, password, SSH keys, DNS, package upgrade and the IP settings of
 * each network device, plus writing the image again so a running guest's next boot picks them up.
 */
@Composable
fun CloudInitScreen(node: String, vmid: Int, name: String) {
    val context = LocalContext.current
    val c = context.container
    val ref = remember(node, vmid) { GuestRef(node, GuestType.QEMU, vmid) }
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client
    val tracker = rememberTaskTracker()

    var config by remember { mutableStateOf<CloudInitConfig?>(null) }
    var pending by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CiEdit?>(null) }

    val canEdit = c.sessions.canWrite(ref.aclPath, CloudInitApi.PRIV)
    val editable = canEdit && !tracker.busy

    LaunchedEffect(ref, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            config = CloudInitApi.load(cl, ref)
            error = null
            pending = runCatching { CloudInitApi.pendingKeys(cl, ref) }.getOrDefault(emptyList())
        } catch (e: PveError) {
            error = e
        }
        refreshing = false
    }

    fun save(label: String, key: String, value: String) {
        val cl = client ?: return
        val digest = config?.digest
        tracker.run(label, cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
            CloudInitApi.set(cl, ref, key, value, digest)
            null
        }
    }

    val title = listOf(vmid.toString(), name).filter { it.isNotBlank() }.joinToString("  ")
    val notSet = stringResource(R.string.ci_not_set)
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.ci_title, title))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                TaskCard(tracker)
                val cfg = config
                val err = error
                when {
                    cfg == null && err != null -> Text(
                        errorMessage(context, err, server?.host.orEmpty(), server?.auth),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Gutter),
                    )
                    cfg == null -> Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Gutter))
                    else -> {
                        if (!cfg.hasDrive) {
                            Text(
                                stringResource(R.string.ci_no_drive),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Gutter + 4.dp, vertical = 8.dp),
                            )
                        }
                        val userLabel = stringResource(R.string.ci_user)
                        val passwordLabel = stringResource(R.string.ci_password)
                        val keysLabel = stringResource(R.string.ci_keys)
                        val userHint = stringResource(R.string.ci_user_hint)
                        val passwordHint = stringResource(R.string.ci_password_hint)
                        val keysHint = stringResource(R.string.ci_keys_hint)
                        Group(
                            stringResource(R.string.ci_group_account),
                            footer = if (canEdit) null else stringResource(R.string.ci_not_allowed),
                        ) {
                            GroupRow(
                                title = userLabel,
                                subtitle = cfg.user ?: stringResource(R.string.ci_user_default),
                                showChevron = editable,
                                onClick = if (editable) ({ editing = CiEdit("ciuser", userLabel, cfg.user.orEmpty(), userHint) }) else null,
                            )
                            GroupRow(
                                title = passwordLabel,
                                subtitle = stringResource(if (cfg.passwordSet) R.string.ci_password_set else R.string.ci_not_set),
                                showChevron = editable,
                                onClick = if (editable) ({ editing = CiEdit("cipassword", passwordLabel, "", passwordHint, secret = true) }) else null,
                            )
                            val keyCount = cfg.sshKeys.lines().count { it.isNotBlank() }
                            GroupRow(
                                title = keysLabel,
                                subtitle = if (keyCount == 0) notSet else pluralStringResource(R.plurals.ci_keys_count, keyCount, keyCount),
                                showChevron = editable,
                                onClick = if (editable) ({ editing = CiEdit("sshkeys", keysLabel, cfg.sshKeys, keysHint, multiline = true) }) else null,
                            )
                            if (canEdit) {
                                GroupSwitch(
                                    title = stringResource(R.string.ci_upgrade),
                                    subtitle = stringResource(R.string.ci_upgrade_hint),
                                    checked = cfg.upgrade,
                                    onChange = { on -> if (editable) save(context.getString(R.string.ci_task_save, context.getString(R.string.ci_upgrade)), "ciupgrade", if (on) "1" else "0") },
                                )
                            } else {
                                GroupRow(stringResource(R.string.ci_upgrade), stringResource(if (cfg.upgrade) R.string.yes else R.string.no))
                            }
                        }

                        val domainLabel = stringResource(R.string.ci_searchdomain)
                        val dnsLabel = stringResource(R.string.ci_nameserver)
                        val domainHint = stringResource(R.string.ci_searchdomain_hint)
                        val dnsHint = stringResource(R.string.ci_nameserver_hint)
                        val ipHint = stringResource(R.string.ci_ip_hint)
                        Group(stringResource(R.string.ci_group_network), footer = stringResource(R.string.ci_network_footer)) {
                            GroupRow(
                                title = domainLabel,
                                subtitle = cfg.searchDomain ?: stringResource(R.string.ci_host_settings),
                                showChevron = editable,
                                onClick = if (editable) ({ editing = CiEdit("searchdomain", domainLabel, cfg.searchDomain.orEmpty(), domainHint) }) else null,
                            )
                            GroupRow(
                                title = dnsLabel,
                                subtitle = cfg.nameserver ?: stringResource(R.string.ci_host_settings),
                                showChevron = editable,
                                onClick = if (editable) ({ editing = CiEdit("nameserver", dnsLabel, cfg.nameserver.orEmpty(), dnsHint) }) else null,
                            )
                            cfg.ipConfigs.forEach { (key, value) ->
                                val label = stringResource(R.string.ci_ip, "net" + key.removePrefix("ipconfig"))
                                GroupRow(
                                    title = label,
                                    subtitle = value ?: notSet,
                                    showChevron = editable,
                                    onClick = if (editable) ({ editing = CiEdit(key, label, value.orEmpty(), ipHint) }) else null,
                                )
                            }
                        }

                        cfg.custom?.let { custom ->
                            Group(stringResource(R.string.ci_group_custom), footer = stringResource(R.string.ci_custom_footer)) {
                                GroupRow("cicustom", custom)
                            }
                        }

                        Group(
                            footer = if (pending.isNotEmpty()) stringResource(R.string.ci_regenerate_pending, pending.joinToString(", "))
                            else stringResource(R.string.ci_regenerate_hint),
                        ) {
                            GroupRow(
                                title = stringResource(R.string.ci_regenerate),
                                icon = Icons.Outlined.Refresh,
                                titleColor = if (editable && cfg.hasDrive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = if (editable && cfg.hasDrive) ({
                                    val cl = client
                                    if (cl != null) {
                                        tracker.run(context.getString(R.string.ci_task_regenerate), cl, server?.host.orEmpty(), server?.auth, { reload++ }) {
                                            CloudInitApi.regenerate(cl, ref)
                                            null
                                        }
                                    }
                                }) else null,
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { edit ->
        CiEditDialog(
            edit = edit,
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                val value = if (edit.key == "sshkeys" && text.isNotBlank()) CloudInitApi.encodeKeys(text) else text.trim()
                // An empty password field means "leave it as it is", never "remove it".
                if (!(edit.secret && value.isEmpty())) save(context.getString(R.string.ci_task_save, edit.label), edit.key, value)
            },
        )
    }
}

@Composable
private fun CiEditDialog(edit: CiEdit, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // A password is typed here: keep the dialog out of screenshots, like the sign-in screens.
    if (edit.secret) SecureScreen()
    var text by remember { mutableStateOf(edit.value) }
    CtrlDialog(
        onDismissRequest = onDismiss,
        title = { Text(edit.label) },
        text = {
            Column {
                CtrlTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !edit.multiline,
                    minLines = if (edit.multiline) 4 else 1,
                    maxLines = if (edit.multiline) 10 else 1,
                    textStyle = if (edit.multiline) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                    visualTransformation = if (edit.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = if (edit.secret) KeyboardType.Password else KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    edit.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
