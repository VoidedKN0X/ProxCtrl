package com.vknxworks.proxctrl.ui.guests

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.runtime.Composable
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.TabPlaceholder

/** All LXC containers and QEMU VMs from /cluster/resources. Filled in once server setup lands. */
@Composable
fun GuestsScreen() {
    TabPlaceholder(R.string.tab_guests, R.string.guests_empty_hint, Icons.Outlined.Computer)
}
