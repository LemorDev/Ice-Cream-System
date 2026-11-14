package com.icecreampost.pos.ui.screen.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.BuildConfig
import com.icecreampost.pos.data.repository.SYNC_SIGN_IN_REQUIRED
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.*
import com.icecreampost.pos.ui.screen.login.LoginPurpose
import com.icecreampost.pos.ui.screen.login.LoginScreen

@Composable
fun SettingsScreen(viewModel: PosViewModel, onBack: () -> Unit, onSync: () -> Unit, onDevice: () -> Unit) {
    var showSecurity by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSecurity) { showSecurity = false }
    val visible by viewModel.amountsVisible.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp)) {
            IconButton(
                onClick = { if (showSecurity) showSecurity = false else onBack() },
                modifier = Modifier.align(Alignment.CenterStart).semantics { contentDescription = "Go back" },
            ) { SettingsIcon(SettingsSymbol.Back) }
            Text(
                if (showSecurity) "Fingerprint login" else "Settings",
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (showSecurity) {
            FingerprintSettings(viewModel)
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    SettingsGroup("Preferences") {
                        SettingsRow(SettingsSymbol.Eye, "Show sales amounts", "Dashboard, history and receipts") {
                            Switch(checked = visible, onCheckedChange = { viewModel.toggleAmountsVisible() },
                                modifier = Modifier.semantics { contentDescription = "Show sales amounts" })
                        }
                        SettingsLink(SettingsSymbol.Lock, "Fingerprint login", "Set up fingerprint sign-in or lock this POS") { showSecurity = true }
                    }
                }
                item {
                    SettingsGroup("Support") {
                        SettingsLink(SettingsSymbol.Sync, "Sync status", onClick = onSync)
                        SettingsLink(SettingsSymbol.Device, "Device information", onClick = onDevice)
                    }
                }
            }
        }
    }
}

@Composable
private fun FingerprintSettings(viewModel: PosViewModel) {
    val fingerprintEnabled by viewModel.fingerprintEnabled.collectAsStateWithLifecycle()
    val fingerprintSignInAvailable by viewModel.fingerprintSignInAvailable.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OutlinedButton(onClick = viewModel::lockPos, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Lock POS") }
            if (fingerprintEnabled) {
                Text(if (fingerprintSignInAvailable) "Use your fingerprint to open the operating day or unlock this POS."
                    else "Fingerprint unlock is enabled. Enable fingerprint sign-in to use it after signing out.")
                OutlinedButton(onClick = viewModel::disableFingerprintLogin, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Turn off fingerprint login") }
            }
            if (!fingerprintSignInAvailable) {
                if (!fingerprintEnabled) Text("Use this phone's fingerprint to unlock the POS and sign in to your cashier account.")
                FingerprintActionButton("Enable fingerprint sign-in", "Enable fingerprint sign-in", !busy,
                    viewModel::prepareFingerprintEnrollment, viewModel::enableFingerprintLogin)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun SyncScreen(viewModel: PosViewModel, onBack: () -> Unit) {
    val state by viewModel.syncState.collectAsStateWithLifecycle()
    val message by viewModel.syncMessage.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    var reconnecting by rememberSaveable { mutableStateOf(false) }
    val closeSignIn = {
        viewModel.dismissError()
        reconnecting = false
    }
    BackHandler(enabled = reconnecting) { if (!busy) closeSignIn() }
    if (reconnecting) {
        LoginScreen(viewModel, LoginPurpose.ReconnectSync, onDone = closeSignIn)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("Sync status", onBack = onBack) }
        item { SyncStatusLine(viewModel) }
        item { Text("Sales and stock receipts save on this device first. Sync runs automatically when a connection is available.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            val running = syncing || state?.status == "running"
            val needsSignIn = state?.status == SYNC_SIGN_IN_REQUIRED
            OutlinedButton(
                onClick = {
                    if (needsSignIn) {
                        viewModel.dismissError()
                        reconnecting = true
                    } else viewModel.syncToIms()
                },
                enabled = !busy && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Syncing…" else if (needsSignIn) "Reconnect sync" else "Sync now")
            }
        }
        state?.errorMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        message?.takeIf { it != state?.errorMessage }?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
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
