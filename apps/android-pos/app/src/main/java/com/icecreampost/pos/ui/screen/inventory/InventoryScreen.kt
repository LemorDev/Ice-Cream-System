package com.icecreampost.pos.ui.screen.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.repository.receivedQuantityOrNull
import com.icecreampost.pos.domain.model.CLOSING_STOCK_COUNT
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*
import java.time.LocalDate
import java.time.ZoneId

private enum class StockFilter(val title: String, val productType: String?) {
    All("All items", null),
    Packaging("Packaging", "packaging"),
    Ingredients("Ingredients", "raw"),
}

private enum class InventoryView(val label: String) { Stock("Stock levels"), Daily("Daily inventory") }
private enum class InventoryEntryAction(val title: String) { Receive("Select stock to receive"), OpenPowder("Select powder") }

@Composable
fun InventoryScreen(viewModel: PosViewModel, onCloseDay: () -> Unit) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val businessDay by viewModel.businessDay.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val lowStock by viewModel.lowStockProducts.collectAsStateWithLifecycle()
    val sealedPacks by viewModel.sealedPacks.collectAsStateWithLifecycle()
    var inventoryView by rememberSaveable { mutableStateOf(InventoryView.Stock) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StockFilter.All) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var success by rememberSaveable { mutableStateOf<String?>(null) }
    var showReceiptConfirmation by rememberSaveable { mutableStateOf(false) }
    var entryAction by rememberSaveable { mutableStateOf<InventoryEntryAction?>(null) }
    var openProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var packsToOpen by rememberSaveable { mutableStateOf("1") }
    val stockItems = products.filter { it.stallId == session?.stallId && it.deletedAt == null && it.productType in listOf("raw", "packaging") }
    val powderProducts = stockItems.filter { it.tracksPowderPacks }
    val powderAvailable = powderProducts.any { openablePacks(it, sealedPacks[it.id] ?: 0) > 0 }
    val today = LocalDate.now(ZoneId.of("Asia/Manila"))
    val inventoryDay = businessDay?.takeIf {
        it.stallId == session?.stallId && (it.closedAt == null || it.businessDate == today.toString())
    }
    val activeDay = inventoryDay?.takeIf { it.closedAt == null }
    val canRecord = activeDay != null && activeDay.deviceId == session?.deviceId && session?.transferReady == false
    val date = inventoryDay?.let { LocalDate.parse(it.businessDate) } ?: today
    val stallId = session?.stallId
    val dailyEntries = if (inventoryView == InventoryView.Daily && stallId != null) {
        val entries by remember(stallId, inventoryDay?.id, date) {
            viewModel.observeDailyStockUpdates(stallId, inventoryDay?.id, date)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        entries
    } else emptyList()
    val productsById = remember(products) { products.associateBy { it.id } }
    val lowStockIds = lowStock.mapTo(mutableSetOf()) { it.id }
    val query = search.trim()
    val visible = stockItems.filter {
        (filter.productType == null || filter.productType == it.productType) &&
            (it.name.contains(query, ignoreCase = true) || it.sku.contains(query, ignoreCase = true))
    }.sortedBy { it.name }
    val selected = stockItems.firstOrNull { it.id == selectedId }
    val openProduct = stockItems.firstOrNull { it.id == openProductId && it.tracksPowderPacks }

    fun selectStock(product: ProductEntity) {
        selectedId = product.id
        quantity = ""
        notes = ""
        success = null
        showReceiptConfirmation = false
        viewModel.dismissError()
    }

    fun selectPowder(product: ProductEntity) {
        openProductId = product.id
        packsToOpen = "1"
        success = null
        viewModel.dismissError()
    }

    entryAction?.let { action ->
        StockItemPicker(
            action = action,
            products = if (action == InventoryEntryAction.Receive) stockItems else powderProducts,
            sealedPacks = sealedPacks,
            enabled = !busy && canRecord,
            onDismiss = { entryAction = null },
            onSelect = { product ->
                entryAction = null
                if (action == InventoryEntryAction.Receive) selectStock(product) else selectPowder(product)
            },
        )
    }
    if (openProduct != null) {
        val sealed = sealedPacks[openProduct.id] ?: 0
        val available = openablePacks(openProduct, sealed)
        val unitsPerPack = openProduct.packSize * openProduct.conversionRate
        val parsed = packsToOpen.toIntOrNull()?.takeIf { it in 1..available }
        AlertDialog(
            onDismissRequest = { if (!busy) openProductId = null },
            title = { Text("Record powder opening") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(openProduct.name, style = MaterialTheme.typography.titleMedium)
                    Text("$sealed sealed packs in stock")
                    OutlinedTextField(packsToOpen, { packsToOpen = it }, label = { Text("Packs to open") },
                        enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = packsToOpen.isNotBlank() && parsed == null,
                        supportingText = { Text(if (available > 0) "Enter a whole number up to $available." else "No sealed packs are available to open.") })
                    parsed?.let { Text("${sealed - it} sealed packs after opening · ${formatStock(openProduct.unitsInStock - it * unitsPerPack, openProduct.baseUnit)}") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(enabled = !busy && canRecord && parsed != null, onClick = {
                viewModel.openPowderPacks(openProduct.id, parsed!!) {
                    success = "Recorded $parsed ${if (parsed == 1) "pack" else "packs"} opened for ${openProduct.name}."
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
                Button(enabled = !busy && canRecord && parsed != null, onClick = {
                    viewModel.receiveStock(selected.id, parsed!!, notes) {
                        success = "Received ${formatStock(parsed ?: 0.0, selected.baseUnit)} of ${selected.name}. Saved on this device. Sync runs automatically."
                        selectedId = null
                        showReceiptConfirmation = false
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
                Button(enabled = !busy && canRecord && parsed != null, onClick = {
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                InventoryView.entries.forEachIndexed { index, view ->
                    SegmentedButton(
                        selected = inventoryView == view,
                        onClick = { inventoryView = view },
                        shape = SegmentedButtonDefaults.itemShape(index, InventoryView.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(view.label) }
                }
            }
        }
        if (inventoryView == InventoryView.Daily) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatBusinessDate(date.toString()), style = MaterialTheme.typography.titleMedium)
                if (canRecord) {
                    Button(onClick = onCloseDay, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Count stock and close") }
                }
                val hint = when {
                    !canRecord -> "Open the operating day on this POS to record stock updates."
                    powderProducts.isEmpty() -> "Enable powder-pack tracking for the product in IMS to record openings."
                    !powderAvailable -> "Receive sealed powder packs to record an opening."
                    else -> null
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = { entryAction = InventoryEntryAction.OpenPowder }, enabled = !busy && canRecord && powderAvailable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Record powder opening") }
            }
        }
        item {
            OutlinedButton(onClick = { entryAction = InventoryEntryAction.Receive },
                enabled = !busy && canRecord && stockItems.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                PosIcon(PosSymbol.Add)
                Spacer(Modifier.width(8.dp))
                Text("Receive stock")
            }
            if (inventoryView == InventoryView.Stock && !canRecord) Text("Open the operating day to record stock updates.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        success?.let { item { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (inventoryView == InventoryView.Daily) {
            if (dailyEntries.isEmpty()) item { Text("No stock updates for this operating day.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(dailyEntries, key = { it.id }) { entry ->
                DailyInventoryEntry(entry, productsById[entry.productId])
            }
            return@LazyColumn
        }
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
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StockFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(option.title, maxLines = 1, softWrap = false) },
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
                canRecord = !busy && canRecord,
                onReceive = { selectStock(product) },
                onOpenPack = { selectPowder(product) },
            )
        }
    }
}

private fun openablePacks(product: ProductEntity, sealed: Int): Int {
    val unitsPerPack = product.packSize * product.conversionRate
    if (unitsPerPack <= 0) return 0
    return minOf(sealed, 1000, (product.unitsInStock / unitsPerPack).toInt()).coerceAtLeast(0)
}

@Composable
private fun StockItemPicker(
    action: InventoryEntryAction,
    products: List<ProductEntity>,
    sealedPacks: Map<String, Int>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ProductEntity) -> Unit,
) {
    val opening = action == InventoryEntryAction.OpenPowder
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(products.sortedBy { it.name }, key = { it.id }) { product ->
                    val sealed = sealedPacks[product.id] ?: 0
                    OutlinedButton(onClick = { onSelect(product) }, enabled = enabled && (!opening || openablePacks(product, sealed) > 0),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(product.name)
                            if (opening) Text("$sealed sealed packs", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DailyInventoryEntry(entry: InventoryLedgerEntity, product: ProductEntity?) {
    val opening = entry.movementType == "pack_unsealed"
    val closingCount = entry.movementType == CLOSING_STOCK_COUNT
    val packs = kotlin.math.abs(entry.sealedPacksDelta)
    val quantity = if (packs > 0) "$packs ${if (packs == 1) "pack" else "packs"}"
        else formatStock(kotlin.math.abs(entry.quantityDelta), product?.baseUnit.orEmpty())
    val syncLabel = when {
        entry.isSynced -> "Synced"
        entry.syncError != null -> "Sync needs attention"
        else -> "Pending sync"
    }
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(product?.name ?: "Archived stock item", style = MaterialTheme.typography.titleSmall)
            Text(when {
                closingCount -> "Counted ${formatStock(requireNotNull(entry.countedQuantity), requireNotNull(entry.countedUnit))} at closing"
                opening -> "Opened $quantity"
                else -> "Received $quantity"
            })
            if (!opening && !closingCount) entry.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("${formatReceiptTime(entry.occurredAt)} · $syncLabel", style = MaterialTheme.typography.bodySmall,
                color = if (!entry.isSynced && entry.syncError != null) colors.error else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun StockItemCard(
    product: ProductEntity,
    lowStock: Boolean,
    sealed: Int,
    canRecord: Boolean,
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
            Surface(onClick = onReceive, enabled = canRecord, color = Color.Transparent) {
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
                TextButton(onClick = onOpenPack, enabled = canRecord && openablePacks(product, sealed) > 0,
                    modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) {
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
