package com.icecreampost.pos.ui.screen.operations

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.domain.model.ClosingStockCount
import com.icecreampost.pos.domain.model.closingCountUnit
import com.icecreampost.pos.domain.model.parseClosingCount
import com.icecreampost.pos.ui.component.displayUnit
import com.icecreampost.pos.ui.component.formatMoney
import com.icecreampost.pos.ui.component.formatQuantity
import com.icecreampost.pos.ui.component.formatStock
import com.icecreampost.pos.ui.screen.checkout.toCentsOrNull
import kotlin.math.round

private val countDraftSaver = Saver<Map<String, String>, Bundle>(
    save = { counts -> Bundle().apply { counts.forEach { (id, count) -> putString(id, count) } } },
    restore = { values -> values.keySet().associateWith { values.getString(it).orEmpty() } },
)

@Composable
internal fun ClosingDayDialog(
    businessDayId: String,
    stockItems: List<ProductEntity>,
    sealedPacks: Map<String, Int>,
    catalogReady: Boolean,
    expectedCash: Long,
    amountsVisible: Boolean,
    busy: Boolean,
    syncing: Boolean,
    error: String?,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, Long, Map<String, ClosingStockCount>) -> Unit,
) {
    var notes by rememberSaveable(businessDayId) { mutableStateOf("") }
    var cash by rememberSaveable(businessDayId) { mutableStateOf("") }
    var counts by rememberSaveable(businessDayId, stateSaver = countDraftSaver) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var reviewing by rememberSaveable(businessDayId) { mutableStateOf(false) }
    val countedCash = cash.toCentsOrNull()
    val cashError = cash.isNotBlank() && countedCash == null
    val closingCash = countedCash ?: expectedCash.coerceAtLeast(0)
    val validCounts = stockItems.mapNotNull { product ->
        val value = counts[countDraftKey(product)].orEmpty()
        product.parseClosingCount(value)?.let { product.id to it }
    }.toMap()
    val readyToReview = catalogReady && !cashError && validCounts.size == stockItems.size && !syncing
    val scroll = rememberScrollState()

    AlertDialog(
        onDismissRequest = { if (!busy) { if (reviewing) reviewing = false else onDismiss() } },
        title = { Text(if (reviewing) "Review closing" else "Close operating day") },
        text = {
            // Each step keeps its own scroll position and the draft survives rotation.
            androidx.compose.runtime.key(reviewing) {
                Column(Modifier.verticalScroll(if (reviewing) rememberScrollState() else scroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (reviewing) {
                        Text("Closing cash · ${formatMoney(closingCash, amountsVisible)}")
                        stockItems.forEach { product ->
                            val count = validCounts[product.id] ?: return@forEach
                            val recorded = recordedCount(product, sealedPacks)
                            val difference = round((count.quantity - recorded) * 1000) / 1000
                            val variance = when {
                                difference > 0 -> "+${formatQuantity(difference)}"
                                difference < 0 -> formatQuantity(difference)
                                else -> "Matches recorded stock"
                            }
                            Column {
                                Text(product.name, style = MaterialTheme.typography.titleSmall)
                                Text("${formatStock(count.quantity, count.unit)} counted · $variance",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (!readyToReview && !syncing) Text("Review the updated stock list before closing.")
                        Text("Confirm to save the counts and close the stall.")
                    } else {
                        if (!catalogReady) {
                            Text("Sync the product catalog to load the closing stock list.")
                            OutlinedButton(onClick = onSync, enabled = !busy && !syncing) {
                                Text(if (syncing) "Syncing…" else "Sync now")
                            }
                        } else if (stockItems.isNotEmpty()) {
                            Text("Closing stock count", style = MaterialTheme.typography.titleMedium)
                            Text("Count what is left in the shop. Enter 0 for an empty item.")
                            stockItems.forEach { product ->
                                val key = countDraftKey(product)
                                val value = counts[key].orEmpty()
                                val unit = product.closingCountUnit
                                val whole = unit == "piece" || unit == "pack"
                                val invalid = value.isNotBlank() && product.id !in validCounts
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { if (it.length <= 16) counts = counts + (key to it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("${product.name} (${if (unit == "pack") "sealed packs" else displayUnit(unit)})") },
                                    supportingText = { Text(if (invalid) {
                                        if (whole) "Enter a whole number of 0 or more." else "Enter 0 or more with up to 3 decimal places."
                                    } else "Recorded stock · ${formatStock(recordedCount(product, sealedPacks), unit)}") },
                                    keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal,
                                        imeAction = ImeAction.Next),
                                    singleLine = true,
                                    enabled = !busy,
                                    isError = invalid,
                                )
                            }
                        }
                        Text("Expected cash · ${formatMoney(expectedCash, amountsVisible)}")
                        OutlinedTextField(
                            cash, { cash = it.filter { char -> char.isDigit() || char == '.' } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Closing cash counted") },
                            placeholder = { Text(formatMoney(expectedCash.coerceAtLeast(0), amountsVisible)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            isError = cashError,
                            supportingText = if (cashError) {{ Text("Enter a valid cash amount.") }} else null,
                            singleLine = true, enabled = !busy,
                        )
                        OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Closing notes (optional)") }, enabled = !busy)
                        if (catalogReady && stockItems.isNotEmpty()) {
                            Text("${validCounts.size} of ${stockItems.size} items counted")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (reviewing) {
                Button(onClick = { onConfirm(notes, closingCash, validCounts) }, enabled = !busy && readyToReview,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(if (busy) "Closing…" else "Confirm close")
                }
            } else {
                Button(onClick = { reviewing = true }, enabled = !busy && readyToReview) { Text("Review close") }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { if (reviewing) reviewing = false else onDismiss() }, enabled = !busy) {
                Text(if (reviewing) "Go back" else "Cancel")
            }
        },
    )
}

private fun countDraftKey(product: ProductEntity) = "${product.id}:${product.closingCountUnit}"

private fun recordedCount(product: ProductEntity, sealedPacks: Map<String, Int>): Double =
    if (product.tracksPowderPacks) (sealedPacks[product.id] ?: 0).toDouble() else product.unitsInStock
