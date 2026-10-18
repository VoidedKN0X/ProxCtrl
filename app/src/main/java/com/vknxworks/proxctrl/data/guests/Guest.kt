package com.vknxworks.proxctrl.data.guests

import kotlinx.serialization.Serializable

/**
 * One entry of GET /cluster/resources?type=vm. Numbers are read as Double because Proxmox sends
 * some of them as floats; every field has a default so a missing one never breaks the list.
 */
@Serializable
data class ClusterResource(
    val id: String = "",
    val type: String = "",
    val vmid: Int = 0,
    val name: String = "",
    val node: String = "",
    val status: String = "",
    val template: Int = 0,
    val cpu: Double = 0.0,
    val maxcpu: Double = 0.0,
    val mem: Double = 0.0,
    val maxmem: Double = 0.0,
    val disk: Double = 0.0,
    val maxdisk: Double = 0.0,
    val uptime: Double = 0.0,
    val tags: String = "",
    val hastate: String = "",
    val lock: String = "",
    val pool: String = "",
)

enum class GuestType(val api: String) {
    LXC("lxc"),
    QEMU("qemu"),
    ;

    companion object {
        fun of(api: String): GuestType? = entries.firstOrNull { it.api == api }
    }
}

/** A container or VM as shown in the app. */
@Serializable
data class Guest(
    val type: GuestType,
    val vmid: Int,
    val name: String,
    val node: String,
    /** running, stopped, paused (suspended), or unknown. */
    val status: String,
    val isTemplate: Boolean,
    /** 0..1 of [maxCpu] cores. */
    val cpu: Double,
    val maxCpu: Double,
    val mem: Long,
    val maxMem: Long,
    /** Used/total disk bytes. LXC from Proxmox; for VMs Proxmox reports 0 and the guest agent fills it in. */
    val disk: Long,
    val maxDisk: Long,
    val uptime: Long,
    val tags: List<String>,
    val haState: String,
    val lock: String,
) {
    val isRunning: Boolean get() = status == "running"
    val isStopped: Boolean get() = status == "stopped"

    /** The ACL path for permission checks. */
    val aclPath: String get() = "/vms/$vmid"

    val memFraction: Double? get() = if (maxMem > 0 && isRunning) mem.toDouble() / maxMem else null

    /** Disk use, or null when unknown (VMs without guest-agent data). */
    val diskFraction: Double? get() = if (maxDisk > 0 && disk > 0) disk.toDouble() / maxDisk else null

    val cpuFraction: Double? get() = if (isRunning) cpu.coerceIn(0.0, 1.0) else null

    companion object {
        fun from(r: ClusterResource): Guest? {
            val type = GuestType.of(r.type) ?: return null
            val vmid = r.vmid.takeIf { it > 0 } ?: r.id.substringAfterLast('/').toIntOrNull() ?: return null
            return Guest(
                type = type,
                vmid = vmid,
                name = r.name,
                node = r.node,
                status = r.status.ifBlank { "unknown" },
                isTemplate = r.template != 0,
                cpu = r.cpu,
                maxCpu = r.maxcpu,
                mem = r.mem.toLong(),
                maxMem = r.maxmem.toLong(),
                // QEMU reports 0 used: Proxmox can't see inside a VM's disks.
                disk = if (type == GuestType.LXC) r.disk.toLong() else 0L,
                maxDisk = r.maxdisk.toLong(),
                uptime = r.uptime.toLong(),
                tags = r.tags.split(';', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
                haState = r.hastate,
                lock = r.lock,
            )
        }
    }
}

/** Disk use read from the QEMU guest agent (get-fsinfo). */
data class AgentDisk(val used: Long, val total: Long)
