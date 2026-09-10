package com.icecreampost.pos.ui.screen.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.BuildConfig
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.SyncStatusCard

@Composable
fun SettingsScreen(viewModel: PosViewModel, onBack: () -> Unit) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    var showSignOutConfirmation by rememberSaveable { mutableStateOf(false) }

    if (showSignOutConfirmation) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirmation = false },
            title = { Text("Sign out?") },
            text = { Text("You will need your cashier credentials to sign in again.") },
            dismissButton = {
                OutlinedButton(onClick = { showSignOutConfirmation = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(onClick = {
                    showSignOutConfirmation = false
                    viewModel.signOut()
                }) { Text("Sign out") }
            },
        )
    }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Text("Settings and device info", style = MaterialTheme.typography.headlineMedium)
        SyncStatusCard(syncState)
        Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Text("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Text("Build: ${BuildConfig.VERSION_NAME}")
        Text("Signed in as: ${session?.displayName ?: "Not signed in"}")
        Text("Role: ${session?.role ?: "-"}")
        Button(
            onClick = viewModel::syncToIms,
            enabled = !busy && session?.sessionToken?.isNotBlank() == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Syncing…" else "Sync to IMS")
        }
        syncMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text("Sync failed: $it", color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = { showSignOutConfirmation = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}
