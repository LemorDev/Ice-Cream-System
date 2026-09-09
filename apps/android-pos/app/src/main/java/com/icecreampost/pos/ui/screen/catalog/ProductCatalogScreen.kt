package com.icecreampost.pos.ui.screen.catalog

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.QuantityStepper
import com.icecreampost.pos.ui.component.ScreenHeader
import com.icecreampost.pos.ui.component.formatMoney
import com.icecreampost.pos.ui.screen.checkout.toCentsOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductCatalogScreen(viewModel: PosViewModel, onSaleComplete: () -> Unit, onBack: () -> Unit) {
    val products by viewModel.filteredProducts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.category.collectAsStateWithLifecycle()
    val cartLines by viewModel.cartLines.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val cartQuantity = cartLines.sumOf { it.quantity }
    val cartTotal = cartLines.sumOf { it.lineTotalCents }
    var showOrderSheet by rememberSaveable { mutableStateOf(false) }
    var cashInput by rememberSaveable { mutableStateOf("") }
    var showCheckoutConfirmation by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    val cashCents = cashInput.toCentsOrNull()
    val changeCents = cashCents?.minus(cartTotal)?.takeIf { it >= 0 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (cartLines.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                    Button(
                        onClick = { showOrderSheet = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Order & payment", modifier = Modifier.weight(1f))
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                ScreenHeader(
                    title = "New order",
                    subtitle = "Coolerz Ice Cream",
                    onBack = onBack,
                    trailing = {
                        if (cartQuantity > 0) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    "$cartQuantity in cart",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Search menu") },
                leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = selectedCategory == null, onClick = { viewModel.setCategory(null) }, label = { Text("All") })
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { viewModel.setCategory(if (selectedCategory == category) null else category) },
                        label = { Text(category) },
                    )
                }
            }
            error?.let { ErrorMessage(it, Modifier.padding(horizontal = 20.dp)) }
            if (products.isEmpty()) {
                EmptyCatalog(query = query, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        )
                    }
                }
            }
        }
    }

    if (showOrderSheet && cartLines.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { if (!busy) showOrderSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
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
                onClear = { showClearConfirmation = true },
                onComplete = { showCheckoutConfirmation = true },
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
                    enabled = !busy && cashCents != null && cashCents >= cartTotal,
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
                Button(onClick = {
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
) {
    val remainingStock = product.unitsInStock - quantityInCart
    val lowStock = remainingStock <= product.lowStockThreshold
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${product.category} · per ${product.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatMoney(product.priceCents), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("  ·  ", color = MaterialTheme.colorScheme.outline)
                    Text(
                        "$remainingStock left",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (quantityInCart == 0) {
                Button(
                    onClick = onAdd,
                    enabled = product.unitsInStock > 0,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) { Text("Add") }
            } else {
                QuantityStepper(
                    quantity = quantityInCart,
                    onDecrease = onRemove,
                    onIncrease = onAdd,
                    canIncrease = quantityInCart < product.unitsInStock,
                )
            }
        }
    }
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
    onClear: () -> Unit,
    onComplete: () -> Unit,
) {
    val quantity = lines.sumOf { it.quantity }
    val listState = rememberLazyListState()
    val cashFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(lines.size) {
        listState.scrollToItem(lines.size + 2)
        withFrameNanos { }
        cashFocusRequester.requestFocus()
        keyboardController?.show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp).imePadding(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Order details", style = MaterialTheme.typography.headlineSmall)
                    Text("$quantity ${if (quantity == 1) "item" else "items"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClear, enabled = !busy) { Text("Clear order", color = MaterialTheme.colorScheme.error) }
            }
        }
        items(lines, key = { it.product.id }) { line ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(line.product.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${formatMoney(line.product.priceCents)} each · ${line.product.unitsInStock - line.quantity} left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(formatMoney(line.lineTotalCents), style = MaterialTheme.typography.titleMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        QuantityStepper(
                            quantity = line.quantity,
                            onDecrease = { onDecrease(line.product.id) },
                            onIncrease = { onIncrease(line.product) },
                            canIncrease = line.quantity < line.product.unitsInStock,
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryRow("Subtotal", formatMoney(total))
                    Surface(modifier = Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
                    SummaryRow("Total", formatMoney(total), emphasized = true)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cash received", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = cashInput,
                    onValueChange = onCashInputChange,
                    prefix = { Text("₱") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth().focusRequester(cashFocusRequester),
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }
        if (cashCents != null && cashCents < total) {
            item { Text("Still due ${formatMoney(total - cashCents)}", color = MaterialTheme.colorScheme.error) }
        }
        changeCents?.let { change ->
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Change", style = MaterialTheme.typography.titleMedium)
                        Text(formatMoney(change), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        error?.let { item { ErrorMessage(it) } }
        item {
            Button(
                onClick = onComplete,
                enabled = !busy && cashCents != null && cashCents >= total,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (busy) "Saving…" else "Confirm sale") }
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
