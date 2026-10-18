package com.vknxworks.proxctrl.ui.guests

import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.settings.GuestSort

enum class TypeFilter { ALL, LXC, QEMU, TEMPLATE }

enum class StatusFilter { ALL, RUNNING, STOPPED }

/** What the guest list is narrowed to. Not saved: the list always opens showing everything. */
data class GuestFilter(
    val type: TypeFilter = TypeFilter.ALL,
    val status: StatusFilter = StatusFilter.ALL,
    /** Guests with any of these tags. */
    val tags: Set<String> = emptySet(),
    val node: String? = null,
    val query: String = "",
) {
    val isActive: Boolean get() = type != TypeFilter.ALL || status != StatusFilter.ALL || tags.isNotEmpty() || node != null

    fun matches(g: Guest): Boolean {
        val typeOk = when (type) {
            TypeFilter.ALL -> true
            TypeFilter.LXC -> g.type == GuestType.LXC && !g.isTemplate
            TypeFilter.QEMU -> g.type == GuestType.QEMU && !g.isTemplate
            TypeFilter.TEMPLATE -> g.isTemplate
        }
        val statusOk = when (status) {
            StatusFilter.ALL -> true
            StatusFilter.RUNNING -> g.isRunning
            StatusFilter.STOPPED -> !g.isRunning
        }
        val tagsOk = tags.isEmpty() || g.tags.any { it in tags }
        val nodeOk = node == null || g.node == node
        val q = query.trim()
        val queryOk = q.isEmpty() || g.name.contains(q, ignoreCase = true) || g.vmid.toString().startsWith(q) ||
            g.tags.any { it.contains(q, ignoreCase = true) }
        return typeOk && statusOk && tagsOk && nodeOk && queryOk
    }
}

/**
 * Sorts guests. Numbers sort highest first when [descending]; guests without a value (stopped guests
 * for CPU and memory, VMs without guest-agent data for disk) always go last.
 */
fun List<Guest>.sortedFor(sort: GuestSort, descending: Boolean): List<Guest> {
    fun <T : Comparable<T>> by(selector: (Guest) -> T?): List<Guest> {
        val (known, unknown) = partition { selector(it) != null }
        val sorted = known.sortedWith(compareBy<Guest> { selector(it) }.thenBy { it.vmid })
        return (if (descending) sorted.reversed() else sorted) + unknown.sortedBy { it.vmid }
    }
    return when (sort) {
        GuestSort.ID -> if (descending) sortedByDescending { it.vmid } else sortedBy { it.vmid }
        GuestSort.NAME -> sortedWith(compareBy<Guest, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.vmid })
            .let { if (descending) it.reversed() else it }
        GuestSort.CPU -> by { it.cpuFraction }
        GuestSort.MEMORY -> by { it.memFraction }
        GuestSort.DISK -> by { it.diskFraction }
        GuestSort.UPTIME -> by { g -> g.uptime.takeIf { g.isRunning } }
    }
}
