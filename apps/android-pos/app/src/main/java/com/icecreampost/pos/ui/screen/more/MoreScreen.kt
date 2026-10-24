package com.icecreampost.pos.ui.screen.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.navigation.Routes
import com.icecreampost.pos.ui.component.*

@Composable
fun MoreScreen(viewModel: PosViewModel, onNavigate: (String) -> Unit) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val lines by viewModel.cartLines.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You will need your cashier credentials to sign in again.")
                    if (lines.isNotEmpty()) Text("Your current order will be cleared.")
                    if (transactions.any { it.stallId == session?.stallId && !it.isSynced })
                        Text("Pending transactions stay on this device. Sign in again to sync them.")
                }
            },
            confirmButton = { Button(onClick = viewModel::signOut, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }, enabled = !busy) { Text("Cancel") } },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("More") }
        item { GroupLabel("Operations") }
        item { MoreRow("Operating day", "Open or close the stall") { onNavigate(Routes.OPERATING_DAY) } }
        item { MoreRow("Revenue deductions", "Record and review deductions") { onNavigate(Routes.DEDUCTIONS) } }
        item { GroupLabel("Account") }
        item {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(session?.displayName ?: "Cashier", style = MaterialTheme.typography.titleMedium)
                Text(session?.role?.replaceFirstChar { it.uppercase() } ?: "Cashier", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { GroupLabel("System") }
        item { MoreRow("Sync status") { onNavigate(Routes.SYNC) } }
        item { MoreRow("Settings") { onNavigate(Routes.SETTINGS) } }
        item { MoreRow("Device information") { onNavigate(Routes.DEVICE) } }
        item {
            TextButton(onClick = { confirmSignOut = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Sign out") }
        }
    }
}

@Composable
private fun GroupLabel(label: String) {
    Text(label.uppercase(), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MoreRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
