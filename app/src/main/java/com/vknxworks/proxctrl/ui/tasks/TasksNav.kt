package com.vknxworks.proxctrl.ui.tasks

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** One task's details and full log. */
@Serializable
data class TaskDetailRoute(val upid: String)

/** One guest's task history. */
@Serializable
data class GuestTasksRoute(val node: String, val vmid: Int, val name: String = "")

/** The screens the Tasks tab and the guest page open. */
fun NavGraphBuilder.tasksDestinations() {
    composable<TaskDetailRoute> {
        val r = it.toRoute<TaskDetailRoute>()
        TaskDetailScreen(r.upid)
    }
    composable<GuestTasksRoute> {
        val r = it.toRoute<GuestTasksRoute>()
        GuestTasksScreen(r.node, r.vmid, r.name)
    }
}
