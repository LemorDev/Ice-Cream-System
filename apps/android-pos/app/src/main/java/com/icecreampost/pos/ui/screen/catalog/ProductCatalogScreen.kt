package com.icecreampost.pos.ui.screen.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.domain.model.MenuAvailability
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.QuantityStepper
import com.icecreampost.pos.ui.component.ScreenHeader
import com.icecreampost.pos.ui.component.ServingFilter
import com.icecreampost.pos.ui.component.formatMoney
import com.icecreampost.pos.ui.screen.checkout.toCentsOrNull
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductCatalogScreen(
    viewModel: PosViewModel,
    onSaleComplete: () -> Unit,
    onOperatingDay: () -> Unit,
    focusMode: Boolean = false,
    onToggleFocusMode: () -> Unit = {},
) {
    val products by viewModel.filteredProducts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.category.collectAsStateWithLifecycle()
    val selectedServing by viewModel.serving.collectAsStateWithLifecycle()
    val cartLines by viewModel.cartLines.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val businessDay by viewModel.businessDay.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val isOpen = businessDay != null && businessDay?.closedAt == null && businessDay?.stallId == session?.stallId
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val cartQuantity = cartLines.sumOf { it.quantity }
    val cartTotal = cartLines.sumOf { it.lineTotalCents }
    var showOrderSheet by rememberSaveable { mutableStateOf(false) }
    var cashInput by rememberSaveable { mutableStateOf("") }
    var showCheckoutConfirmation by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    val cashCents = cashInput.toCentsOrNull()
    val changeCents = cashCents?.minus(cartTotal)?.takeIf { it >= 0 }

    LaunchedEffect(Unit) { viewModel.syncToIms() }
    BackHandler(enabled = focusMode && !showOrderSheet && !showCheckoutConfirmation && !showClearConfirmation) {
        onToggleFocusMode()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (cartLines.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                    Button(
                        onClick = { showOrderSheet = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Cart", modifier = Modifier.weight(1f))
                        Text("$cartQuantity ${if (cartQuantity == 1) "item" else "items"}  ·  ${formatMoney(cartTotal)}")
                        Spacer(Modifier.width(8.dp))
                        Text("↑")
                    }
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ScreenHeader(
                    title = "Sell",
                    subtitle = if (focusMode) "Focus mode" else null,
                    trailing = {
                        if (isOpen) {
                            TextButton(onClick = onToggleFocusMode) {
                                Text(if (focusMode) "Exit focus" else "Focus screen")
                            }
                        }
                    },
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("Search products") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = selectedCategory == null && selectedServing == null, onClick = { viewModel.setCategory(null) }, label = { Text("All") })
                ServingFilter.entries.forEach { serving ->
                    FilterChip(
                        selected = selectedServing == serving,
                        onClick = { viewModel.setServing(if (selectedServing == serving) null else serving) },
                        label = { Text(serving.label) },
                    )
                }
                categories.filterNot { it.equals("Cup", true) || it.equals("Cups", true) || it.equals("Cone", true) || it.equals("Cones", true) }.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { viewModel.setCategory(if (selectedCategory == category) null else category) },
                        label = { Text(category) },
                    )
                }
            }
            if (!isOpen) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Open the operating day to start a sale.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOperatingDay) { Text("Operating day") }
                }
            }
            com.icecreampost.pos.ui.component.SyncStatusLine(viewModel, Modifier.padding(horizontal = 16.dp))
            error?.let { ErrorMessage(it, Modifier.padding(horizontal = 16.dp)) }
            if (products.isEmpty()) {
                EmptyCatalog(query = query, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "${products.size} ${if (products.size == 1) "product" else "products"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(products, key = { it.id }) { product ->
                        val quantityInCart = cartLines.firstOrNull { it.product.id == product.id }?.quantity ?: 0
                        ProductRow(
                            product = product,
                            quantityInCart = quantityInCart,
                            onAdd = { viewModel.addToCart(product) },
                            onRemove = { viewModel.removeFromCart(product.id) },
                            availability = availability[product.id],
                            busy = busy || !isOpen,
                        )
                    }
                }
            }
        }
    }

    // Keep the order visible behind the confirmation. The sheet itself must
    // remain dismissible so Android Back can close it after the dialog closes.
    if (showOrderSheet && cartLines.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = {
                if (!busy && !showCheckoutConfirmation && !showClearConfirmation) showOrderSheet = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = null,
        ) {
            OrderAndPaymentSheet(
                lines = cartLines,
                total = cartTotal,
                cashInput = cashInput,
                onCashInputChange = { cashInput = sanitizeCashInput(it) },
                cashCents = cashCents,
                changeCents = changeCents,
                busy = busy,
                error = error,
                onDecrease = viewModel::removeFromCart,
                onIncrease = viewModel::addToCart,
                canIncrease = { product, _ -> isOpen && !busy && (availability[product.id]?.additionalPortions ?: 0) > 0 },
                onClear = { showClearConfirmation = true },
                onComplete = { if (isOpen) showCheckoutConfirmation = true },
                onBack = { if (!busy) showOrderSheet = false },
            )
        }
    }

    if (showCheckoutConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!busy) showCheckoutConfirmation = false },
            title = { Text("Complete this sale?") },
            text = {
                Text("Total ${formatMoney(cartTotal)} · Cash ${formatMoney(cashCents ?: 0)} · Change ${formatMoney(changeCents ?: 0)}")
            },
            dismissButton = {
                TextButton(onClick = { showCheckoutConfirmation = false }, enabled = !busy) { Text("Go back") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        cashCents?.let { received ->
                            viewModel.checkout(received) {
                                showCheckoutConfirmation = false
                                showOrderSheet = false
                                cashInput = ""
                                onSaleComplete()
                            }
                        }
                    },
                    enabled = isOpen && !busy && cashCents != null && cashCents >= cartTotal && cartLines.isNotEmpty(),
                ) { Text(if (busy) "Saving…" else "Confirm sale") }
            },
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear this order?") },
            text = { Text("All $cartQuantity items will be removed. This cannot be undone.") },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Keep order") } },
            confirmButton = {
                Button(colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                    viewModel.clearCart()
                    cashInput = ""
                    showClearConfirmation = false
                    showOrderSheet = false
                }) { Text("Clear order") }
            },
        )
    }
}

