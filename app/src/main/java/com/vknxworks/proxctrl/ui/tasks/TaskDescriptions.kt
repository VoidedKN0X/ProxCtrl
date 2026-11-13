package com.vknxworks.proxctrl.ui.tasks

import android.content.Context
import androidx.annotation.StringRes
import com.vknxworks.proxctrl.R

/** What a task is about, shown before the action ("VM 106 – Start"). */
private enum class Subject { NONE, VM, CT, GUEST, SERVICE, DISK }

/**
 * The common task types, as the Proxmox web UI names them (Proxmox.Utils.task_desc_table and PVE's
 * overrides). Unknown types fall back to "type id".
 */
private val TASK_TYPES: Map<String, Pair<Subject, Int>> = mapOf(
    // VMs
    "qmstart" to (Subject.VM to R.string.tasks_act_start),
    "qmstop" to (Subject.VM to R.string.tasks_act_stop),
    "qmshutdown" to (Subject.VM to R.string.tasks_act_shutdown),
    "qmreboot" to (Subject.VM to R.string.tasks_act_reboot),
    "qmreset" to (Subject.VM to R.string.tasks_act_reset),
    "qmsuspend" to (Subject.VM to R.string.tasks_act_hibernate),
    "qmpause" to (Subject.VM to R.string.tasks_act_pause),
    "qmresume" to (Subject.VM to R.string.tasks_act_resume),
    "qmclone" to (Subject.VM to R.string.tasks_act_clone),
    "qmcreate" to (Subject.VM to R.string.tasks_act_create),
    "qmconfig" to (Subject.VM to R.string.tasks_act_configure),
    "qmdestroy" to (Subject.VM to R.string.tasks_act_destroy),
    "qmigrate" to (Subject.VM to R.string.tasks_act_migrate),
    "qmmove" to (Subject.VM to R.string.tasks_act_move_disk),
    "qmrestore" to (Subject.VM to R.string.tasks_act_restore),
    "qmsnapshot" to (Subject.VM to R.string.tasks_act_snapshot),
    "qmdelsnapshot" to (Subject.VM to R.string.tasks_act_delete_snapshot),
    "qmrollback" to (Subject.VM to R.string.tasks_act_rollback),
    "qmtemplate" to (Subject.VM to R.string.tasks_act_template),
    // Containers
    "vzstart" to (Subject.CT to R.string.tasks_act_start),
    "vzstop" to (Subject.CT to R.string.tasks_act_stop),
    "vzshutdown" to (Subject.CT to R.string.tasks_act_shutdown),
    "vzreboot" to (Subject.CT to R.string.tasks_act_reboot),
    "vzsuspend" to (Subject.CT to R.string.tasks_act_suspend),
    "vzresume" to (Subject.CT to R.string.tasks_act_resume),
    "vzclone" to (Subject.CT to R.string.tasks_act_clone),
    "vzcreate" to (Subject.CT to R.string.tasks_act_create),
    "vzdestroy" to (Subject.CT to R.string.tasks_act_destroy),
    "vzmigrate" to (Subject.CT to R.string.tasks_act_migrate),
    "vzrestore" to (Subject.CT to R.string.tasks_act_restore),
    "vzsnapshot" to (Subject.CT to R.string.tasks_act_snapshot),
    "vzdelsnapshot" to (Subject.CT to R.string.tasks_act_delete_snapshot),
    "vzrollback" to (Subject.CT to R.string.tasks_act_rollback),
    "vztemplate" to (Subject.CT to R.string.tasks_act_template),
    "vzmount" to (Subject.CT to R.string.tasks_act_mount),
    "vzumount" to (Subject.CT to R.string.tasks_act_unmount),
    "move_volume" to (Subject.CT to R.string.tasks_act_move_volume),
    // Either
    "resize" to (Subject.GUEST to R.string.tasks_act_resize),
    "vncproxy" to (Subject.GUEST to R.string.tasks_act_console),
    "hastart" to (Subject.GUEST to R.string.tasks_act_ha_start),
    "hastop" to (Subject.GUEST to R.string.tasks_act_ha_stop),
    "hashutdown" to (Subject.GUEST to R.string.tasks_act_ha_shutdown),
    "hamigrate" to (Subject.GUEST to R.string.tasks_act_ha_migrate),
    // Node
    "vncshell" to (Subject.NONE to R.string.tasks_act_shell),
    "termproxy" to (Subject.NONE to R.string.tasks_act_console_xterm),
    "aptupdate" to (Subject.NONE to R.string.tasks_act_aptupdate),
    "startall" to (Subject.NONE to R.string.tasks_act_startall),
    "stopall" to (Subject.NONE to R.string.tasks_act_stopall),
    "suspendall" to (Subject.NONE to R.string.tasks_act_suspendall),
    "migrateall" to (Subject.NONE to R.string.tasks_act_migrateall),
    "reloadnetworkall" to (Subject.NONE to R.string.tasks_act_reload_network),
    "imgcopy" to (Subject.NONE to R.string.tasks_act_imgcopy),
    "imgdel" to (Subject.NONE to R.string.tasks_act_imgdel),
    "download" to (Subject.NONE to R.string.tasks_act_download),
    "srvstart" to (Subject.SERVICE to R.string.tasks_act_start),
    "srvstop" to (Subject.SERVICE to R.string.tasks_act_stop),
    "srvrestart" to (Subject.SERVICE to R.string.tasks_act_restart),
    "srvreload" to (Subject.SERVICE to R.string.tasks_act_reload),
    "diskinit" to (Subject.DISK to R.string.tasks_act_diskinit),
    "wipedisk" to (Subject.DISK to R.string.tasks_act_wipedisk),
    "zfscreate" to (Subject.NONE to R.string.tasks_act_zfscreate),
    "lvmcreate" to (Subject.NONE to R.string.tasks_act_lvmcreate),
    "lvmthincreate" to (Subject.NONE to R.string.tasks_act_lvmthincreate),
    "dircreate" to (Subject.NONE to R.string.tasks_act_dircreate),
    "acmenewcert" to (Subject.NONE to R.string.tasks_act_acme_order),
    "acmerenew" to (Subject.NONE to R.string.tasks_act_acme_renew),
)

