package com.vknxworks.proxctrl.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.container
import com.vknxworks.proxctrl.data.api.Auth
import com.vknxworks.proxctrl.data.api.ClusterStatusEntry
import com.vknxworks.proxctrl.data.api.NodeEntry
import com.vknxworks.proxctrl.data.api.Permissions
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.api.PveVersion
import com.vknxworks.proxctrl.data.api.Realm
import com.vknxworks.proxctrl.data.api.TicketResponse
import com.vknxworks.proxctrl.data.api.httpFor
import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.data.server.ServerAddress
import com.vknxworks.proxctrl.data.server.ServerSecrets
import com.vknxworks.proxctrl.ui.components.ChoiceSheet
import com.vknxworks.proxctrl.ui.components.Group
import com.vknxworks.proxctrl.ui.components.GroupRow
import com.vknxworks.proxctrl.ui.components.GroupSwitch
import com.vknxworks.proxctrl.ui.components.PillButton
import com.vknxworks.proxctrl.ui.components.ScreenTopBar
import com.vknxworks.proxctrl.ui.components.SecureScreen
import com.vknxworks.proxctrl.ui.components.errorMessage
import com.vknxworks.proxctrl.ui.theme.Gutter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl

/** Everything learned from a successful connection, shown before the server is saved. */
private class Connected(
    val server: Server,
    val secrets: ServerSecrets,
    val version: PveVersion,
    val permissions: Permissions,
)

private class CertPrompt(val cert: CertInfo, val changed: Boolean)

private class TfaPrompt(val username: String, val challenge: String)

/**
 * Adds a Proxmox server, or changes the address or sign-in of an existing one ([editId]).
 *
 * 1. The address is checked with the realm list, which needs no login: that shows whether it is a
 *    Proxmox API and whether its certificate is trusted. An untrusted certificate is shown by its
 *    SHA-256 fingerprint to confirm and pin (nothing has been sent to the server at that point).
 * 2. Then it signs in with the API token, or with username and password (plus a two-factor code).
 * 3. The version and effective permissions are read and shown, and the server is saved on Continue.
 */
