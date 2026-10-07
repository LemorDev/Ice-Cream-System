package com.icecreampost.pos.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*
import com.icecreampost.pos.ui.screen.checkout.toCentsOrNull
import java.time.LocalDate

@Composable
fun HomeScreen(viewModel: PosViewModel, onCatalog: () -> Unit, onOperatingDay: () -> Unit, onInventory: () -> Unit) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val lowStockProducts by viewModel.lowStockProducts.collectAsStateWithLifecycle()
    val businessDay by viewModel.businessDay.collectAsStateWithLifecycle()
    val amountsVisible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    val today = LocalDate.now(businessZone).toString()
    val day = businessDay?.takeIf { it.stallId == session?.stallId }
    val stockAlerts = lowStockProducts.filter { it.stallId == session?.stallId && it.deletedAt == null }
    val isOpen = day != null && day.closedAt == null
    val visibleDay = day?.takeIf { isOpen || it.businessDate == today }
    val completed = transactions.filter {
        visibleDay != null && it.status == "completed" && it.stallId == visibleDay.stallId &&
            it.occurredAt >= visibleDay.openedAt && (visibleDay.closedAt == null || it.occurredAt <= visibleDay.closedAt)
    }
    val activitySalesCents = completed.sumOf { it.totalCents } + (visibleDay?.recoveryKnownSalesCents ?: 0)
    val activityOrders = completed.size + (visibleDay?.recoveryKnownOrders ?: 0)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoolerzLogo(Modifier.size(48.dp))
                Text("COOLERZ POS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text("Hello, ${session?.displayName ?: "Cashier"}", style = MaterialTheme.typography.headlineMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Ready for the day", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stockAlerts.isNotEmpty()) item {
            val outOfStockCount = stockAlerts.count { it.unitsInStock <= 0.0 }
            Surface(
                onClick = onInventory,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (outOfStockCount > 0) "Stock needs attention" else "Low stock alert",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        buildString {
                            append("${stockAlerts.size} stock item${if (stockAlerts.size == 1) "" else "s"} at or below its alert level")
                            if (outOfStockCount > 0) append(" · $outOfStockCount out of stock")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    stockAlerts.take(3).forEach { product ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(product.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(formatStock(product.unitsInStock, product.baseUnit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    if (stockAlerts.size > 3) Text("And ${stockAlerts.size - 3} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Tap to review inventory", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        item {
            Text(if (isOpen) "Stall open" else "Stall closed", style = MaterialTheme.typography.titleLarge,
                color = if (isOpen) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
            Text(formatBusinessDate(visibleDay?.businessDate ?: today), style = MaterialTheme.typography.bodyMedium)
            if (isOpen) Text("Opened ${formatReceiptTime(day!!.openedAt).substringAfter(" · ")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(onClick = onCatalog, enabled = isOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                Text("New Sale", style = MaterialTheme.typography.titleMedium)
            }
            if (!isOpen) {
                Text(if (day?.businessDate == today) "Today's operating day is closed." else "Open the stall before accepting sales.",
                    modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOperatingDay) { Text("Operating day") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's activity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AmountVisibilityButton(amountsVisible, viewModel::toggleAmountsVisible)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Sales", formatMoney(activitySalesCents, amountsVisible), Modifier.weight(1f))
                Metric("Orders", activityOrders.toString(), Modifier.weight(1f))
            }
        }
        item { HomeAnalytics(transactions, session?.stallId, amountsVisible) }
        item { SyncStatusLine(viewModel) }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun validateRevenueDeduction(amount: String, reason: String, grossSalesCents: Long): String? {
    if (amount.isBlank()) return null
    val deductionCents = amount.toCentsOrNull() ?: return "Enter a valid deduction amount."
    if (deductionCents <= 0) return "Enter a deduction greater than zero."
    if (deductionCents > grossSalesCents) return "Deduction cannot exceed gross sales."
    if (deductionCents > 0 && reason.isBlank()) return "Provide a reason for the deduction."
    return null
}
