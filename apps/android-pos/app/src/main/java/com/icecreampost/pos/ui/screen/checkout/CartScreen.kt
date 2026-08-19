package com.icecreampost.pos.ui.screen.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.formatMoney

@Composable
fun CartScreen(viewModel: PosViewModel, onPayment: () -> Unit, onBack: () -> Unit) {
    val lines by viewModel.cartLines.collectAsStateWithLifecycle()
    val total = lines.sumOf { it.lineTotalCents }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back to catalog") }
        Text("Cart", style = MaterialTheme.typography.headlineMedium)
        if (lines.isEmpty()) {
            Text("Your cart is empty.")
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines, key = { it.product.id }) { line ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(line.product.name)
                                Text("${line.quantity} × ${formatMoney(line.product.priceCents)}")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.removeFromCart(line.product.id) }) { Text("−") }
                                Button(onClick = { viewModel.addToCart(line.product) }) { Text("+") }
                            }
                        }
                    }
                }
            }
            Text("Total: ${formatMoney(total)}", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onPayment, modifier = Modifier.fillMaxWidth()) { Text("Continue to payment") }
        }
    }
}