@Composable
fun SetupScreen(editId: String? = null, onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val existing = remember(editId) { editId?.let { id -> c.sessions.servers.value.firstOrNull { it.id == id } } }
    val existingSecrets = remember(editId) { editId?.let { c.serverStore.secrets(it) } }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    // The full saved address, with https://: without it, a reverse-proxy address on port 443 would be
    // read back as a node on port 8006.
    var address by remember { mutableStateOf(existing?.url.orEmpty()) }
    var method by remember { mutableStateOf(existing?.auth ?: AuthMethod.TOKEN) }
    var tokenId by remember { mutableStateOf(existing?.tokenId.orEmpty()) }
    var tokenSecret by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(existing?.username?.substringBeforeLast('@').orEmpty()) }
    var realm by remember { mutableStateOf(existing?.username?.substringAfterLast('@', "")?.ifBlank { null } ?: "pam") }
    var password by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var realms by remember { mutableStateOf<List<Realm>>(emptyList()) }
    var realmSheet by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var certPrompt by remember { mutableStateOf<CertPrompt?>(null) }
    var tfaPrompt by remember { mutableStateOf<TfaPrompt?>(null) }
    var tfaError by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf<Connected?>(null) }
    var readOnly by remember { mutableStateOf(existing?.readOnly ?: false) }

    // A certificate the user trusted in this session, or the one already pinned for this server.
    var pinnedHost by remember { mutableStateOf(existing?.host) }
    var pin by remember { mutableStateOf(existing?.certPin.orEmpty()) }

    val resolved: HttpUrl? = ServerAddress.normalize(address)
    val addressError = when {
        address.isBlank() -> null
        ServerAddress.isPlainHttp(address) -> stringResource(R.string.address_http)
        resolved == null -> stringResource(R.string.address_invalid)
        else -> null
    }
    val tokenIdValid = tokenId.trim().let { it.contains('@') && it.contains('!') && !it.endsWith('!') }
    // The saved token secret is only ever sent to the address and token it was saved for. Changing
    // either needs the secret typed again, so an edited address can't receive the old secret.
    val canReuseSecret = existing != null && existing.auth == AuthMethod.TOKEN &&
        existingSecrets?.tokenSecret?.isNotBlank() == true &&
        resolved != null && ServerAddress.base(resolved) == existing.url && tokenId.trim() == existing.tokenId
    val canConnect = !busy && resolved != null && when (method) {
        AuthMethod.TOKEN -> tokenIdValid && (tokenSecret.isNotBlank() || canReuseSecret)
        AuthMethod.TICKET -> username.isNotBlank() && password.isNotEmpty()
    }

    fun pinFor(url: HttpUrl): String = if (pinnedHost.equals(url.host, ignoreCase = true)) pin else ""

    fun fullUsername(): String = username.trim().let { if (it.contains('@')) it else "$it@$realm" }

    // Load the realm list quietly once the address looks complete, for the realm picker.
    LaunchedEffect(resolved, method) {
        val url = resolved ?: return@LaunchedEffect
        if (method != AuthMethod.TICKET) return@LaunchedEffect
        delay(600)
        runCatching { Auth.realms(httpFor(c.http, url.host, pinFor(url)), ServerAddress.base(url)) }
            .onSuccess { list -> realms = list }
    }

    /** Reads version, permissions and the node/cluster name, and shows the result. */
    suspend fun finish(draft: Server, secrets: ServerSecrets) {
        val client = PveClient(draft, c.http, secrets)
        val version = client.get("/version", PveVersion.serializer())
        val perms = Permissions.parse(runCatching { client.get("/access/permissions") }.getOrNull())
        val clusterName = runCatching { client.get("/cluster/status", ListSerializer(ClusterStatusEntry.serializer())) }
            .getOrNull()?.firstOrNull { it.type == "cluster" }?.name
        val nodeName = runCatching { client.get("/nodes", ListSerializer(NodeEntry.serializer())) }
            .getOrNull()?.singleOrNull()?.node
        val finalName = name.trim().ifBlank { clusterName ?: nodeName ?: draft.host }
        // Keep the user's read-only choice when editing; a token that can't change anything starts read-only.
        readOnly = existing?.readOnly ?: perms.isReadOnly
        connected = Connected(
            server = draft.copy(name = finalName, pveVersion = version.version),
            secrets = client.secrets,
            version = version,
            permissions = perms,
        )
    }

    fun handleTicket(url: HttpUrl, user: String, res: TicketResponse, viaTfa: Boolean) {
        val base = ServerAddress.base(url)
        val draft = Server(
            id = existing?.id.orEmpty(), name = name.trim(), url = base, auth = AuthMethod.TICKET,
            username = res.username.ifBlank { user }, certPin = pinFor(url), usesTfa = viaTfa,
        )
        // The password is only kept when it can renew the session on its own (no second factor).
        val secrets = ServerSecrets(
            password = if (viaTfa) "" else password,
            ticket = res.ticket,
            csrf = res.csrf,
            ticketIssuedAt = System.currentTimeMillis(),
        )
        scope.launch {
            try {
                finish(draft, secrets)
            } catch (e: Exception) {
                error = errorMessage(context, e, url.host, AuthMethod.TICKET)
            } finally {
                busy = false
            }
        }
    }

    fun connect() {
        val url = resolved ?: return
        val base = ServerAddress.base(url)
        busy = true
        error = null
        scope.launch {
            val http = httpFor(c.http, url.host, pinFor(url))
            // 1. Reachable, a Proxmox API, and a trusted certificate?
            try {
                realms = Auth.realms(http, base)
            } catch (e: PveError.UntrustedCertificate) {
                askToTrust(url, changed = false, onCert = { certPrompt = it }, onFail = { error = it })
                busy = false
                return@launch
            } catch (e: PveError.PinMismatch) {
                askToTrust(url, changed = true, onCert = { certPrompt = it }, onFail = { error = it })
                busy = false
                return@launch
            } catch (e: Exception) {
                error = errorMessage(context, e, url.host)
                busy = false
                return@launch
            }
            // 2. Sign in.
            when (method) {
                AuthMethod.TOKEN -> {
                    val secret = tokenSecret.trim().ifBlank { if (canReuseSecret) existingSecrets?.tokenSecret.orEmpty() else "" }
                    if (secret.isEmpty()) {
                        error = context.getString(R.string.token_secret_reenter)
                        busy = false
                        return@launch
                    }
                    val draft = Server(
                        id = existing?.id.orEmpty(), name = name.trim(), url = base, auth = AuthMethod.TOKEN,
                        tokenId = tokenId.trim(), certPin = pinFor(url),
                    )
                    try {
                        finish(draft, ServerSecrets(tokenSecret = secret))
                    } catch (e: Exception) {
                        error = errorMessage(context, e, url.host, AuthMethod.TOKEN)
                    }
                    busy = false
                }
                AuthMethod.TICKET -> {
                    val user = fullUsername()
                    try {
                        val res = Auth.requestTicket(http, base, user, password)
                        if (res.needTfa != 0) {
                            tfaError = null
                            tfaPrompt = TfaPrompt(res.username.ifBlank { user }, res.ticket)
                            busy = false
                        } else {
                            handleTicket(url, user, res, viaTfa = false)
                        }
                    } catch (e: Exception) {
                        error = errorMessage(context, e, url.host, AuthMethod.TICKET)
                        busy = false
                    }
                }
            }
        }
    }

    fun answerTfa(response: String) {
        val url = resolved ?: return
        val prompt = tfaPrompt ?: return
        busy = true
        scope.launch {
            try {
                val res = Auth.finishTfa(httpFor(c.http, url.host, pinFor(url)), ServerAddress.base(url), prompt.username, prompt.challenge, response)
                if (res.needTfa != 0 || res.ticket.isEmpty()) {
                    tfaError = context.getString(R.string.error_bad_tfa)
                    busy = false
                } else {
                    tfaPrompt = null
                    handleTicket(url, prompt.username, res, viaTfa = true)
                }
            } catch (e: PveError.Unauthorized) {
                tfaError = context.getString(R.string.error_bad_tfa)
                busy = false
            } catch (e: Exception) {
                tfaPrompt = null
                error = errorMessage(context, e, url.host, AuthMethod.TICKET)
                busy = false
            }
        }
    }

    fun save() {
        val result = connected ?: return
        c.sessions.add(result.server.copy(readOnly = readOnly), result.secrets)
        onDone()
    }

    // Token secrets, passwords and two-factor codes are typed here: never in screenshots or Recents.
    SecureScreen()

    val result = connected
    Column(Modifier.fillMaxSize()) {
        if (onCancel != null) {
            ScreenTopBar(stringResource(if (editId != null) R.string.edit_server_title else R.string.add_server))
        } else {
            Spacer(Modifier.statusBarsPadding())
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            if (result == null) {
                if (onCancel == null) {
                    Column(Modifier.padding(horizontal = Gutter, vertical = 16.dp)) {
                        Icon(Icons.Filled.Dns, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.trim() },
                        label = { Text(stringResource(R.string.field_address)) },
                        placeholder = { Text(stringResource(R.string.field_address_hint)) },
                        singleLine = true,
                        isError = addressError != null,
                        supportingText = {
                            when {
                                addressError != null -> Text(addressError)
                                resolved != null -> Text(stringResource(R.string.address_resolves, ServerAddress.base(resolved)))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.field_name)) },
                        placeholder = { Text(stringResource(R.string.field_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.auth_method), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(AuthMethod.TOKEN to R.string.auth_token, AuthMethod.TICKET to R.string.auth_password).forEachIndexed { i, (m, label) ->
                            SegmentedButton(
                                selected = method == m,
                                onClick = { method = m; error = null },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    Text(
                        stringResource(if (method == AuthMethod.TOKEN) R.string.auth_token_note else R.string.auth_password_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val secretToggle: @Composable () -> Unit = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(
                                if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                stringResource(if (showSecret) R.string.hide_secret else R.string.show_secret),
                            )
                        }
                    }
                    val secretTransform = if (showSecret) VisualTransformation.None else PasswordVisualTransformation()

                    when (method) {
                        AuthMethod.TOKEN -> {
                            OutlinedTextField(
                                value = tokenId,
                                onValueChange = { tokenId = it.trim() },
                                label = { Text(stringResource(R.string.field_token_id)) },
                                placeholder = { Text(stringResource(R.string.field_token_id_hint)) },
                                singleLine = true,
                                isError = tokenId.isNotBlank() && !tokenIdValid,
                                supportingText = if (tokenId.isNotBlank() && !tokenIdValid) {
                                    { Text(stringResource(R.string.token_id_invalid)) }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = tokenSecret,
                                onValueChange = { tokenSecret = it },
                                label = { Text(stringResource(R.string.field_token_secret)) },
                                placeholder = if (canReuseSecret) {
                                    { Text("••••••••") }
                                } else {
                                    null
                                },
                                supportingText = if (existing?.auth == AuthMethod.TOKEN && !canReuseSecret && tokenSecret.isBlank()) {
                                    { Text(stringResource(R.string.token_secret_reenter)) }
                                } else {
                                    null
                                },
                                singleLine = true,
                                visualTransformation = secretTransform,
                                trailingIcon = secretToggle,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        AuthMethod.TICKET -> {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it.trim() },
                                label = { Text(stringResource(R.string.field_username)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!username.contains('@')) {
                                val realmLabel = realms.firstOrNull { it.realm == realm }?.let { "${it.label} (${it.realm})" }
                                    ?: when (realm) {
                                        "pam" -> stringResource(R.string.realm_pam)
                                        "pve" -> stringResource(R.string.realm_pve)
                                        else -> realm
                                    }
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    GroupRow(stringResource(R.string.field_realm), realmLabel, icon = Icons.Filled.Lock, showChevron = true, onClick = { realmSheet = true })
                                }
                            }
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.field_password)) },
                                singleLine = true,
                                visualTransformation = secretTransform,
                                trailingIcon = secretToggle,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    PillButton(
                        text = stringResource(if (busy) R.string.connecting else R.string.connect),
                        onClick = ::connect,
                        enabled = canConnect,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                ConnectedSummary(result, readOnly, onReadOnly = { readOnly = it })
                PillButton(
                    text = stringResource(if (editId != null) R.string.save_changes else R.string.continue_label),
                    onClick = ::save,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp),
                )
            }
        }
    }

    certPrompt?.let { prompt ->
        CertTrustDialog(
            cert = prompt.cert,
            changed = prompt.changed,
            onTrust = {
                certPrompt = null
                pinnedHost = prompt.cert.host
                pin = prompt.cert.fingerprint
                connect()
            },
            onCancel = { certPrompt = null },
        )
    }
    tfaPrompt?.let {
        TfaDialog(
            error = tfaError,
            busy = busy,
            onSubmit = ::answerTfa,
            onCancel = { tfaPrompt = null; busy = false },
        )
    }
    if (realmSheet) {
        val options = (realms.map { it.realm to "${it.label} (${it.realm})" })
            .ifEmpty { listOf("pam" to stringResource(R.string.realm_pam), "pve" to stringResource(R.string.realm_pve)) }
        ChoiceSheet(stringResource(R.string.field_realm), options, realm, onDismiss = { realmSheet = false }) {
            realm = it
            realmSheet = false
        }
    }
}

/** Reads the certificate the server presents and asks the user to confirm it. */
private suspend fun askToTrust(url: HttpUrl, changed: Boolean, onCert: (CertPrompt) -> Unit, onFail: (String) -> Unit) {
    try {
        onCert(CertPrompt(fetchCertInfo(url), changed))
    } catch (e: Exception) {
        onFail(e.message ?: url.host)
    }
}

@Composable
private fun ConnectedSummary(result: Connected, readOnly: Boolean, onReadOnly: (Boolean) -> Unit) {
    val server = result.server
    Column(Modifier.padding(horizontal = Gutter, vertical = 16.dp)) {
        Icon(Icons.Filled.Dns, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.connected_to, server.name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(
                stringResource(R.string.pve_version, result.version.version.ifBlank { "?" }),
                stringResource(R.string.signed_in_as, server.authId),
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Group(footer = if (result.permissions.isReadOnly && readOnly) stringResource(R.string.read_only_suggest) else null) {
        GroupSwitch(
            stringResource(R.string.read_only_mode),
            stringResource(R.string.read_only_mode_subtitle),
            checked = readOnly,
            onChange = onReadOnly,
        )
    }
    AccessSummary(result.permissions, result.version.major)
}
