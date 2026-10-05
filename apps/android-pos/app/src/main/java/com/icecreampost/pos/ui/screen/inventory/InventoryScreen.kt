package com.icecreampost.pos.ui.screen.inventory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.data.repository.receivedQuantityOrNull
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*

@Composable
fun InventoryScreen(viewModel: PosViewModel) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val lowStock by viewModel.lowStockProducts.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var success by rememberSaveable { mutableStateOf<String?>(null) }
    var showReceiptConfirmation by rememberSaveable { mutableStateOf(false) }
    var selectingToReceive by rememberSaveable { mutableStateOf(false) }
    val stockItems = products.filter { it.stallId == session?.stallId && it.deletedAt == null && it.productType in listOf("raw", "packaging") }
    val selected = stockItems.firstOrNull { it.id == selectedId }
    if (selected != null && showReceiptConfirmation) {
        val parsed = receivedQuantityOrNull(quantity)
        AlertDialog(
            onDismissRequest = { if (!busy) showReceiptConfirmation = false },
            title = { Text("Confirm stock receipt") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Item: ${selected.name}")
                    Text("Quantity received: ${formatStock(parsed ?: 0.0, selected.baseUnit)}")
                    Text("Current stock: ${formatStock(selected.unitsInStock, selected.baseUnit)}")
                    parsed?.let { Text("Stock after receiving: ${formatStock(selected.unitsInStock + it, selected.baseUnit)}") }
                    if (notes.isNotBlank()) Text("Delivery reference / notes: ${notes.trim()}")
                    Text("Confirm that this delivery has arrived and the quantity is correct. Saving will add it to your stock.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !busy && parsed != null, onClick = {
                    viewModel.receiveStock(selected.id, parsed!!, notes) {
                        success = "Received ${formatStock(parsed ?: 0.0, selected.baseUnit)} of ${selected.name}. Saved on this device. Sync runs automatically."
                        selectedId = null
                        showReceiptConfirmation = false
                        selectingToReceive = false
                    }
                }) { Text(if (busy) "Saving…" else "Confirm and receive") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showReceiptConfirmation = false }, enabled = !busy) { Text("Go back") }
            },
        )
    } else if (selected != null) {
        val parsed = receivedQuantityOrNull(quantity)
        AlertDialog(
            onDismissRequest = { if (!busy) selectedId = null },
            title = { Text("Receive ${selected.name}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Current stock: ${formatStock(selected.unitsInStock, selected.baseUnit)}")
                    Text("Enter the quantity in ${displayUnit(selected.baseUnit)}, not packs.")
                    OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity (${displayUnit(selected.baseUnit)})") },
                        enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = quantity.isNotBlank() && parsed == null,
                        supportingText = { Text("Greater than zero; up to 3 decimal places.") })
                    OutlinedTextField(notes, { notes = it.take(500) }, enabled = !busy, label = { Text("Delivery reference / notes (optional)") })
                    parsed?.let { Text("Stock after receiving: ${formatStock(selected.unitsInStock + it, selected.baseUnit)}") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !busy && parsed != null, onClick = {
                    viewModel.dismissError()
                    showReceiptConfirmation = true
                }) { Text("Review receipt") }
            },
            dismissButton = { OutlinedButton(onClick = { selectedId = null }, enabled = !busy) { Text("Cancel") } },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Inventory") }
        item {
            if (selectingToReceive) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Select an item to receive", modifier = Modifier.weight(1f))
                    TextButton(onClick = { selectingToReceive = false }) { Text("Cancel") }
                }
            } else {
                Button(onClick = { selectingToReceive = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Receive Stock") }
            }
        }
        success?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) } }
        item { SyncStatusLine(viewModel) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { OutlinedTextField(search, { search = it }, label = { Text("Search stock items") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        val visible = stockItems.filter { it.name.contains(search.trim(), true) || it.sku.contains(search.trim(), true) }.sortedBy { it.name }
        if (visible.isEmpty()) item { Text("No stock items found. Try another search or sync the catalog from More.") }
        items(visible, key = { it.id }) { product ->
            Surface(onClick = {
                selectedId = product.id; quantity = ""; notes = ""; success = null
                showReceiptConfirmation = false; viewModel.dismissError()
            }, enabled = !busy, color = MaterialTheme.colorScheme.background) {
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        if (lowStock.any { it.id == product.id }) Text("Low stock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(formatStock(product.unitsInStock, product.baseUnit), style = MaterialTheme.typography.bodyLarge)
                    if (selectingToReceive) Text("  ›", color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
