package com.icecreampost.pos.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.icecreampost.pos.data.local.entity.SyncStateEntity

fun formatMoney(cents: Long, visible: Boolean = true): String =
    if (visible) "₱${cents / 100}.${(cents % 100).toString().padStart(2, '0')}" else "₱••••"

@Composable
fun AmountVisibilityButton(visible: Boolean, onToggle: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    IconButton(onClick = onToggle) {
        Canvas(
            modifier = Modifier.size(24.dp).semantics {
                contentDescription = if (visible) "Hide sales amounts" else "Show sales amounts"
            },
        ) {
            drawOval(
                color = color,
                topLeft = Offset(2.dp.toPx(), 6.dp.toPx()),
                size = Size(20.dp.toPx(), 12.dp.toPx()),
                style = Stroke(width = 1.8.dp.toPx()),
            )
            drawCircle(color = color, radius = 3.dp.toPx(), center = center)
            if (!visible) {
                drawLine(
                    color = color,
                    start = Offset(3.dp.toPx(), 3.dp.toPx()),
                    end = Offset(21.dp.toPx(), 21.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(48.dp).semantics {
                    role = Role.Button
                    contentDescription = "Go back"
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun QuantityStepper(
    quantity: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canIncrease: Boolean = true,
    canDecrease: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(48.dp),
            onClick = onDecrease,
            enabled = canDecrease,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("−", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = "Decrease quantity" })
            }
        }
        Text(
            quantity.toString(),
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Surface(
            modifier = Modifier.size(48.dp),
            onClick = onIncrease,
            enabled = canIncrease,
            shape = CircleShape,
            color = if (canIncrease) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (canIncrease) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.semantics { contentDescription = "Increase quantity" },
                )
            }
        }
    }
}

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
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(
                status.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = when (status) {
                    "success" -> MaterialTheme.colorScheme.secondary
                    "error" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            )
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
