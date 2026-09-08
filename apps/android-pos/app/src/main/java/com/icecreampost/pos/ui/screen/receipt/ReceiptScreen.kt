package com.icecreampost.pos.ui.screen.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.formatMoney

@Composable
fun ReceiptScreen(viewModel: PosViewModel, onDone: () -> Unit) {
    val receipt by viewModel.receipt.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Start another order", modifier = Modifier.weight(1f))
                Text("→")
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✓", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Sale complete", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                "The order was saved on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            receipt?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(it.receiptNumber, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        ReceiptRow("Total", formatMoney(it.subtotalCents), emphasized = true)
                        Surface(modifier = Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
                        ReceiptRow("Cash received", formatMoney(it.cashReceivedCents))
                        ReceiptRow("Change", formatMoney(it.changeAmountCents))
                    }
                }
            } ?: Text("Receipt details are no longer available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Text(
                    "Offline-ready · This sale will sync automatically when a connection is available.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String, emphasized: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
    }
}