/** Task types offered in the type filter, in this order; any others seen in the list are added after. */
internal val COMMON_TYPES = listOf(
    "vzdump", "qmstart", "qmstop", "qmshutdown", "qmreboot", "vzstart", "vzstop", "vzshutdown", "vzreboot",
    "qmsnapshot", "vzsnapshot", "qmrestore", "vzrestore", "aptupdate", "vncshell", "termproxy", "vncproxy",
)

/**
 * A readable name for a task: "VM 106 (haos) – Start", "Backup job", "Update package database".
 * [guestName] is added after the ID when the task is about a known guest.
 */
fun taskDescription(context: Context, type: String, id: String, guestName: String? = null): String {
    if (type == "vzdump") {
        return if (id.isBlank()) context.getString(R.string.tasks_act_backup_job)
        else context.getString(R.string.tasks_desc, subject(context, Subject.GUEST, id, guestName), context.getString(R.string.tasks_act_backup))
    }
    val entry = TASK_TYPES[type] ?: return if (id.isBlank()) type else "$type $id"
    val (subj, action) = entry
    val actionText = context.getString(action)
    if (subj == Subject.NONE || id.isBlank()) return actionText
    return context.getString(R.string.tasks_desc, subject(context, subj, id, guestName), actionText)
}

/** The short name of a task type for the filter ("Backup", "VM start"); falls back to the raw type. */
fun taskTypeName(context: Context, type: String): String {
    if (type == "vzdump") return context.getString(R.string.tasks_act_backup)
    val (subj, action) = TASK_TYPES[type] ?: return type
    val actionText = context.getString(action)
    @StringRes val kind = when (subj) {
        Subject.VM -> R.string.tasks_kind_vm
        Subject.CT -> R.string.tasks_kind_ct
        Subject.SERVICE -> R.string.tasks_kind_service
        else -> return actionText
    }
    return context.getString(kind, actionText)
}

private fun subject(context: Context, subj: Subject, id: String, guestName: String?): String {
    val withName = if (!guestName.isNullOrBlank()) context.getString(R.string.tasks_id_with_name, id, guestName) else id
    return when (subj) {
        Subject.VM -> context.getString(R.string.tasks_subject_vm, withName)
        Subject.CT -> context.getString(R.string.tasks_subject_ct, withName)
        Subject.GUEST -> context.getString(R.string.tasks_subject_guest, withName)
        Subject.SERVICE -> context.getString(R.string.tasks_subject_service, id)
        Subject.DISK -> context.getString(R.string.tasks_subject_disk, id)
        Subject.NONE -> id
    }
}

/** "45s", "3m 12s", "1h 4m". */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
    }
}
