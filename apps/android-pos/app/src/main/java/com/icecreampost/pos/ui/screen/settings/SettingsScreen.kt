package com.icecreampost.pos.ui.screen.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.BuildConfig
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*

@Composable
fun SettingsScreen(viewModel: PosViewModel, onBack: () -> Unit) {
    val visible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("Settings", onBack = onBack) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show sales amounts", style = MaterialTheme.typography.bodyLarge)
                    Text("Dashboard, history and receipts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = visible, onCheckedChange = { viewModel.toggleAmountsVisible() },
                    modifier = Modifier.semantics { contentDescription = "Show sales amounts" })
            }
        }
    }
}

@Composable
fun SyncScreen(viewModel: PosViewModel, onBack: () -> Unit) {
    val state by viewModel.syncState.collectAsStateWithLifecycle()
    val message by viewModel.syncMessage.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isSyncing.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("Sync status", onBack = onBack) }
        item { SyncStatusLine(viewModel) }
        item { Text("Sales and stock receipts save on this device first. Sync runs automatically when a connection is available.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedButton(onClick = viewModel::syncToIms, enabled = !busy && state?.status != "running", modifier = Modifier.fillMaxWidth()) {
                Text(if (busy || state?.status == "running") "Syncing…" else "Sync now")
            }
        }
        state?.errorMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        error?.takeIf { it != state?.errorMessage }?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
fun DeviceInformationScreen(viewModel: PosViewModel, onBack: () -> Unit) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("Device information", onBack = onBack) }
        item { DeviceValue("Device", "${Build.MANUFACTURER} ${Build.MODEL}") }
        item { DeviceValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") }
        item { DeviceValue("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
        item { DeviceValue("POS registration", if (session?.isActivated == true) "Activated" else "Not activated") }
        session?.deviceId?.let { item { DeviceValue("Device ID", it) } }
        if (session?.isActivated == true) item {
            Text(if (session?.transferReady == true)
                "This POS is ready for replacement. Sales and stock operations are paused. To keep using it, sign in again; the pending replacement will require a new preparation."
            else "Replacing this POS? Close the operating day, then sync all sales, stock changes, deductions, and the closing before creating a replacement code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = viewModel::prepareReplacement, enabled = !busy && !syncing && session?.transferReady != true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(if (session?.transferReady == true) "Ready for replacement" else if (busy || syncing) "Checking sync…" else "Prepare for replacement")
            }
            syncMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DeviceValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
