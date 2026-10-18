package com.vknxworks.proxctrl.ui.storage

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.components.TabPlaceholder

/** Storages, content, disks, ZFS/LVM and backup jobs. Filled in once server setup lands. */
@Composable
fun StorageScreen() {
    TabPlaceholder(R.string.tab_storage, R.string.storage_empty_hint, Icons.Outlined.Storage)
}
