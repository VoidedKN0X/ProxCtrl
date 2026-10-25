package com.vknxworks.proxctrl.ui.guests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.api.NodeEntry
import com.vknxworks.proxctrl.data.guests.CreateApi
import com.vknxworks.proxctrl.data.guests.MissingPriv
import com.vknxworks.proxctrl.data.guests.ResourcesApi
import com.vknxworks.proxctrl.data.storage.StorageInfo
import com.vknxworks.proxctrl.ui.components.CtrlButton
import com.vknxworks.proxctrl.ui.components.CtrlCard
import com.vknxworks.proxctrl.ui.components.CtrlSecondaryButton
import com.vknxworks.proxctrl.ui.components.CtrlSectionLabel
import com.vknxworks.proxctrl.ui.components.CtrlSwitch
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.formatBytes
import com.vknxworks.proxctrl.ui.theme.CtrlColors
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.serialization.builtins.ListSerializer

/**
 * A wizard page: top bar, "STEP 2 / 8 · NAME", the step's content and Back / Next at the bottom.
 * The system back gesture goes to the previous step, and leaves the wizard only from the first.
 */
@Composable
internal fun WizardScaffold(
    title: String,
    steps: List<String>,
    step: Int,
    onStep: (Int) -> Unit,
    canNext: Boolean,
    busy: Boolean,
    onFinish: () -> Unit,
    finishLabel: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = step > 0 && !busy) { onStep(step - 1) }
    val last = step == steps.lastIndex
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(title)
        CtrlSectionLabel(
            stringResource(R.string.create_step, step + 1, steps.size, steps[step]),
            Modifier.padding(horizontal = Gutter, vertical = 4.dp),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            content()
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                CtrlSecondaryButton(onClick = { onStep(step - 1) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.create_back))
                }
            }
            CtrlButton(
                onClick = { if (last) onFinish() else onStep(step + 1) },
                enabled = canNext && !busy,
                modifier = Modifier.weight(1f),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CtrlColors.Stopped)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (last) finishLabel else stringResource(R.string.create_next))
            }
        }
    }
}

/** A text field across the page with the gutter. */
@Composable
internal fun WizardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: String? = null,
    number: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardType: KeyboardType? = null,
) {
    CtrlTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) { { Text(placeholder) } } else null,
        isError = isError,
        supportingText = if (supporting != null) { { Text(supporting) } } else null,
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType ?: if (number) KeyboardType.Number else KeyboardType.Text,
            autoCorrectEnabled = false,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
    )
}

/** A small heading inside a step. */
@Composable
internal fun WizardLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = Gutter + 4.dp, end = Gutter, top = 12.dp, bottom = 2.dp),
    )
}

/** A short explanation inside a step. */
@Composable
internal fun WizardHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Gutter + 4.dp, end = Gutter, top = 2.dp, bottom = 4.dp),
    )
}

/** One choice out of a few, as chips. */
@Composable
internal fun <T> WizardChips(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, enabled: (T) -> Boolean = { true }) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled(value), label = { Text(label) })
        }
    }
}

