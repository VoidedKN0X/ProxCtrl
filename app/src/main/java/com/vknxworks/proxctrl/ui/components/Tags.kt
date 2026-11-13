package com.vknxworks.proxctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.data.guests.TagColors

/** A Proxmox tag in the colours the web UI uses for it. */
@Composable
fun TagChip(tag: String, colors: TagColors, modifier: Modifier = Modifier) {
    val c = colors.colorOf(tag)
    Text(
        tag,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF000000.toInt() or c.text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF000000.toInt() or c.background))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun TagRow(tags: List<String>, colors: TagColors, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { TagChip(it, colors) }
    }
}
