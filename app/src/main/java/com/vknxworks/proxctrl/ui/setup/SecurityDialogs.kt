package com.vknxworks.proxctrl.ui.setup

import com.vknxworks.proxctrl.ui.components.CtrlDialog
import com.vknxworks.proxctrl.ui.components.CtrlTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.server.Tls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** What the user is asked to trust: the server's certificate as fetched (nothing was sent to it). */
data class CertInfo(val host: String, val subject: String, val fingerprint: String)

/** Fetches the certificate [url] presents, without trusting it or sending anything to it. */
suspend fun fetchCertInfo(url: HttpUrl): CertInfo = withContext(Dispatchers.IO) {
    val leaf = Tls.fetchLeaf(url.host, url.port)
    CertInfo(url.host, leaf.subjectX500Principal.name, Tls.fingerprint(leaf))
}

/** Asks the user to confirm a self-signed certificate by its SHA-256 fingerprint (trust on first use). */
@Composable
fun CertTrustDialog(cert: CertInfo, changed: Boolean, onTrust: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    CtrlDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.VerifiedUser, null) },
        title = { Text(stringResource(if (changed) R.string.cert_changed_title else R.string.cert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (changed) {
                    Text(stringResource(R.string.cert_changed_warning, cert.host), color = cs.error)
                } else {
                    Text(stringResource(R.string.cert_intro, cert.host))
                }
                Text(stringResource(R.string.cert_check))
                Text(
                    cert.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHighest).padding(10.dp),
                )
                if (cert.subject.isNotBlank()) {
                    Text(stringResource(R.string.cert_issued_to, cert.subject), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Text(stringResource(R.string.cert_pin_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onTrust) { Text(stringResource(R.string.cert_trust)) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Asks for the second factor: a TOTP code from an authenticator app, or a recovery key.
 * [onSubmit] gets the response in the form Proxmox expects ("totp:…" / "recovery:…").
 */
@Composable
fun TfaDialog(error: String?, busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var recovery by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    val canSubmit = !busy && if (recovery) value.isNotBlank() else value.count { it.isDigit() } == 6
    fun submit() {
        if (canSubmit) onSubmit(if (recovery) com.vknxworks.proxctrl.data.api.Auth.recovery(value) else com.vknxworks.proxctrl.data.api.Auth.totp(value))
    }
    CtrlDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Password, null) },
        title = { Text(stringResource(R.string.tfa_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(if (recovery) R.string.tfa_recovery_body else R.string.tfa_body))
                CtrlTextField(
                    value = value,
                    onValueChange = { v -> value = if (recovery) v else v.filter { it.isDigit() }.take(6) },
                    label = { Text(stringResource(if (recovery) R.string.tfa_recovery else R.string.tfa_code)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error) }
                    } else {
                        null
                    },
                    // Password keyboards: the code or recovery key is never suggested, corrected or learned.
                    keyboardOptions = KeyboardOptions(keyboardType = if (recovery) KeyboardType.Password else KeyboardType.NumberPassword, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { recovery = !recovery; value = "" }) {
                    Text(stringResource(if (recovery) R.string.tfa_use_code else R.string.tfa_use_recovery))
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = canSubmit) { Text(stringResource(R.string.verify)) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
