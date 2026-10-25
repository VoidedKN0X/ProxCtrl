package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.CreateApi
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.ResourcesApi
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.nav.LocalNavigator
import com.vknxworks.proxctrl.ui.tasks.TaskDetailRoute
import kotlinx.coroutines.launch

/**
 * The start of the create flow (the + button on Guests): a new container, a new VM, or a clone of
 * one of the server's templates. Whatever is created, its task log opens next.
 */
@Composable
fun CreateGuestScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val list by c.guests.state.collectAsStateWithLifecycle()
    val server = active?.server
    val templates = list.guests.filter { it.isTemplate }.sortedBy { it.vmid }
    var cloning by remember { mutableStateOf<Guest?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }

    // VM.Allocate on /vms (or a single guest path) is what every create needs first.
    val canAllocate = server?.readOnly == false && access.permissions.hasAnywhere("/vms", CreateApi.PRIV_ALLOCATE)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.create_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            if (access.error != null) ConnectionBanner()
            if (server?.readOnly == true) {
                WizardHint(stringResource(R.string.create_read_only), error = true)
            } else if (!access.loading && !canAllocate) {
                WizardHint(stringResource(R.string.create_no_allocate), error = true)
            }
            Group(stringResource(R.string.create_new)) {
                GroupRow(
                    title = stringResource(R.string.create_lxc),
                    subtitle = stringResource(R.string.create_lxc_hint),
                    icon = Icons.Outlined.Inventory2,
                    showChevron = true,
                    onClick = if (canAllocate) ({ nav.navigate(CreateLxcRoute) }) else null,
                )
                GroupRow(
                    title = stringResource(R.string.create_vm),
                    subtitle = stringResource(R.string.create_vm_hint),
                    icon = Icons.Outlined.Computer,
                    showChevron = true,
                    onClick = if (canAllocate) ({ nav.navigate(CreateVmRoute) }) else null,
                )
            }
            error?.let { e ->
                CreateErrorCard(errorMessage(context, e, server?.host.orEmpty(), server?.auth), CreateChecks.isForbidden(e))
            }
            if (templates.isEmpty()) {
                WizardLabel(stringResource(R.string.create_from_template))
                WizardHint(stringResource(R.string.create_no_templates))
            } else Group(
                stringResource(R.string.create_from_template),
                footer = stringResource(R.string.create_from_template_hint),
            ) {
                templates.forEach { t ->
                    val allowed = server?.readOnly == false && c.sessions.canWrite(t.aclPath, ResourcesApi.PRIV_CLONE)
                    GroupRow(
                        title = "${t.vmid}  ${t.name}".trim(),
                        subtitle = stringResource(if (t.type == GuestType.LXC) R.string.lxc else R.string.qemu) +
                            if (!allowed) "  ·  " + stringResource(R.string.create_clone_not_allowed) else "",
                        icon = Icons.Outlined.ContentCopy,
                        showChevron = allowed,
                        onClick = if (allowed) ({ cloning = t }) else null,
                    )
                }
            }
        }
    }

    cloning?.let { t ->
        val ref = GuestRef(t.node, t.type, t.vmid)
        CloneDialog(
            ref = ref,
            sourceName = t.name,
            isTemplate = true,
            onDismiss = { cloning = null },
            onClone = { newId, name, full, storage ->
                cloning = null
                val cl = active?.client ?: return@CloneDialog
                scope.launch {
                    try {
                        val upid = ResourcesApi.clone(cl, ref, newId, name, full, storage)
                        error = null
                        c.appScope.launch { c.guests.refresh() }
                        if (!upid.isNullOrBlank() && upid.startsWith("UPID:")) {
                            nav.nav.navigate(TaskDetailRoute(upid)) { popUpTo<CreateGuestRoute> { inclusive = true } }
                        } else {
                            nav.back()
                        }
                    } catch (e: PveError) {
                        error = e
                    }
                }
            },
        )
    }
}
