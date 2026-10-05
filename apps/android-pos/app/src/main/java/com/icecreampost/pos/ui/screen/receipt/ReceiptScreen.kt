package com.icecreampost.pos.ui.screen.receipt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.data.repository.HistoricalReceipt
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*

@Composable
fun ReceiptScreen(viewModel: PosViewModel, onDone: () -> Unit) {
    val receipt by viewModel.receipt.collectAsStateWithLifecycle()
    val details by viewModel.completedReceiptDetails.collectAsStateWithLifecycle()
    val visible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                ScreenHeader("Sale complete")
                Text("Saved on this device", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Receipt", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    AmountVisibilityButton(visible, viewModel::toggleAmountsVisible)
                }
            }
            details?.let { item { ReceiptDetails(it, visible) } } ?: receipt?.let { saved ->
                item {
                    Text(saved.receiptNumber)
                    Text("Item details are unavailable. You can reopen this receipt from History.", style = MaterialTheme.typography.bodyMedium)
                    ReceiptAmount("Total", saved.subtotalCents, visible, true)
                    ReceiptAmount("Cash received", saved.cashReceivedCents, visible)
                    ReceiptAmount("Change", saved.changeAmountCents, visible)
                }
            } ?: item { Text("Receipt details are no longer available.") }
            item { Text("This sale will sync automatically when a connection is available.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) { Text("New Sale") }
    }
}

@Composable
fun HistoricalReceiptScreen(viewModel: PosViewModel, onBack: () -> Unit) {
    val receipt by viewModel.historicalReceipt.collectAsStateWithLifecycle()
    val visible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("Receipt", onBack = onBack, trailing = { AmountVisibilityButton(visible, viewModel::toggleAmountsVisible) }) }
        receipt?.let { item { ReceiptDetails(it, visible) } } ?: item {
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            else CircularProgressIndicator()
        }
    }
}

@Composable
fun ReceiptDetails(receipt: HistoricalReceipt, visible: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transaction ID", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(receipt.transaction.receiptNumber, style = MaterialTheme.typography.titleMedium)
            Text(formatReceiptTime(receipt.transaction.occurredAt), style = MaterialTheme.typography.bodyMedium)
            Text(receipt.transaction.status.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Items", style = MaterialTheme.typography.titleLarge)
            if (receipt.items.isEmpty()) Text("No line items found for this transaction.")
            receipt.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.productName, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${formatQuantity(item.quantity)} × ${formatMoney(item.unitPriceCents, visible)}", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatMoney(item.lineTotalCents, visible), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReceiptAmount("Subtotal", receipt.transaction.subtotalCents, visible)
            ReceiptAmount("Total", receipt.transaction.totalCents, visible, true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            receipt.transaction.cashReceivedCents?.let { ReceiptAmount("Cash received", it, visible) }
            receipt.transaction.changeAmountCents?.let { ReceiptAmount("Change", it, visible, true) }
            Text(if (receipt.transaction.isSynced) "Synced" else "Pending sync", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            receipt.transaction.syncError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ReceiptAmount(label: String, cents: Long, visible: Boolean, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(formatMoney(cents, visible), style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
    }
}
