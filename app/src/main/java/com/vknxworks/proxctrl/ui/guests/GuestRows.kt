package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.vknxworks.proxctrl.ui.theme.CtrlColors
import com.vknxworks.proxctrl.ui.theme.CtrlType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.TagColors
import com.vknxworks.proxctrl.ui.components.StatusDot
import com.vknxworks.proxctrl.ui.components.TagRow
import com.vknxworks.proxctrl.ui.components.UsageBar
import com.vknxworks.proxctrl.ui.components.formatUptime
import com.vknxworks.proxctrl.ui.components.percent
import com.vknxworks.proxctrl.ui.components.usageColor
import com.vknxworks.proxctrl.ui.theme.Gutter

/** Container, VM or template icon, as in the Proxmox resource tree. */
fun guestIcon(g: Guest): ImageVector = when {
    g.isTemplate -> Icons.Outlined.Inventory2
    g.type == GuestType.LXC -> Icons.Outlined.ViewInAr
    else -> Icons.Outlined.Computer
}

@Composable
fun statusLabel(g: Guest): String = stringResource(
    when (g.status) {
        "running" -> R.string.status_running
        "stopped" -> R.string.status_stopped
        "paused", "suspended" -> R.string.status_paused
        else -> R.string.status_unknown
    },
)

/** Type icon with a status dot, or a check circle while selecting. */
@Composable
private fun GuestBadge(g: Guest, selecting: Boolean, selected: Boolean, size: Int) {
    Box(Modifier.size(size.dp)) {
        if (selecting) {
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
                null,
                Modifier.size(size.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Box(
                Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(CtrlColors.Surface).border(1.dp, CtrlColors.Outline, RoundedCornerShape((size * 0.3f).dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(guestIcon(g), null, Modifier.size((size * 0.55f).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!g.isTemplate) StatusDot(g.status, Modifier.align(Alignment.BottomEnd).offset(1.dp, 1.dp), size = (size / 3.6f).dp)
        }
    }
}

@Composable
private fun rowModifier(selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    Modifier
        .fillMaxWidth()
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** ID and name, with the ID in a quieter weight. */
@Composable
private fun GuestTitle(g: Guest, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            g.vmid.toString(),
            style = CtrlType.Mono.copy(fontSize = 13.sp),
            color = CtrlColors.Muted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            g.name.ifBlank { "—" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Detailed row: status, uptime, CPU / RAM / disk bars and tags. */
@Composable
fun GuestRowDetailed(
    g: Guest,
    tagColors: TagColors,
    diskWarn: Int,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(rowModifier(selected, onClick, onLongClick).padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        GuestBadge(g, selecting, selected, 40)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GuestTitle(g, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        g.isTemplate -> stringResource(R.string.template)
                        g.isRunning -> stringResource(R.string.uptime_value, formatUptime(g.uptime))
                        else -> statusLabel(g)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (g.lock.isNotEmpty() || g.haState.isNotEmpty()) {
                Text(
                    listOfNotNull(
                        g.lock.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.locked, it) },
                        g.haState.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ha_state, it) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!g.isTemplate) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meter(stringResource(R.string.label_cpu), g.cpuFraction, null, Modifier.weight(1f))
                    Meter(stringResource(R.string.label_mem), g.memFraction, null, Modifier.weight(1f))
                    Meter(stringResource(R.string.label_disk), g.diskFraction, diskWarn, Modifier.weight(1f))
                }
            }
            if (g.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TagRow(g.tags, tagColors)
            }
        }
    }
}

/** Label and value over a usage bar. [warnAt] colours the bar (disk); null uses the accent. */
@Composable
private fun Meter(label: String, fraction: Double?, warnAt: Int?, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val color = if (warnAt != null) usageColor(fraction, warnAt, accent) else accent
    Column(modifier) {
        Row {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                fraction?.let { stringResource(R.string.percent, percent(it)) } ?: stringResource(R.string.no_value),
                style = MaterialTheme.typography.labelSmall,
                color = if (warnAt != null && color != accent) color else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(3.dp))
        UsageBar(fraction, color)
    }
}

/** Compact row for long lists: one line with CPU, RAM and disk percentages. */
@Composable
fun GuestRowCompact(
    g: Guest,
    diskWarn: Int,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        rowModifier(selected, onClick, onLongClick).padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GuestBadge(g, selecting, selected, 28)
        Spacer(Modifier.width(12.dp))
        GuestTitle(g, Modifier.weight(1f))
        if (!g.isTemplate) {
            val accent = MaterialTheme.colorScheme.onSurfaceVariant
            CompactValue(g.cpuFraction, accent)
            CompactValue(g.memFraction, accent)
            CompactValue(g.diskFraction, usageColor(g.diskFraction, diskWarn, accent))
        } else {
            Text(stringResource(R.string.template), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompactValue(fraction: Double?, color: Color) {
    Text(
        fraction?.let { stringResource(R.string.percent, percent(it)) } ?: stringResource(R.string.no_value),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.widthIn(min = 44.dp).padding(start = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

/** Column headings above the compact list. */
@Composable
fun CompactHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        listOf(R.string.label_cpu, R.string.label_mem, R.string.label_disk).forEach {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 44.dp).padding(start = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}
