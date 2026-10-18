package com.vknxworks.proxctrl.ui.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.proxctrl.R
import com.vknxworks.proxctrl.data.guests.TagColors
import com.vknxworks.proxctrl.data.settings.GuestSort
import com.vknxworks.proxctrl.ui.components.TagChip

@Composable
fun SortFilterSheet(
    filter: GuestFilter,
    sort: GuestSort,
    descending: Boolean,
    nodes: List<String>,
    tags: List<String>,
    tagColors: TagColors,
    onFilter: (GuestFilter) -> Unit,
    onSort: (GuestSort, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading(stringResource(R.string.sort_by))
            ChipRow {
                GuestSort.entries.forEach { s ->
                    FilterChip(selected = s == sort, onClick = { onSort(s, descending) }, label = { Text(stringResource(s.label)) })
                }
            }
            ChipRow {
                FilterChip(
                    selected = !descending,
                    onClick = { onSort(sort, false) },
                    label = { Text(stringResource(R.string.sort_ascending)) },
                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                )
                FilterChip(
                    selected = descending,
                    onClick = { onSort(sort, true) },
                    label = { Text(stringResource(R.string.sort_descending)) },
                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                )
            }

            Heading(stringResource(R.string.filter_type))
            ChipRow {
                listOf(
                    TypeFilter.ALL to R.string.filter_all,
                    TypeFilter.LXC to R.string.type_lxc,
                    TypeFilter.QEMU to R.string.type_qemu,
                    TypeFilter.TEMPLATE to R.string.type_template,
                ).forEach { (t, label) ->
                    FilterChip(selected = filter.type == t, onClick = { onFilter(filter.copy(type = t)) }, label = { Text(stringResource(label)) })
                }
            }

            Heading(stringResource(R.string.filter_status))
            ChipRow {
                listOf(
                    StatusFilter.ALL to R.string.filter_all,
                    StatusFilter.RUNNING to R.string.status_running,
                    StatusFilter.STOPPED to R.string.status_stopped,
                ).forEach { (s, label) ->
                    FilterChip(selected = filter.status == s, onClick = { onFilter(filter.copy(status = s)) }, label = { Text(stringResource(label)) })
                }
            }

            if (tags.isNotEmpty()) {
                Heading(stringResource(R.string.filter_tags))
                ChipRow {
                    tags.forEach { tag ->
                        val on = tag in filter.tags
                        FilterChip(
                            selected = on,
                            onClick = { onFilter(filter.copy(tags = if (on) filter.tags - tag else filter.tags + tag)) },
                            label = { TagChip(tag, tagColors) },
                        )
                    }
                }
            }

            // Only worth showing in a cluster.
            if (nodes.size > 1) {
                Heading(stringResource(R.string.filter_node))
                ChipRow {
                    FilterChip(selected = filter.node == null, onClick = { onFilter(filter.copy(node = null)) }, label = { Text(stringResource(R.string.filter_all)) })
                    nodes.forEach { n ->
                        FilterChip(selected = filter.node == n, onClick = { onFilter(filter.copy(node = n)) }, label = { Text(n) })
                    }
                }
            }

            if (filter.isActive) {
                TextButton(onClick = { onFilter(GuestFilter(query = filter.query)) }) { Text(stringResource(R.string.clear_filters)) }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
