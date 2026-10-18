package com.vknxworks.proxctrl.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.ui.components.glass

data class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val BarHeight = 64.dp
private val Inset = 6.dp

/**
 * Floating "island" tab bar: a frosted glass pill with a lens that slides to the selected tab.
 * Material 3 colours, shapes and type; the glass comes from [glass].
 */
@Composable
fun GlassTabBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // Five tabs still fit a phone in portrait; four get a little more room each.
    val ItemWidth = if (items.size >= 5) 72.dp else 80.dp
    val lensX by animateDpAsState(
        targetValue = Inset + ItemWidth * selectedIndex.coerceAtLeast(0),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "lens",
    )
    Box(
        modifier
            .height(BarHeight)
            .width(ItemWidth * items.size + Inset * 2)
            .glass(CircleShape),
    ) {
        if (selectedIndex >= 0) {
            // The lens: a brighter pill under the selected tab.
            Box(
                Modifier
                    .offset(x = lensX)
                    .padding(vertical = Inset)
                    .width(ItemWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            )
        }
        Row(
            Modifier.padding(horizontal = Inset).fillMaxHeight().selectableGroup(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val selected = i == selectedIndex
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "tab-color",
                )
                Column(
                    Modifier
                        .width(ItemWidth)
                        .fillMaxHeight()
                        .padding(vertical = Inset)
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            onClick = {
                                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                }
            }
        }
    }
}
