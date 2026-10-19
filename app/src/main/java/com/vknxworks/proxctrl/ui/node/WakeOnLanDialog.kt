package com.vknxworks.proxctrl.ui.node

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.vknxworks.proxctrl.data.node.WakeOnLan
import com.vknxworks.proxctrl.data.node.WolConfig

/** Sets up Wake-on-LAN for the node: its MAC address, and optionally where the packet goes. */
@Composable
internal fun WakeOnLanDialog(
    initial: WolConfig?,
    onDismiss: () -> Unit,
    onSave: (WolConfig?) -> Unit,
) {
    var mac by remember { mutableStateOf(initial?.mac.orEmpty()) }
    var broadcast by remember { mutableStateOf(initial?.broadcast ?: WakeOnLan.DEFAULT_BROADCAST) }
    var port by remember { mutableStateOf((initial?.port ?: WakeOnLan.DEFAULT_PORT).toString()) }
    val normalized = WakeOnLan.normalizeMac(mac)
    val portNumber = port.trim().toIntOrNull()
    val portOk = portNumber != null && WakeOnLan.isValidPort(portNumber)
    val broadcastOk = broadcast.isNotBlank() && broadcast.trim().none { it.isWhitespace() }
    val valid = normalized != null && portOk && broadcastOk

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.PowerSettingsNew, null) },
        title = { Text(stringResource(R.string.node_wol_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.node_wol_setup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.node_wol_mac)) },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    isError = mac.isNotBlank() && normalized == null,
                    supportingText = { Text(stringResource(if (mac.isNotBlank() && normalized == null) R.string.node_wol_mac_invalid else R.string.node_wol_mac_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    OutlinedTextField(
                        value = broadcast,
                        onValueChange = { broadcast = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.node_wol_broadcast)) },
                        isError = !broadcastOk,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { v -> port = v.filter(Char::isDigit).take(5) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.node_wol_port)) },
                        isError = !portOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                }
                Text(
                    stringResource(R.string.node_wol_broadcast_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (normalized != null && portNumber != null) onSave(WolConfig(normalized, broadcast.trim(), portNumber)) },
                enabled = valid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.node_wol_remove), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
