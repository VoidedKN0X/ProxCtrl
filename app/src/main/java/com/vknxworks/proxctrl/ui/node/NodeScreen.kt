package com.vknxworks.proxctrl.ui.node

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.runtime.Composable
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.TabPlaceholder

/** Node detail, services, updates, network and shell. Filled in once server setup lands. */
@Composable
fun NodeScreen() {
    TabPlaceholder(R.string.tab_node, R.string.node_empty_hint, Icons.Outlined.Dns)
}
