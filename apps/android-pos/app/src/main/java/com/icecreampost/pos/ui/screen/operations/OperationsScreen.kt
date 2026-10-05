package com.icecreampost.pos.ui.screen.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.setValue
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*
import com.icecreampost.pos.ui.screen.home.validateRevenueDeduction
import java.time.LocalDate
import java.time.ZoneId
import com.icecreampost.pos.ui.screen.checkout.toCentsOrNull

@Composable
fun OperationsScreen(viewModel: PosViewModel, onBack: () -> Unit, deductionsOnly: Boolean = false) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val deductions by viewModel.revenueDeductions.collectAsStateWithLifecycle()
    val storedDay by viewModel.businessDay.collectAsStateWithLifecycle()
    val businessDay = storedDay?.takeIf { it.stallId == session?.stallId }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val amountsVisible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    val isOpen = businessDay?.closedAt == null && businessDay != null
    val today = LocalDate.now(ZoneId.of("Asia/Manila")).toString()
    val alreadyOperatedToday = businessDay?.businessDate == today
    val visibleDay = businessDay?.takeIf { isOpen || alreadyOperatedToday }
    val completedSales = if (visibleDay == null) emptyList() else transactions.filter {
        it.status == "completed" && it.stallId == visibleDay.stallId && it.occurredAt >= visibleDay.openedAt &&
            (visibleDay.closedAt == null || it.occurredAt <= visibleDay.closedAt)
    }
    val localSalesTotal = completedSales.sumOf { it.totalCents }
    val salesTotal = localSalesTotal + (visibleDay?.recoveryKnownSalesCents ?: 0)
    val dayDeductions = deductions.filter { it.businessDayId == visibleDay?.id }
    val deductionTotal = dayDeductions.sumOf { it.amountCents } + (visibleDay?.recoveryKnownDeductionsCents ?: 0)
    val expectedCash = (salesTotal - deductionTotal).coerceAtLeast(0)
    var showCloseDialog by rememberSaveable { mutableStateOf(false) }
    var showFinalCloseConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeductionDialog by rememberSaveable { mutableStateOf(false) }
    var closingNotes by rememberSaveable { mutableStateOf("") }
    var collectedCash by rememberSaveable { mutableStateOf("") }
    var revenueDeduction by rememberSaveable { mutableStateOf("") }
    var deductionReason by rememberSaveable { mutableStateOf("") }
    var affectsProfit by rememberSaveable { mutableStateOf(true) }
    val deductionError = validateRevenueDeduction(revenueDeduction, deductionReason, salesTotal - deductionTotal)
    val cashError = collectedCash.isNotBlank() && collectedCash.toCentsOrNull() == null

    if (showDeductionDialog) {
        AlertDialog(
            onDismissRequest = { showDeductionDialog = false },
            title = { Text("Record revenue deduction") },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remaining sales revenue: ${formatMoney((salesTotal - deductionTotal).coerceAtLeast(0), amountsVisible)}")
                OutlinedTextField(
                    revenueDeduction,
                    { revenueDeduction = it.filter { char -> char.isDigit() || char == '.' } },
                    label = { Text("Deduction amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = revenueDeduction.isNotBlank() && deductionError != null,
                    supportingText = deductionError?.takeIf { revenueDeduction.isNotBlank() }?.let { message -> { Text(message) } },
                )
                OutlinedTextField(
                    deductionReason,
                    { deductionReason = it },
                    label = { Text("Reason (required)") },
                    placeholder = { Text("e.g. customer refund") },
                    isError = revenueDeduction.isNotBlank() && deductionReason.isBlank(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = affectsProfit, onCheckedChange = { affectsProfit = it })
                    Text("Additional expense in profit", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Turn this off when the cash pays an expense already in IMS fixed overhead, or is only a cash transfer. It still reduces expected cash.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(onClick = {
                viewModel.recordRevenueDeduction(revenueDeduction.toCentsOrNull()!!, deductionReason, affectsProfit) {
                    revenueDeduction = ""
                    deductionReason = ""
                    affectsProfit = true
                    showDeductionDialog = false
                }
            }, enabled = !busy && revenueDeduction.isNotBlank() && revenueDeduction.toCentsOrNull() != null && revenueDeduction.toCentsOrNull()!! > 0 && deductionError == null) { Text("Save deduction") } },
            dismissButton = { OutlinedButton(onClick = { showDeductionDialog = false }) { Text("Cancel") } },
        )
    }

    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text("Close operating day?") },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cash sales recorded on this device: ${formatMoney(salesTotal, amountsVisible)}")
                Text("Recorded deductions: ${formatMoney(deductionTotal, amountsVisible)}")
                Text("Expected cash: ${formatMoney(expectedCash, amountsVisible)}")
                OutlinedTextField(collectedCash, { collectedCash = it.filter { char -> char.isDigit() || char == '.' } }, label = { Text("Closing cash counted") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), placeholder = { Text(formatMoney(expectedCash, amountsVisible)) }, isError = cashError, supportingText = if (cashError) {{ Text("Enter a valid cash amount.") }} else null)
                OutlinedTextField(closingNotes, { closingNotes = it }, label = { Text("Closing notes (optional)") })
            } },
            confirmButton = { Button(onClick = {
                showCloseDialog = false
                showFinalCloseConfirmation = true
            }, enabled = !busy && !cashError) { Text("Review close") } },
            dismissButton = { OutlinedButton(onClick = { showCloseDialog = false }) { Text("Cancel") } },
        )
    }
    if (showFinalCloseConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!busy) { showFinalCloseConfirmation = false; showCloseDialog = true } },
            title = { Text("Confirm closing the operating day") },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Expected cash: ${formatMoney(expectedCash, amountsVisible)}. Closing cash: ${formatMoney(collectedCash.toCentsOrNull() ?: expectedCash, amountsVisible)}. You cannot start another sale today after closing.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                viewModel.closeDay(closingNotes, collectedCash.toCentsOrNull() ?: expectedCash) {
                    showFinalCloseConfirmation = false
                    closingNotes = ""
                    collectedCash = ""
                }
            }, enabled = !busy) { Text("Confirm close") } },
            dismissButton = { OutlinedButton(onClick = { showFinalCloseConfirmation = false; showCloseDialog = true }, enabled = !busy) { Text("Go back") } },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader(if (deductionsOnly) "Revenue deductions" else "Operating day", onBack = onBack) }
        error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            androidx.compose.material3.TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
        } }
        item {
            Text(if (isOpen) "Stall open" else "Stall closed", style = MaterialTheme.typography.titleLarge)
            Text(formatBusinessDate(visibleDay?.businessDate ?: today), color = MaterialTheme.colorScheme.onSurfaceVariant)
            visibleDay?.let { day ->
                Text("Opened "+formatReceiptTime(day.openedAt), style = MaterialTheme.typography.bodyMedium)
                day.closedAt?.let { Text("Closed "+formatReceiptTime(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (visibleDay?.isRecoveryDay == true) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Lost POS recovery in progress", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text("IMS has ${formatMoney(visibleDay.recoveryKnownSalesCents, amountsVisible)} in synced sales and ${formatMoney(visibleDay.recoveryKnownDeductionsCents, amountsVisible)} in synced deductions for this day. These are the server-known totals when the old phone was reported lost; any unsynced queue on that phone is missing here. Count the cash and receipts physically, then close this day with the actual amount counted.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Cash summary", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                AmountVisibilityButton(amountsVisible, viewModel::toggleAmountsVisible)
            }
            OperationAmount("Sales", salesTotal, amountsVisible)
            OperationAmount("Deductions", deductionTotal, amountsVisible)
            OperationAmount("Expected cash", expectedCash, amountsVisible)
        }
        if (deductionsOnly) {
            item {
                Button(onClick = { showDeductionDialog = true }, enabled = isOpen && !busy, modifier = Modifier.fillMaxWidth()) { Text("Record deduction") }
                if (!isOpen) Text("Open the operating day to record a deduction.", style = MaterialTheme.typography.bodyMedium)
            }
            if (dayDeductions.isEmpty()) item { Text("No deductions for this operating day.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            dayDeductions.forEach { deduction -> item(key = deduction.id) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OperationAmount(deduction.reason, deduction.amountCents, amountsVisible)
                    Text(if (deduction.affectsProfit) "Additional expense in profit" else "Cash only · not an additional profit expense",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatReceiptTime(deduction.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (deduction.isSynced) "Synced" else "Pending sync", style = MaterialTheme.typography.bodySmall)
                    deduction.syncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    androidx.compose.material3.HorizontalDivider()
                }
            } }
        } else if (isOpen) {
            item {
                OutlinedButton(onClick = { showCloseDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Close operating day") }
            }
        } else if (alreadyOperatedToday) {
            item { Text("Today's operating day is closed. The next day can be opened after midnight.") }
        } else {
            item { Button(onClick = { viewModel.openDay() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open today's stall") } }
        }
    }
}

@Composable
private fun OperationAmount(label: String, cents: Long, visible: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(formatMoney(cents, visible), style = MaterialTheme.typography.titleMedium)
    }
}
