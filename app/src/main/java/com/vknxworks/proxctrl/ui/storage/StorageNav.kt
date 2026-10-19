package com.vknxworks.proxctrl.ui.storage

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** One storage's content (backups, ISOs, templates, disk images). */
@Serializable data class StorageContentRoute(val node: String, val storage: String)

/** The Proxmox container template list, to download one to [storage]. */
@Serializable data class StorageTemplatesRoute(val node: String, val storage: String)

/** Physical disks with S.M.A.R.T., plus ZFS pools and LVM of a node. */
@Serializable data class StorageDisksRoute(val node: String)

/** One ZFS pool's health, scrub and devices. */
@Serializable data class StorageZfsRoute(val node: String, val name: String)

/** Scheduled backup jobs. */
@Serializable data object StorageBackupJobsRoute

/** The Storage tab's screens below the tab itself. */
fun NavGraphBuilder.storageDestinations() {
    composable<StorageContentRoute> {
        val r = it.toRoute<StorageContentRoute>()
        StorageContentScreen(r.node, r.storage)
    }
    composable<StorageTemplatesRoute> {
        val r = it.toRoute<StorageTemplatesRoute>()
        TemplatesScreen(r.node, r.storage)
    }
    composable<StorageDisksRoute> {
        val r = it.toRoute<StorageDisksRoute>()
        DisksScreen(r.node)
    }
    composable<StorageZfsRoute> {
        val r = it.toRoute<StorageZfsRoute>()
        ZfsPoolScreen(r.node, r.name)
    }
    composable<StorageBackupJobsRoute> { BackupJobsScreen() }
}
