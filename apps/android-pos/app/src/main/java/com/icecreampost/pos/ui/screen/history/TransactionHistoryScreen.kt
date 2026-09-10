package com.icecreampost.pos.ui.screen.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.AmountVisibilityButton
import com.icecreampost.pos.ui.component.formatMoney

@Composable
fun TransactionHistoryScreen(viewModel: PosViewModel, onBack: () -> Unit) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val amountsVisible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Transaction history", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            AmountVisibilityButton(visible = amountsVisible, onToggle = viewModel::toggleAmountsVisible)
        }
        if (transactions.isEmpty()) {
            Text("No local transactions yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(transactions, key = { it.id }) { transaction ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(transaction.receiptNumber, style = MaterialTheme.typography.titleMedium)
                            Text(formatMoney(transaction.totalCents, amountsVisible))
                            Text(if (transaction.isSynced) "Synced" else "Pending sync")
                            transaction.syncError?.let { Text("Sync error: $it", color = MaterialTheme.colorScheme.error) }
                            Text(transaction.status)
                        }
                    }
                }
            }
        }
    }
}
