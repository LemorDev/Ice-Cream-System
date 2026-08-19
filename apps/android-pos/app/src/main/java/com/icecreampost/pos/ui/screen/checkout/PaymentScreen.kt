package com.icecreampost.pos.ui.screen.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.formatMoney

@Composable
fun PaymentScreen(viewModel: PosViewModel, onSuccess: () -> Unit, onBack: () -> Unit) {
    var cashInput by rememberSaveable { mutableStateOf("") }
    val lines by viewModel.cartLines.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val total = lines.sumOf { it.lineTotalCents }
    val cashCents = cashInput.toCentsOrNull()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back to cart") }
        Text("Payment", style = MaterialTheme.typography.headlineMedium)
        Text("Total due: ${formatMoney(total)}")
        OutlinedTextField(
            value = cashInput,
            onValueChange = { cashInput = it.filter { char -> char.isDigit() || char == '.' } },
            label = { Text("Cash received") },
            prefix = { Text("₱") },
            modifier = Modifier.fillMaxWidth(),
        )
        cashCents?.let { if (it >= total) Text("Change: ${formatMoney(it - total)}") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { cashCents?.let { viewModel.checkout(it, onSuccess) } },
            enabled = !busy && cashCents != null && cashCents >= total,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator() else Text("Complete sale offline")
        }
    }
}

private fun String.toCentsOrNull(): Long? =
    trim().toBigDecimalOrNull()?.movePointRight(2)?.longValueExactOrNull()

private fun java.math.BigDecimal.longValueExactOrNull(): Long? = try {
    longValueExact()
} catch (_: ArithmeticException) {
    null
}
