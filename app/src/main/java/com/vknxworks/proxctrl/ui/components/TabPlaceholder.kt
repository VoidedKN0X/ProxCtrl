package com.vknxworks.proxctrl.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.ui.theme.Gutter

/** A main tab with nothing connected yet: its title, what it will show, and a pointer to server setup. */
@Composable
fun TabPlaceholder(@StringRes title: Int, @StringRes hint: Int, icon: ImageVector) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabTitle(stringResource(title))
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
        )
        EmptyState(
            icon = icon,
            title = stringResource(R.string.no_server_title),
            body = stringResource(R.string.no_server_body),
            modifier = Modifier.weight(1f).padding(bottom = LocalBottomClearance.current),
        )
        Spacer(Modifier.height(8.dp))
    }
}
