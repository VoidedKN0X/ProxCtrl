package com.vknxworks.proxctrl.data.server

import com.vknxworks.proxctrl.data.api.Permissions
import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.api.PveVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID

/** The active server and its API client. */
class ActiveServer(val server: Server, val client: PveClient)

/** What the signed-in user or token may do, and the Proxmox version, for the active server. */
data class Access(
    val permissions: Permissions = Permissions.NONE,
    val version: PveVersion? = null,
    val loading: Boolean = true,
    val error: PveError? = null,
)

/**
 * Keeps the list of servers, which one is active, an API client for it and what it's allowed to do.
 * Switching servers rebuilds the client; any change to a server (certificate, read-only) does too.
 */
class Sessions(
    private val store: ServerStore,
    private val baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
) {
    val servers: StateFlow<List<Server>> = store.servers

    /** Bumped when secrets change outside the client (signing in again), so the client is rebuilt. */
    private val revision = MutableStateFlow(0)

    val active: StateFlow<ActiveServer?> = combine(store.servers, store.activeId, revision) { list, id, rev -> list.firstOrNull { it.id == id } to rev }
        .distinctUntilChanged()
        .map { (server, _) -> server?.let { ActiveServer(it, clientFor(it)) } }
        .stateIn(scope, SharingStarted.Eagerly, store.servers.value.firstOrNull { it.id == store.activeId.value }?.let { ActiveServer(it, clientFor(it)) })

    private val _access = MutableStateFlow(Access())
    val access: StateFlow<Access> = _access.asStateFlow()
    private var accessJob: Job? = null

    init {
        scope.launch {
            active.collect { refreshAccess() }
        }
    }

    fun clientFor(server: Server): PveClient =
        PveClient(server, baseHttp, store.secrets(server.id)) { updated -> store.saveSecrets(server.id, updated) }

    /** Reloads permissions and version of the active server. */
    fun refreshAccess() {
        accessJob?.cancel()
        val current = active.value
        if (current == null) {
            _access.value = Access(loading = false)
            return
        }
        _access.value = _access.value.copy(loading = true, error = null)
        accessJob = scope.launch {
            try {
                val version = current.client.get("/version", PveVersion.serializer())
                val perms = Permissions.parse(current.client.get("/access/permissions"))
                if (version.version.isNotBlank() && version.version != current.server.pveVersion) {
                    store.update(current.server.id) { it.copy(pveVersion = version.version) }
                }
                _access.value = Access(perms, version, loading = false)
            } catch (e: PveError) {
                _access.value = _access.value.copy(loading = false, error = e)
            }
        }
    }

    /** True when the active server allows [priv] on [path] and isn't in read-only mode. */
    fun canWrite(path: String, priv: String): Boolean {
        val server = active.value?.server ?: return false
        return !server.readOnly && access.value.permissions.has(path, priv)
    }

    fun add(server: Server, secrets: ServerSecrets): Server {
        val saved = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
        store.saveSecrets(saved.id, secrets)
        store.save(saved)
        store.setActive(saved.id)
        // Editing a server can leave its settings unchanged but its secrets new: rebuild the client either way.
        revision.value++
        return saved
    }

    fun switchTo(id: String) = store.setActive(id)

    fun remove(id: String) = store.remove(id)

    fun setReadOnly(id: String, readOnly: Boolean) = store.update(id) { it.copy(readOnly = readOnly) }

    /** Saves a new password session for a server whose ticket expired (after signing in again). */
    fun updateSecrets(id: String, secrets: ServerSecrets) {
        store.saveSecrets(id, secrets)
        // Rebuild the client so it picks up the new ticket; that also reloads permissions.
        revision.value++
    }

    fun updatePin(id: String, pin: String) = store.update(id) { it.copy(certPin = pin) }
}
