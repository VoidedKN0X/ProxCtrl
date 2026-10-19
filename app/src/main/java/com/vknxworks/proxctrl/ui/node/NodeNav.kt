package com.vknxworks.proxctrl.ui.node

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** A node's services (systemd units Proxmox manages). */
@Serializable data class NodeServicesRoute(val node: String)

/** A node's available package updates. */
@Serializable data class NodeUpdatesRoute(val node: String)

/** A node's network interfaces and the cluster's SDN zones and VNets. */
@Serializable data class NodeNetworkRoute(val node: String)

/** The Node tab's own screens, registered in the app's navigation graph. */
fun NavGraphBuilder.nodeDestinations() {
    composable<NodeServicesRoute> { NodeServicesScreen(it.toRoute<NodeServicesRoute>().node) }
    composable<NodeUpdatesRoute> { NodeUpdatesScreen(it.toRoute<NodeUpdatesRoute>().node) }
    composable<NodeNetworkRoute> { NodeNetworkScreen(it.toRoute<NodeNetworkRoute>().node) }
}
