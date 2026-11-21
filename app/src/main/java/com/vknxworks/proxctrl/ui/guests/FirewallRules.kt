package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.guests.FirewallApi
import com.vknxworks.proxctrl.data.guests.FirewallRule
import com.vknxworks.proxctrl.data.guests.RuleDraft
import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import com.vknxworks.proxctrl.ui.components.GroupSwitch

/** What can be done with a rule from its row. */
internal enum class RuleAction { EDIT, MOVE_UP, MOVE_DOWN, DELETE }

/**
 * Adds a firewall rule ([original] == null) or edits one. Proxmox checks the values (addresses,
 * ports, macro names) and its answer is shown if it refuses the rule.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RuleEditorDialog(
    original: FirewallRule?,
    firewallOn: Boolean,
    onDismiss: () -> Unit,
    onSave: (RuleDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(original?.let(RuleDraft::from) ?: RuleDraft()) }
    val noSuggest = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii)
    val usesMacro = draft.macro.isNotBlank()
    CtrlDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (original == null) R.string.fw_rule_add else R.string.fw_rule_edit_title, original?.pos ?: 0)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.fw_rule_direction), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FirewallApi.TYPES.forEach { t ->
                        FilterChip(
                            selected = draft.type == t,
                            onClick = { draft = draft.copy(type = t) },
                            label = { Text(stringResource(if (t == "in") R.string.fw_rule_in else R.string.fw_rule_out)) },
                        )
                    }
                }
                Text(stringResource(R.string.fw_rule_action), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FirewallApi.ACTIONS.forEach { a ->
                        FilterChip(selected = draft.action == a, onClick = { draft = draft.copy(action = a) }, label = { Text(a) })
                    }
                }
                CtrlTextField(
                    value = draft.macro,
                    onValueChange = { draft = draft.copy(macro = it.trim()) },
                    label = { Text(stringResource(R.string.fw_rule_macro)) },
                    supportingText = { Text(stringResource(R.string.fw_rule_macro_hint)) },
                    singleLine = true,
                    keyboardOptions = noSuggest,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!usesMacro) {
                    CtrlTextField(
                        value = draft.proto,
                        onValueChange = { draft = draft.copy(proto = it.trim()) },
                        label = { Text(stringResource(R.string.fw_rule_proto)) },
                        supportingText = { Text(stringResource(R.string.fw_rule_proto_hint)) },
                        singleLine = true,
                        keyboardOptions = noSuggest,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draft.proto.isNotBlank()) {
                        CtrlTextField(
                            value = draft.dport,
                            onValueChange = { draft = draft.copy(dport = it.trim()) },
                            label = { Text(stringResource(R.string.fw_rule_dport)) },
                            supportingText = { Text(stringResource(R.string.fw_rule_port_hint)) },
                            singleLine = true,
                            keyboardOptions = noSuggest,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CtrlTextField(
                            value = draft.sport,
                            onValueChange = { draft = draft.copy(sport = it.trim()) },
                            label = { Text(stringResource(R.string.fw_rule_sport)) },
                            singleLine = true,
                            keyboardOptions = noSuggest,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                CtrlTextField(
                    value = draft.source,
                    onValueChange = { draft = draft.copy(source = it.trim()) },
                    label = { Text(stringResource(R.string.fw_rule_source)) },
                    supportingText = { Text(stringResource(R.string.fw_rule_addr_hint)) },
                    singleLine = true,
                    keyboardOptions = noSuggest,
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlTextField(
                    value = draft.dest,
                    onValueChange = { draft = draft.copy(dest = it.trim()) },
                    label = { Text(stringResource(R.string.fw_rule_dest)) },
                    singleLine = true,
                    keyboardOptions = noSuggest,
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlTextField(
                    value = draft.iface,
                    onValueChange = { draft = draft.copy(iface = it.trim()) },
                    label = { Text(stringResource(R.string.fw_rule_iface)) },
                    supportingText = { Text(stringResource(R.string.fw_rule_iface_hint)) },
                    singleLine = true,
                    keyboardOptions = noSuggest,
                    modifier = Modifier.fillMaxWidth(),
                )
                CtrlTextField(
                    value = draft.comment,
                    onValueChange = { draft = draft.copy(comment = it) },
                    label = { Text(stringResource(R.string.fw_rule_comment)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                GroupSwitch(
                    title = stringResource(R.string.fw_rule_enabled),
                    checked = draft.enabled,
                    onChange = { draft = draft.copy(enabled = it) },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(if (firewallOn) R.string.fw_rule_applies_now else R.string.fw_rule_applies_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
