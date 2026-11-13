package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.guests.FirewallOptions
import com.vknxworks.proxctrl.data.guests.FirewallRule
import com.vknxworks.proxctrl.data.guests.GuestOption
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.ResourcesApi
import com.vknxworks.proxctrl.ui.components.ConnectionBanner
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.serialization.Serializable

@Serializable data class GuestOptionsRoute(val node: String, val type: String, val vmid: Int, val name: String = "")

/** A guest's options and firewall (options and rules), read-only. */
@Composable
fun GuestOptionsScreen(node: String, type: String, vmid: Int, name: String) {
    val context = LocalContext.current
    val c = context.container
    val ref = remember(node, type, vmid) { GuestRef(node, GuestType.of(type) ?: GuestType.QEMU, vmid) }
    val active by c.sessions.active.collectAsStateWithLifecycle()
    val access by c.sessions.access.collectAsStateWithLifecycle()
    val server = active?.server
    val client = active?.client

    var options by remember { mutableStateOf<List<GuestOption>?>(null) }
    var firewall by remember { mutableStateOf<FirewallOptions?>(null) }
    var rules by remember { mutableStateOf<List<FirewallRule>?>(null) }
    var error by remember { mutableStateOf<PveError?>(null) }
    var firewallError by remember { mutableStateOf<PveError?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(ref, server?.id, reload) {
        val cl = client ?: return@LaunchedEffect
        try {
            options = ResourcesApi.resources(cl, ref).second
            error = null
        } catch (e: PveError) {
            error = e
        }
        try {
            firewall = ResourcesApi.firewallOptions(cl, ref)
            rules = ResourcesApi.firewallRules(cl, ref)
            firewallError = null
        } catch (e: PveError) {
            firewallError = e
        }
        refreshing = false
    }

    val title = listOf(vmid.toString(), name).filter { it.isNotBlank() }.joinToString("  ")
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.options_title, title))
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                if (access.error != null) ConnectionBanner()
                val opts = options
                val err = error
                when {
                    opts == null && err == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    opts == null && err != null -> Text(errorMessage(context, err, server?.host.orEmpty(), server?.auth), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(Gutter))
                    opts != null -> Group(stringResource(R.string.options_group), footer = stringResource(R.string.options_read_only)) {
                        if (opts.isEmpty()) GroupRow(stringResource(R.string.options_none))
                        opts.forEach { o -> GroupRow(optionLabel(o.key), optionValue(o)) }
                    }
                }

                val fw = firewall
                val fwErr = firewallError
                if (fwErr != null && fw == null) {
                    Group(stringResource(R.string.firewall_group)) {
                        GroupRow(stringResource(R.string.firewall_unavailable), errorMessage(context, fwErr, server?.host.orEmpty(), server?.auth))
                    }
                } else if (fw != null) {
                    Group(stringResource(R.string.firewall_group)) {
                        GroupRow(stringResource(R.string.firewall_enabled), stringResource(if (fw.enabled) R.string.yes else R.string.no))
                        GroupRow(stringResource(R.string.firewall_policy_in), fw.policyIn ?: stringResource(R.string.firewall_policy_default))
                        GroupRow(stringResource(R.string.firewall_policy_out), fw.policyOut ?: stringResource(R.string.firewall_policy_default))
                        fw.other.forEach { o -> GroupRow(o.key, o.value) }
                    }
                    val list = rules.orEmpty()
                    Group(stringResource(R.string.firewall_rules), footer = if (fw.enabled) null else stringResource(R.string.firewall_off_hint)) {
                        if (list.isEmpty()) GroupRow(stringResource(R.string.firewall_no_rules))
                        list.forEach { r -> RuleRow(r) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(r: FirewallRule) {
    val head = buildString {
        append(r.type.uppercase())
        append("  ")
        append(r.action)
        r.macro?.let { append("  ").append(it) }
        r.proto?.let { append("  ").append(it) }
        r.dport?.let { append(" :").append(it) }
    }
    val details = listOfNotNull(
        r.source?.let { stringResource(R.string.firewall_from, it) },
        r.dest?.let { stringResource(R.string.firewall_to, it) },
        r.sport?.let { stringResource(R.string.firewall_sport, it) },
        r.iface?.let { stringResource(R.string.firewall_iface, it) },
        r.comment,
        if (!r.enabled) stringResource(R.string.firewall_rule_off) else null,
    ).joinToString("\n")
    GroupRow(
        title = "#${r.pos}  $head",
        subtitle = details,
        titleColor = if (r.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun optionLabel(key: String): String = when (key) {
    "name" -> stringResource(R.string.opt_name)
    "hostname" -> stringResource(R.string.row_hostname)
    "ostype" -> stringResource(R.string.opt_ostype)
    "onboot" -> stringResource(R.string.row_onboot)
    "startup" -> stringResource(R.string.row_start_order)
    "protection" -> stringResource(R.string.opt_protection)
    "boot" -> stringResource(R.string.opt_boot)
    "bios" -> stringResource(R.string.opt_bios)
    "machine" -> stringResource(R.string.opt_machine)
    "agent" -> stringResource(R.string.opt_agent)
    "unprivileged" -> stringResource(R.string.opt_unprivileged)
    "features" -> stringResource(R.string.opt_features)
    "arch" -> stringResource(R.string.opt_arch)
    "nameserver" -> stringResource(R.string.opt_nameserver)
    "searchdomain" -> stringResource(R.string.opt_searchdomain)
    else -> key
}

@Composable
private fun optionValue(o: GuestOption): String = when (o.key) {
    "onboot", "protection", "unprivileged", "acpi", "kvm", "tablet", "localtime", "numa", "freeze" ->
        stringResource(if (o.value == "1") R.string.yes else R.string.no)
    else -> o.value
}