@Composable
private fun ProductRow(
    product: ProductEntity,
    quantityInCart: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    availability: MenuAvailability?,
    busy: Boolean,
) {
    val additionalAvailable = availability?.additionalPortions ?: 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${formatMoney(product.priceCents)} / ${product.unit}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        menuAvailabilityLabel(availability, product.unitsInStock),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (additionalAvailable == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (quantityInCart == 0) {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.semantics { contentDescription = "Add ${product.name}" },
                    enabled = !busy && additionalAvailable > 0,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) { Text("Add") }
            } else {
                QuantityStepper(
                    quantity = quantityInCart,
                    onDecrease = onRemove,
                    onIncrease = onAdd,
                    canIncrease = !busy && additionalAvailable > 0,
                    canDecrease = !busy,
                )
            }
        }
    }
}

internal fun menuAvailabilityLabel(availability: MenuAvailability?, ownStock: Double): String = when {
    availability == null -> "Checking stock…"
    availability.additionalPortions > 0 -> "In stock"
    availability.invalidRecipe -> "Sold out"
    availability.limitingIngredients.isNotEmpty() -> "Sold out · Need ${availability.limitingIngredients.joinToString()}"
    availability.limitedByCart -> "No more for this order"
    !availability.hasRecipe && ownStock <= 0 -> "Sold out"
    else -> "Sold out"
}

@Composable
private fun OrderAndPaymentSheet(
    lines: List<CartLine>,
    total: Long,
    cashInput: String,
    onCashInputChange: (String) -> Unit,
    cashCents: Long?,
    changeCents: Long?,
    busy: Boolean,
    error: String?,
    onDecrease: (String) -> Unit,
    onIncrease: (ProductEntity) -> Unit,
    canIncrease: (ProductEntity, Int) -> Boolean,
    onClear: () -> Unit,
    onComplete: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            Text("Payment", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClear, enabled = !busy) { Text("Clear", color = MaterialTheme.colorScheme.error) }
        }
        // Only cart lines scroll. Cash entry and checkout stay anchored below them.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(lines, key = { it.product.id }) { line ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(line.product.name, style = MaterialTheme.typography.titleSmall)
                            Text(formatMoney(line.lineTotalCents) + " · " + formatMoney(line.product.priceCents) + " each",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        QuantityStepper(line.quantity, { onDecrease(line.product.id) }, { onIncrease(line.product) },
                            canIncrease = canIncrease(line.product, line.quantity), canDecrease = !busy)
                    }
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total " + formatMoney(total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                when {
                    changeCents != null -> "Change " + formatMoney(changeCents)
                    cashCents != null -> "Due " + formatMoney(total - cashCents)
                    else -> "Change —"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (cashCents != null && cashCents < total) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cash received", style = MaterialTheme.typography.bodySmall)
                Text("₱" + cashInput.ifBlank { "0.00" }, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = { onCashInputChange(total.toPesoInput()) }, enabled = !busy) { Text("Exact amount") }
        }
        NumericCashKeypad(enabled = !busy, onKey = { onCashInputChange(updateCashInput(cashInput, it)) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onComplete, enabled = !busy && cashCents != null && cashCents >= total && lines.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
            Text(if (busy) "Saving…" else "Review sale")
        }
    }
}

@Composable
private fun NumericCashKeypad(enabled: Boolean, onKey: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { keys ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEach { key ->
                    OutlinedButton(
                        onClick = { onKey(key) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(key, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = if (key == "⌫") "Delete last digit" else if (key == ".") "Decimal point" else key })
                    }
                }
            }
        }
    }
}

internal fun updateCashInput(current: String, key: String): String = when (key) {
    "⌫" -> current.dropLast(1)
    "." -> when {
        current.contains('.') -> current
        current.isBlank() -> "0."
        else -> "$current."
    }
    else -> {
        if (key.length != 1 || !key[0].isDigit()) current
        else if (current.substringBefore('.').length >= 8 && !current.contains('.')) current
        else if (current.contains('.') && current.substringAfter('.').length >= 2) current
        else if (current == "0") key
        else current + key
    }
}

private fun Long.toPesoInput(): String = String.format(Locale.US, "%.2f", this / 100.0)

@Composable
private fun ErrorMessage(message: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun EmptyCatalog(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (query.isBlank()) "No products yet" else "No matches found", style = MaterialTheme.typography.titleLarge)
            Text(
                if (query.isBlank()) "Sync this device after products are added in the IMS."
                else "Try a different product name or choose another category.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun sanitizeCashInput(value: String): String =
    value.filter { it.isDigit() || it == '.' }.let { filtered ->
        val parts = filtered.split('.', limit = 2)
        if (parts.size == 2) "${parts[0]}.${parts[1].take(2)}" else parts[0]
    }
