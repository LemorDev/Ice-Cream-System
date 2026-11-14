package com.icecreampost.pos.ui.screen.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.navigation.Routes
import com.icecreampost.pos.ui.component.*

@Composable
fun MoreScreen(viewModel: PosViewModel, availableUpdate: String?, onNavigate: (String) -> Unit) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val fingerprintSignInAvailable by viewModel.fingerprintSignInAvailable.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val lines by viewModel.cartLines.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (fingerprintSignInAvailable) "Sign in again with your fingerprint or password."
                        else "Sign in again with your cashier password.")
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
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("More", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsGroup("Operations") {
                    SettingsLink(SettingsSymbol.Calendar, "Operating day", "Open or close the stall") {
                        onNavigate(Routes.OPERATING_DAY)
                    }
                    SettingsLink(SettingsSymbol.Deductions, "Revenue deductions", "Record and review deductions") {
                        onNavigate(Routes.DEDUCTIONS)
                    }
                }
            }
            item {
                SettingsGroup("System") {
                    SettingsLink(SettingsSymbol.Sync, "Sync status") { onNavigate(Routes.SYNC) }
                    SettingsLink(SettingsSymbol.Preferences, "Settings") { onNavigate(Routes.SETTINGS) }
                    SettingsLink(SettingsSymbol.Sync, "App updates", availableUpdate?.let { "Version $it available" }) {
                        onNavigate(Routes.UPDATES)
                    }
                    SettingsLink(SettingsSymbol.Device, "Device information") { onNavigate(Routes.DEVICE) }
                }
            }
            item {
                SettingsGroup("Account") {
                    SettingsRow(
                        SettingsSymbol.Account,
                        session?.displayName ?: "Cashier",
                        session?.role?.replaceFirstChar { it.uppercase() } ?: "Cashier",
                    )
                    Surface(onClick = viewModel::lockPos, enabled = !busy, color = Color.Transparent) {
                        SettingsRow(SettingsSymbol.Lock, "Lock POS")
                    }
                    Surface(
                        onClick = { confirmSignOut = true },
                        enabled = !busy,
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.error,
                    ) {
                        SettingsRow(SettingsSymbol.SignOut, "Sign out")
                    }
                }
            }
        }
    }
}
