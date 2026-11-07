package com.icecreampost.pos.ui.screen.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.repository.receivedQuantityOrNull
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*

private enum class StockFilter(val title: String, val productType: String?) {
    All("All items", null),
    Packaging("Packaging", "packaging"),
    Ingredients("Ingredients", "raw"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(viewModel: PosViewModel) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val lowStock by viewModel.lowStockProducts.collectAsStateWithLifecycle()
    val sealedPacks by viewModel.sealedPacks.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StockFilter.All) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var success by rememberSaveable { mutableStateOf<String?>(null) }
    var showReceiptConfirmation by rememberSaveable { mutableStateOf(false) }
    var selectingToReceive by rememberSaveable { mutableStateOf(false) }
    var openProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var packsToOpen by rememberSaveable { mutableStateOf("1") }
    val stockItems = products.filter { it.stallId == session?.stallId && it.deletedAt == null && it.productType in listOf("raw", "packaging") }
    val lowStockIds = lowStock.mapTo(mutableSetOf()) { it.id }
    val query = search.trim()
    val visible = stockItems.filter {
        (filter.productType == null || filter.productType == it.productType) &&
            (it.name.contains(query, ignoreCase = true) || it.sku.contains(query, ignoreCase = true))
    }.sortedBy { it.name }
    val selected = stockItems.firstOrNull { it.id == selectedId }
    val openProduct = stockItems.firstOrNull { it.id == openProductId && it.tracksPowderPacks }
    if (openProduct != null) {
        val available = sealedPacks[openProduct.id] ?: 0
        val unitsPerPack = openProduct.packSize * openProduct.conversionRate
        val parsed = packsToOpen.toIntOrNull()?.takeIf { it in 1..minOf(available, 1000) && it * unitsPerPack <= openProduct.unitsInStock }
        AlertDialog(
            onDismissRequest = { if (!busy) openProductId = null },
            title = { Text("Open Powder Pack") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${openProduct.name}: $available sealed packs available.")
                    OutlinedTextField(packsToOpen, { packsToOpen = it }, label = { Text("Packs to open") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = packsToOpen.isNotBlank() && parsed == null,
                        supportingText = { Text("Each opening deducts one pack from inventory.") })
                    parsed?.let { Text("Stock after opening: ${formatStock(openProduct.unitsInStock - it * unitsPerPack, openProduct.baseUnit)}") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(enabled = !busy && parsed != null, onClick = {
                viewModel.openPowderPacks(openProduct.id, parsed!!) {
                    success = "Opened $parsed pack(s) of ${openProduct.name}. Saved on this device."
                    openProductId = null
                }
            }) { Text(if (busy) "Saving…" else "Record opening") } },
            dismissButton = { OutlinedButton(enabled = !busy, onClick = { openProductId = null }) { Text("Cancel") } },
        )
    }
    if (selected != null && showReceiptConfirmation) {
        val parsed = if (selected.tracksPowderPacks) receivedQuantityOrNull(quantity)?.takeIf { it % 1.0 == 0.0 }?.times(selected.packSize * selected.conversionRate) else receivedQuantityOrNull(quantity)
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
        val parsed = if (selected.tracksPowderPacks) receivedQuantityOrNull(quantity)?.takeIf { it % 1.0 == 0.0 }?.times(selected.packSize * selected.conversionRate) else receivedQuantityOrNull(quantity)
        AlertDialog(
            onDismissRequest = { if (!busy) selectedId = null },
            title = { Text("Receive ${selected.name}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Current stock: ${formatStock(selected.unitsInStock, selected.baseUnit)}")
                    Text(if (selected.tracksPowderPacks) "Enter the number of sealed packs received." else "Enter the quantity in ${displayUnit(selected.baseUnit)}, not packs.")
                    OutlinedTextField(quantity, { quantity = it }, label = { Text(if (selected.tracksPowderPacks) "Sealed packs" else "Quantity (${displayUnit(selected.baseUnit)})") },
                        enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = quantity.isNotBlank() && parsed == null,
                        supportingText = { Text(if (selected.tracksPowderPacks) "Whole packs greater than zero." else "Greater than zero; up to 3 decimal places.") })
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
        item {
            ScreenHeader("Inventory")
            SyncStatusLine(viewModel, Modifier.padding(top = 4.dp), showStatusIndicator = true)
        }
        item {
            if (selectingToReceive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Select an item to receive", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { selectingToReceive = false }) { Text("Cancel") }
                }
            } else {
                Button(onClick = { selectingToReceive = true }, enabled = !busy && stockItems.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                    PosIcon(PosSymbol.Add)
                    Spacer(Modifier.width(8.dp))
                    Text("Receive Stock", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        success?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search stock items") },
                leadingIcon = { PosIcon(PosSymbol.Search) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StockFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(if (option == StockFilter.All) "${option.title} (${stockItems.size})" else option.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        if (visible.isEmpty()) item {
            Text(
                if (stockItems.isEmpty()) "No stock items yet. Sync the catalog from More."
                else "No matching stock items. Try another search or filter.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(visible, key = { it.id }) { product ->
            val sealed = sealedPacks[product.id] ?: 0
            StockItemCard(
                product = product,
                lowStock = product.id in lowStockIds,
                sealed = sealed,
                busy = busy,
                onReceive = {
                    selectedId = product.id
                    quantity = ""
                    notes = ""
                    success = null
                    showReceiptConfirmation = false
                    viewModel.dismissError()
                },
                onOpenPack = {
                    openProductId = product.id
                    packsToOpen = "1"
                    viewModel.dismissError()
                },
            )
        }
    }
}

@Composable
private fun StockItemCard(
    product: ProductEntity,
    lowStock: Boolean,
    sealed: Int,
    busy: Boolean,
    onReceive: () -> Unit,
    onOpenPack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val packaging = product.productType == "packaging"
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Surface(onClick = onReceive, enabled = !busy, color = Color.Transparent) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (packaging) colors.primaryContainer else colors.secondaryContainer,
                        contentColor = if (packaging) colors.onPrimaryContainer else colors.onSecondaryContainer,
                    ) {
                        PosIcon(if (packaging) PosSymbol.Packaging else PosSymbol.Ingredient, Modifier.padding(8.dp))
                    }
                    Column(Modifier.weight(1.35f)) {
                        Text(product.name, style = MaterialTheme.typography.titleSmall)
                        if (product.sku.isNotBlank()) Text("SKU: ${product.sku}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (product.tracksPowderPacks) Text("$sealed sealed packs",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(formatStock(product.unitsInStock, product.baseUnit),
                            style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        StockStatusBadge(product.unitsInStock <= 0.0, lowStock)
                    }
                }
            }
            if (product.tracksPowderPacks) {
                TextButton(onClick = onOpenPack, enabled = !busy && sealed > 0, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Open Powder Pack")
                }
            }
        }
    }
}

@Composable
private fun StockStatusBadge(outOfStock: Boolean, lowStock: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (label, background, foreground) = when {
        outOfStock -> Triple("Out of stock", colors.errorContainer, colors.onErrorContainer)
        lowStock -> Triple("Low stock", colors.tertiaryContainer, colors.onTertiaryContainer)
        else -> Triple("In stock", colors.secondaryContainer, colors.onSecondaryContainer)
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = background, contentColor = foreground) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}
