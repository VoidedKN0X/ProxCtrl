package com.vknxworks.proxctrl.ui.tasks

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.LocalBottomClearance
import com.vknxworks.proxctrl.ui.components.TabTitle

/** The Tasks tab: tasks running now, with live progress and Stop, then the filterable history. */
@Composable
fun TasksScreen() {
    TaskListContent(fixedVmid = null, bottomPadding = LocalBottomClearance.current) {
        TabTitle(stringResource(R.string.tab_tasks), Modifier.statusBarsPadding())
    }
}
