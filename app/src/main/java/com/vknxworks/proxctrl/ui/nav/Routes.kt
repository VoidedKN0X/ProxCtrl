package com.vknxworks.proxctrl.ui.nav

import kotlinx.serialization.Serializable

// Tabs
@Serializable data object OverviewRoute
@Serializable data object GuestsRoute
@Serializable data object StorageRoute
@Serializable data object TasksRoute
@Serializable data object NodeRoute

@Serializable data class GuestRoute(val node: String, val type: String, val vmid: Int)
@Serializable data class SnapshotsRoute(val node: String, val type: String, val vmid: Int, val name: String = "")
@Serializable data class BackupsRoute(val node: String, val type: String, val vmid: Int, val name: String = "")

/** A console: kind is "node", "lxc" or "qemu"; serial is set for VMs with a serial-terminal display. */
@Serializable data class ConsoleRoute(val kind: String, val node: String, val vmid: Int = 0, val serial: String? = null, val title: String = "")

// Account and settings, opened from the profile picture
@Serializable data object YouRoute
@Serializable data object SettingsRoute
@Serializable data object NotificationsRoute
@Serializable data object ServersRoute
@Serializable data object AddServerRoute
@Serializable data class EditServerRoute(val id: String)
