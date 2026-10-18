package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import kotlinx.serialization.json.JsonPrimitive

/**
 * Guest power actions: POST /nodes/{node}/{qemu|lxc}/{vmid}/status/{action}. All need VM.PowerMgmt
 * on /vms/{vmid}. Each returns the UPID of the task Proxmox starts.
 */
enum class PowerAction(val api: String) {
    START("start"),
    SHUTDOWN("shutdown"),
    REBOOT("reboot"),
    STOP("stop"),
    ;

    /** Whether the action makes sense for a guest in its current state. */
    fun appliesTo(g: Guest): Boolean = !g.isTemplate && g.lock.isEmpty() && when (this) {
        START -> g.isStopped
        SHUTDOWN, REBOOT -> g.isRunning
        STOP -> g.isRunning || g.status == "paused"
    }

    companion object {
        const val PRIV = "VM.PowerMgmt"
    }
}

/** Result of running one action on one guest. */
data class PowerResult(val guest: Guest, val upid: String?, val error: PveError?)

suspend fun PveClient.power(guest: Guest, action: PowerAction): PowerResult = try {
    val data = post("/nodes/${guest.node}/${guest.type.api}/${guest.vmid}/status/${action.api}")
    PowerResult(guest, (data as? JsonPrimitive)?.content, null)
} catch (e: PveError) {
    PowerResult(guest, null, e)
}
