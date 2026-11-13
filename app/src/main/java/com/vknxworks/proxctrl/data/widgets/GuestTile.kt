package com.vknxworks.proxctrl.data.widgets

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.core.content.edit
import com.vknxworks.proxctrl.data.api.PveJson
import com.vknxworks.proxctrl.data.guests.ClusterResource
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.PowerAction
import com.vknxworks.proxctrl.ui.widgets.GuestTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The guest the Quick Settings guest tile controls: which server (by its saved ID, so the tile keeps
 * working on that server whichever one is active in the app), and which VM or container. The name is
 * only for showing it before the server has answered. No secrets.
 */
@Serializable
data class GuestTileTarget(
    val serverId: String,
    val type: String,
    val vmid: Int,
    val name: String = "",
) {
    val guestType: GuestType get() = GuestType.of(type) ?: GuestType.QEMU
}

/** Where the tile's guest is kept: a small private preferences file (never backed up, see data_extraction_rules). */
class GuestTileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("guest_tile", Context.MODE_PRIVATE)
    private val _target = MutableStateFlow(read())

    /** The tile's guest, or null when none has been picked. */
    val target: StateFlow<GuestTileTarget?> = _target.asStateFlow()

    private fun read(): GuestTileTarget? = runCatching {
        prefs.getString(KEY, null)?.let { PveJson.decodeFromString(GuestTileTarget.serializer(), it) }
    }.getOrNull()

    fun set(target: GuestTileTarget?) {
        prefs.edit {
            if (target == null) remove(KEY) else putString(KEY, PveJson.encodeToString(GuestTileTarget.serializer(), target))
        }
        _target.value = target
        // A tile that's showing redraws with the new guest.
        runCatching { TileService.requestListeningState(context, ComponentName(context, GuestTileService::class.java)) }
    }

    /** A server was removed: a tile set up for it forgets its guest. */
    fun forgetServer(serverId: String) {
        if (_target.value?.serverId == serverId) set(null)
    }

    private companion object {
        const val KEY = "target"
    }
}

/** What the guest tile shows and does, kept free of Android code so it can be unit-tested. */
object GuestTileLogic {

    /** GET /cluster/resources?type=vm: the whole cluster in one call, so a guest that moved to another node is still found. */
    const val RESOURCES_PATH = "/cluster/resources"
    val RESOURCES_QUERY = mapOf("type" to "vm")

    /** The tile's guest in a /cluster/resources answer, or null when it isn't there (removed, or not visible to this sign-in). */
    fun find(resources: JsonElement?, target: GuestTileTarget): Guest? =
        guests(resources).firstOrNull { it.vmid == target.vmid && it.type == target.guestType }

    /** Every VM and container in a /cluster/resources answer, by ID; entries that can't be read are skipped. */
    fun guests(resources: JsonElement?): List<Guest> {
        val list = resources as? JsonArray ?: return emptyList()
        return list.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { runCatching { PveJson.decodeFromJsonElement(ClusterResource.serializer(), it) }.getOrNull() }
            .mapNotNull { Guest.from(it) }
            .sortedBy { it.vmid }
            .toList()
    }

    /** The guests a tile can be set up for: not templates (they can't be started). */
    fun pickable(resources: JsonElement?): List<Guest> = guests(resources).filter { !it.isTemplate }

    /**
     * What a tap does: start a stopped guest, shut down a running one (gracefully, after a
     * confirmation), or nothing here (paused, locked by a backup or migration, a template) so the
     * tile opens the app instead.
     */
    fun tapAction(guest: Guest?): PowerAction? = when {
        guest == null -> null
        PowerAction.START.appliesTo(guest) -> PowerAction.START
        PowerAction.SHUTDOWN.appliesTo(guest) -> PowerAction.SHUTDOWN
        else -> null
    }

    /** POST path for [action] on [guest] (no form parameters for start and shutdown). */
    fun powerPath(guest: Guest, action: PowerAction): String = "/nodes/${guest.node}/${guest.type.api}/${guest.vmid}/status/${action.api}"
}
