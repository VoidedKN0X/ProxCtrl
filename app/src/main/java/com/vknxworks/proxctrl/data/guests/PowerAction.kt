package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import kotlinx.serialization.json.JsonPrimitive

/**
 * Guest power actions: POST /nodes/{node}/{qemu|lxc}/{vmid}/status/{action}. All need VM.PowerMgmt
 * on /vms/{vmid} (hibernate also needs Datastore.AllocateSpace on the state storage). Each returns
 * the UPID of the task Proxmox starts. Suspend, resume and hibernate are offered for VMs only:
 * container suspend is marked experimental by Proxmox.
 */
enum class PowerAction(val api: String, val params: Map<String, String> = emptyMap(), val vmOnly: Boolean = false) {
    START("start"),
    SHUTDOWN("shutdown"),
    REBOOT("reboot"),
    STOP("stop"),
    SUSPEND("suspend", vmOnly = true),
    RESUME("resume", vmOnly = true),
    /** Suspend to disk: RAM is written to storage and the VM stops; Start brings it back. */
    HIBERNATE("suspend", mapOf("todisk" to "1"), vmOnly = true),
    ;

    /**
     * Whether the action makes sense for a guest in its current state. [runState] is the more precise
     * QEMU state (qmpstatus: running, paused, suspended…) when known.
     */
    fun appliesTo(g: Guest, runState: String? = null): Boolean {
        if (g.isTemplate) return false
        if (vmOnly && g.type != GuestType.QEMU) return false
        val state = runState ?: g.status
        val paused = state == "paused" || state == "suspended"
        // A hibernated VM is stopped with lock "suspended": Start resumes it. Any other lock blocks actions.
        if (g.lock.isNotEmpty() && !(this == START && g.lock == "suspended")) return false
        return when (this) {
            START -> g.isStopped
            SHUTDOWN, REBOOT -> g.isRunning && !paused
            STOP -> g.isRunning || paused
            SUSPEND, HIBERNATE -> g.isRunning && !paused
            RESUME -> paused
        }
    }

    companion object {
        const val PRIV = "VM.PowerMgmt"

        /** The actions offered for several guests at once. */
        val BULK = listOf(START, SHUTDOWN, REBOOT, STOP)
    }
}

/** Result of running one action on one guest. */
data class PowerResult(val guest: Guest, val upid: String?, val error: PveError?)

suspend fun PveClient.power(guest: Guest, action: PowerAction): PowerResult = try {
    val data = post("/nodes/${guest.node}/${guest.type.api}/${guest.vmid}/status/${action.api}", action.params)
    PowerResult(guest, (data as? JsonPrimitive)?.content, null)
} catch (e: PveError) {
    PowerResult(guest, null, e)
}
