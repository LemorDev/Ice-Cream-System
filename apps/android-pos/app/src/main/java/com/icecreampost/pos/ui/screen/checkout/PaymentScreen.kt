package com.icecreampost.pos.ui.screen.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.ScreenHeader
import com.icecreampost.pos.ui.component.formatMoney
import java.util.Locale
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(viewModel: PosViewModel, onSuccess: () -> Unit, onBack: () -> Unit) {
    var cashInput by rememberSaveable { mutableStateOf("") }
    val lines by viewModel.cartLines.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val total = lines.sumOf { it.lineTotalCents }
    val cashCents = cashInput.toCentsOrNull()
    val change = cashCents?.minus(total)?.takeIf { it >= 0 }
    val suggestedAmounts = buildSuggestedCashAmounts(total)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Button(
                    onClick = { cashCents?.let { viewModel.checkout(it, onSuccess) } },
                    enabled = !busy && cashCents != null && cashCents >= total && lines.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Complete sale", modifier = Modifier.weight(1f))
                        Text("→")
                    }
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ScreenHeader(title = "Payment", subtitle = "Cash", onBack = onBack)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Amount due", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(formatMoney(total), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "${lines.sumOf { it.quantity }} ${if (lines.sumOf { it.quantity } == 1) "item" else "items"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cash received", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = cashInput,
                    onValueChange = { value ->
                        cashInput = value.filter { it.isDigit() || it == '.' }.let { filtered ->
                            val parts = filtered.split('.', limit = 2)
                            if (parts.size == 2) "${parts[0]}.${parts[1].take(2)}" else parts[0]
                        }
                    },
                    prefix = { Text("₱") },
                    placeholder = { Text("0.00") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.medium,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quick cash", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestedAmounts.forEachIndexed { index, amount ->
                        AssistChip(
                            onClick = { cashInput = amount.toPesoInput() },
                            label = { Text(if (index == 0) "Exact · ${formatMoney(amount)}" else formatMoney(amount)) },
                        )
                    }
                }
            }
            if (cashCents != null && cashCents < total) {
                Text("Still due ${formatMoney(total - cashCents)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
            change?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Change", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(formatMoney(it), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(it, modifier = Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "This sale is saved on the device first and will sync automatically when online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun String.toCentsOrNull(): Long? =
    trim().toBigDecimalOrNull()?.movePointRight(2)?.longValueExactOrNull()

private fun java.math.BigDecimal.longValueExactOrNull(): Long? = try {
    longValueExact()
} catch (_: ArithmeticException) {
    null
}

internal fun buildSuggestedCashAmounts(totalCents: Long): List<Long> {
    if (totalCents <= 0) return listOf(0)
    val steps = listOf(5_000L, 10_000L, 20_000L, 50_000L, 100_000L)
    return (listOf(totalCents) + steps.map { step -> ceil(totalCents.toDouble() / step).toLong() * step })
        .distinct()
        .sorted()
        .take(4)
}

private fun Long.toPesoInput(): String = String.format(Locale.US, "%.2f", this / 100.0)
