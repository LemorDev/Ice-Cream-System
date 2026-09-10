package com.icecreampost.pos.ui.screen.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.SyncStatusCard
import com.icecreampost.pos.ui.component.formatMoney
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    viewModel: PosViewModel,
    onCatalog: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val businessDay by viewModel.businessDay.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val isOpen = businessDay?.closedAt == null && businessDay != null
    val today = LocalDate.now(ZoneId.of("Asia/Manila")).toString()
    val alreadyOperatedToday = businessDay?.businessDate == today
    val visibleDay = businessDay?.takeIf { isOpen || alreadyOperatedToday }
    val completedSales = if (visibleDay == null) emptyList() else transactions.filter {
        it.status == "completed" && it.stallId == visibleDay.stallId && it.occurredAt >= visibleDay.openedAt &&
            (visibleDay.closedAt == null || it.occurredAt <= visibleDay.closedAt)
    }
    val salesTotal = completedSales.sumOf { it.totalCents }
    var showCloseDialog by remember { mutableStateOf(false) }
    var closingNotes by remember { mutableStateOf("") }

    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text("Close operating day?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cash sales recorded on this device: ${formatMoney(salesTotal)}")
                OutlinedTextField(closingNotes, { closingNotes = it }, label = { Text("Closing notes (optional)") })
            } },
            confirmButton = { Button(onClick = { viewModel.closeDay(closingNotes); closingNotes = ""; showCloseDialog = false }, enabled = !busy) { Text("Close day") } },
            dismissButton = { OutlinedButton(onClick = { showCloseDialog = false }) { Text("Cancel") } },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("COOLERZ POS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Good day, ${session?.displayName ?: "Cashier"}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Ready for the next scoop?", style = MaterialTheme.typography.headlineMedium)
        }
        item { SyncStatusCard(syncState) }
        error?.let { message ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        OutlinedButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isOpen) "Stall is open" else "Operating day is closed",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isOpen) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    )
                    businessDay?.let { day ->
                        Text("Business date: ${day.businessDate}", style = MaterialTheme.typography.bodyMedium)
                        Text("Opened: ${formatTimestamp(day.openedAt)}", style = MaterialTheme.typography.bodySmall)
                        day.closedAt?.let { Text("Closed: ${formatTimestamp(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (isOpen) {
                        OutlinedButton(onClick = { showCloseDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Close operating day") }
                    } else if (alreadyOperatedToday) {
                        Text("Today's operating day is closed. The next day can be opened after midnight.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = { viewModel.openDay() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open today's stall") }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("New sale", style = MaterialTheme.typography.titleLarge)
                    Text(if (isOpen) "Build an order from your available products." else "Open the operating day before accepting sales.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onCatalog, enabled = isOpen, modifier = Modifier.fillMaxWidth()) { Text("Start a sale  →") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Sales", formatMoney(salesTotal), Modifier.weight(1f))
                MetricCard("Orders", completedSales.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Text("More", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) { Text("History") }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
            }
        }
    }
}

private fun formatTimestamp(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")
        .withZone(ZoneId.of("Asia/Manila")).format(Instant.parse(value))
}.getOrDefault(value)

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
