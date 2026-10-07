package com.icecreampost.pos.ui.screen.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*

@Composable
fun TransactionHistoryScreen(viewModel: PosViewModel, onReceipt: (String) -> Unit) {
    val allTransactions by viewModel.transactions.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val visible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    val transactions = allTransactions.filter { it.stallId == session?.stallId }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("History", trailing = { AmountVisibilityButton(visible, viewModel::toggleAmountsVisible) }) }
        if (transactions.isEmpty()) item { Text("No transactions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(transactions, key = { it.id }) { transaction ->
            Surface(onClick = { onReceipt(transaction.id) }, color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(formatMoney(transaction.totalCents, visible), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Text(transaction.status.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium,
                            color = if (transaction.status == "completed") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatReceiptTime(transaction.occurredAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(transaction.receiptNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when { transaction.isSynced -> "Synced"; transaction.syncError != null -> "Sync failed · Tap for details"; else -> "Pending sync" },
                        style = MaterialTheme.typography.bodySmall, color = if (transaction.syncError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
