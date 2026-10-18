package com.vknxworks.proxctrl.ui.overview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.runtime.Composable
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.TabPlaceholder

/** Node health and the attention list. Filled in once server setup lands. */
@Composable
fun OverviewScreen() {
    TabPlaceholder(R.string.tab_overview, R.string.overview_empty_hint, Icons.Outlined.Dashboard)
}
