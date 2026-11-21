package com.vknxworks.proxctrl.ui.guests

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute

/** A guest page's own sub-screens (guest agent, cloud-init), registered in the app's navigation graph. */
fun NavGraphBuilder.guestDestinations() {
    composable<GuestAgentRoute> {
        val r = it.toRoute<GuestAgentRoute>()
        GuestAgentScreen(r.node, r.vmid, r.name)
    }
    composable<CloudInitRoute> {
        val r = it.toRoute<CloudInitRoute>()
        CloudInitScreen(r.node, r.vmid, r.name)
    }
}
