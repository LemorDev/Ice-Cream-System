package com.icecreampost.pos.ui.screen.login

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.CoolerzLogo
import com.icecreampost.pos.ui.component.FingerprintActionButton

@Composable
fun FingerprintSetupOffer(viewModel: PosViewModel) {
    val offered by viewModel.offerFingerprintSetup.collectAsStateWithLifecycle()
    if (!offered) return

    val context = LocalContext.current
    val supported = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    if (!supported) {
        LaunchedEffect(Unit) { viewModel.dismissFingerprintSetup() }
        return
    }

    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var enrolling by remember { mutableStateOf(false) }
    if (busy && !enrolling) return

    AlertDialog(
        onDismissRequest = viewModel::dismissFingerprintSetup,
        title = { Text("Enable fingerprint unlock?") },
        text = { Text("Use your fingerprint to unlock this POS when you return. You can change this later in Settings.") },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FingerprintActionButton(
                    label = "Enable fingerprint unlock",
                    title = "Enable fingerprint unlock",
                    enabled = !busy,
                    prepareCipher = viewModel::prepareFingerprintEnrollment,
                    onAuthenticated = { cipher ->
                        enrolling = true
                        viewModel.enableFingerprintLogin(cipher)
                    },
                )
                if (enrolling) error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                TextButton(onClick = viewModel::dismissFingerprintSetup, modifier = Modifier.align(Alignment.End)) {
                    Text("Not now")
                }
            }
        },
    )
}

@Composable
fun LoginScreen(viewModel: PosViewModel) {
    var stallCode by rememberSaveable { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPasswordForm by rememberSaveable { mutableStateOf(false) }
    val fingerprintUnlockRequired by viewModel.fingerprintUnlockRequired.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoolerzLogo(Modifier.size(112.dp).align(Alignment.CenterHorizontally))
        Text(
            "Coolerz POS",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        if (fingerprintUnlockRequired) {
            Text("Unlock your saved cashier session with your fingerprint.")
            FingerprintActionButton(
                label = "Unlock with fingerprint",
                title = "Unlock Coolerz POS",
                enabled = !busy,
                prepareCipher = viewModel::prepareFingerprintUnlock,
                onAuthenticated = viewModel::unlockWithFingerprint,
            )
            TextButton(
                onClick = {
                    showPasswordForm = !showPasswordForm
                    if (!showPasswordForm) password = ""
                    viewModel.dismissError()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (showPasswordForm) "Hide password sign-in" else "Use password instead") }
            if (!showPasswordForm) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                return@Column
            }
        } else {
            Text("Sign in with the stall code and Cashier account. This phone will reuse its active POS registration.")
        }
        OutlinedTextField(
            stallCode,
            { stallCode = it.uppercase() },
            label = { Text("Stall code") },
            enabled = !busy,
            placeholder = { Text("MAIN-001") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(employeeId, { employeeId = it.uppercase() }, label = { Text("Employee ID") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val enteredPassword = password
                password = ""
                viewModel.signIn(stallCode, employeeId, enteredPassword)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator() else Text("Sign in with password")
        }
        Text("Password sign-in requires internet access. To set up fingerprint unlock after signing in, go to More → Settings → Fingerprint login.")
    }
}
