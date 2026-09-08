package com.icecreampost.pos.ui.screen.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.QuantityStepper
import com.icecreampost.pos.ui.component.ScreenHeader
import com.icecreampost.pos.ui.component.formatMoney

@Composable
fun CartScreen(viewModel: PosViewModel, onPayment: () -> Unit, onBack: () -> Unit) {
    val lines by viewModel.cartLines.collectAsStateWithLifecycle()
    val total = lines.sumOf { it.lineTotalCents }
    val quantity = lines.sumOf { it.quantity }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (lines.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total", style = MaterialTheme.typography.titleMedium)
                            Text(formatMoney(total), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Button(onClick = onPayment, modifier = Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.medium) {
                            Text("Continue to payment", modifier = Modifier.weight(1f))
                            Text("→")
                        }
                    }
                }
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Review order",
                    subtitle = "$quantity ${if (quantity == 1) "item" else "items"}",
                    onBack = onBack,
                )
            }
            if (lines.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxHeight(0.72f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Your order is empty", style = MaterialTheme.typography.titleLarge)
                            Text("Go back to the menu to add a product.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = onBack) { Text("Browse menu") }
                        }
                    }
                }
            } else {
                item { Text("Items", style = MaterialTheme.typography.titleMedium) }
                items(lines, key = { it.product.id }) { line ->
                    OrderLine(
                        line = line,
                        onDecrease = { viewModel.removeFromCart(line.product.id) },
                        onIncrease = { viewModel.addToCart(line.product) },
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Order details", style = MaterialTheme.typography.titleMedium)
                            SummaryRow("Subtotal", formatMoney(total))
                            Surface(modifier = Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
                            SummaryRow("Total", formatMoney(total), emphasized = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderLine(line: CartLine, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(line.product.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(line.product.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMoney(line.product.priceCents), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(formatMoney(line.lineTotalCents), style = MaterialTheme.typography.titleMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                QuantityStepper(
                    quantity = line.quantity,
                    onDecrease = onDecrease,
                    onIncrease = onIncrease,
                    canIncrease = line.quantity < line.product.unitsInStock,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, emphasized: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
