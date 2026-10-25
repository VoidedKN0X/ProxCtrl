package com.vknxworks.proxctrl.ui.guests

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** Create: pick a container, a VM or a template to clone. */
@Serializable data object CreateGuestRoute

/** The container wizard. */
@Serializable data object CreateLxcRoute

/** The VM wizard. */
@Serializable data object CreateVmRoute

/** The create flow's screens. */
fun NavGraphBuilder.createDestinations() {
    composable<CreateGuestRoute> { CreateGuestScreen() }
    composable<CreateLxcRoute> { CreateLxcScreen() }
    composable<CreateVmRoute> { CreateVmScreen() }
}
