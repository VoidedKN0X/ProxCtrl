package com.vknxworks.proxctrl.ui.tasks

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.ScreenTopBar

/** Task history for one guest: its running tasks and everything it ran before, with the same filters. */
@Composable
fun GuestTasksScreen(node: String, vmid: Int, name: String) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = listOf(vmid.toString(), name).filter { it.isNotBlank() }.joinToString("  ")
    TaskListContent(fixedVmid = vmid, bottomPadding = bottom) {
        ScreenTopBar(stringResource(R.string.tasks_guest_title, title))
    }
}
