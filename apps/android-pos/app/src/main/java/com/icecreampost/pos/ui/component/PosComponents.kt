package com.icecreampost.pos.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.icecreampost.pos.data.local.entity.SyncStateEntity

fun formatMoney(cents: Long): String = "₱${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

@Composable
fun SyncStatusCard(syncState: SyncStateEntity? = null) {
    val status = syncState?.status ?: "offline-ready"
    val detail = syncState?.errorMessage ?: when (status) {
        "success" -> "Last sync completed"
        "running" -> "Syncing queued sales"
        "retrying" -> "Waiting to retry"
        "error" -> "Action required"
        else -> "Changes save to Room first"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(detail)
            Text(status, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun CategoryFilters(
    categories: List<String>,
    selected: String?,
    onSelected: (String?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text("All") })
        categories.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(if (selected == category) null else category) },
                label = { Text(category) },
            )
        }
    }
}
