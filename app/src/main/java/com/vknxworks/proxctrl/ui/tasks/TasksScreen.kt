package com.vknxworks.proxctrl.ui.tasks

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Task
import androidx.compose.runtime.Composable
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.TabPlaceholder

/** Running and recent tasks with logs. Filled in once server setup lands. */
@Composable
fun TasksScreen() {
    TabPlaceholder(R.string.tab_tasks, R.string.tasks_empty_hint, Icons.Outlined.Task)
}
