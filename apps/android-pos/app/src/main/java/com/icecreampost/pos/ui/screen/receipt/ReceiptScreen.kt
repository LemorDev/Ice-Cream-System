package com.icecreampost.pos.ui.screen.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.formatMoney

@Composable
fun ReceiptScreen(viewModel: PosViewModel, onDone: () -> Unit) {
    val receipt by viewModel.receipt.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sale complete", style = MaterialTheme.typography.headlineMedium)
        receipt?.let {
            Text("Receipt: ${it.receiptNumber}")
            Text("Total: ${formatMoney(it.subtotalCents)}")
            Text("Cash: ${formatMoney(it.cashReceivedCents)}")
            Text("Change: ${formatMoney(it.changeAmountCents)}")
            Text("Saved locally. It will sync when a connection is available.")
        } ?: Text("Receipt is no longer available.")
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Start another sale") }
    }
}