/** A switch with a title and an explanation. */
@Composable
internal fun WizardSwitch(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter + 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        CtrlSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Storages as chips with their free space; storages the sign-in can't use are greyed out. */
@Composable
internal fun StorageChips(storages: List<StorageInfo>?, selected: String?, allowed: (String) -> Boolean, onSelect: (String) -> Unit) {
    when {
        storages == null -> WizardHint(stringResource(R.string.create_loading))
        storages.isEmpty() -> WizardHint(stringResource(R.string.create_no_storage), error = true)
        else -> {
            WizardChips(
                options = storages.map { it.id to stringResource(R.string.create_storage_free, it.id, formatBytes(it.avail)) },
                selected = selected,
                onSelect = onSelect,
                enabled = allowed,
            )
            if (storages.any { !allowed(it.id) }) WizardHint(stringResource(R.string.create_storage_not_allowed))
        }
    }
}

/** Which node the guest is made on, when there's more than one. */
@Composable
internal fun NodeChips(nodes: List<String>, node: String, onNode: (String) -> Unit) {
    if (nodes.size <= 1) return
    WizardLabel(stringResource(R.string.create_node))
    WizardChips(nodes.map { it to it }, node, onNode)
}

/** The guest ID field: the next free ID is filled in, and an ID already in use is flagged. */
@Composable
internal fun IdField(client: PveClient?, id: String, onId: (String) -> Unit, onValid: (Boolean) -> Unit) {
    LaunchedEffect(Unit) {
        val cl = client ?: return@LaunchedEffect
        if (id.isBlank()) runCatching { ResourcesApi.nextId(cl) }.getOrNull()?.let { onId(it.toString()) }
    }
    var taken by remember { mutableStateOf(false) }
    val n = id.toIntOrNull()
    LaunchedEffect(n) {
        val cl = client ?: return@LaunchedEffect
        taken = n != null && !ResourcesApi.isIdFree(cl, n)
    }
    val ok = n != null && n in 100..999_999_999 && !taken
    LaunchedEffect(ok) { onValid(ok) }
    WizardField(
        value = id,
        onValueChange = { onId(it.filter(Char::isDigit).take(9)) },
        label = stringResource(R.string.create_id),
        isError = id.isNotEmpty() && !ok,
        supporting = when {
            taken -> stringResource(R.string.clone_id_taken)
            n != null && n < 100 -> stringResource(R.string.create_id_range)
            else -> null
        },
        number = true,
    )
}

/** Online nodes of the server (the only node on a single host). */
internal suspend fun onlineNodes(client: PveClient): List<String> =
    runCatching { client.get("/nodes", ListSerializer(NodeEntry.serializer())) }.getOrDefault(emptyList())
        .filter { it.status == "online" }.map { it.node }.sorted()

/** A summary line on the last step. */
@Composable
internal fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

/** A card listing what the summary shows. */
@Composable
internal fun SummaryCard(content: @Composable ColumnScope.() -> Unit) {
    CtrlCard(Modifier.padding(horizontal = Gutter, vertical = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp), content = content)
}

/** Privileges the sign-in seems to lack, before creating. Proxmox has the last word. */
@Composable
internal fun MissingPrivsCard(missing: List<MissingPriv>) {
    if (missing.isEmpty()) return
    CtrlCard(Modifier.padding(horizontal = Gutter, vertical = 8.dp), borderColor = CtrlColors.Warn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, null, Modifier.size(20.dp), tint = CtrlColors.Warn)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.create_missing_title), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(6.dp))
        missing.forEach { m ->
            Text(
                stringResource(R.string.create_missing_line, m.privs.joinToString(" " + stringResource(R.string.create_or) + " "), m.path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.create_missing_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The error Proxmox gave when creating failed (permission errors name the missing privilege). */
@Composable
internal fun CreateErrorCard(message: String?, forbidden: Boolean) {
    if (message.isNullOrBlank()) return
    CtrlCard(Modifier.padding(horizontal = Gutter, vertical = 8.dp), borderColor = MaterialTheme.colorScheme.error) {
        Text(
            stringResource(if (forbidden) R.string.create_failed_forbidden else R.string.create_failed),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

/** Checks for the inputs both wizards share. */
internal object CreateChecks {
    fun dnsName(s: String): Boolean = s.trim().length in 1..255 && CreateApi.DNS_NAME.matches(s.trim())
    fun vlan(s: String): Boolean = s.isBlank() || (s.toIntOrNull() ?: 0) in 1..4094
    fun bridge(s: String): Boolean = Regex("^[A-Za-z0-9_.-]{1,15}$").matches(s.trim())
    fun ipv4Cidr(s: String): Boolean = CreateApi.IPV4_CIDR.matches(s.trim()) && octetsOk(s.trim().substringBefore('/'))
    fun ipv4(s: String): Boolean = CreateApi.IPV4.matches(s.trim()) && octetsOk(s.trim())
    fun ipv6Cidr(s: String): Boolean = CreateApi.IPV6_CIDR.matches(s.trim())
    fun ipv6(s: String): Boolean = CreateApi.IPV6.matches(s.trim())
    private fun octetsOk(ip: String) = ip.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 }

    fun isForbidden(e: PveError?): Boolean =
        e is PveError.Forbidden || (e is PveError.Server && e.detail?.contains("Permission check failed", ignoreCase = true) == true)
}
