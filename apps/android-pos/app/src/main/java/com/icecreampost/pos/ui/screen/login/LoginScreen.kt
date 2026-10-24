package com.icecreampost.pos.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.CoolerzLogo
import com.icecreampost.pos.ui.component.FingerprintActionButton

@Composable
fun LoginScreen(viewModel: PosViewModel) {
    var stallCode by rememberSaveable { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
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
            Text("POS is locked. Use your fingerprint to unlock your Cashier session, or sign in with your password.")
            FingerprintActionButton(
                label = "Sign in with fingerprint",
                title = "Unlock Coolerz POS",
                enabled = !busy,
                prepareCipher = viewModel::prepareFingerprintUnlock,
                onAuthenticated = viewModel::unlockWithFingerprint,
            )
        } else {
            Text("Sign in with the stall code and Cashier account. This phone will reuse its active POS registration.")
        }
        OutlinedTextField(
            stallCode,
            { stallCode = it.uppercase() },
            label = { Text("Stall code") },
            placeholder = { Text("MAIN-001") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(employeeId, { employeeId = it.uppercase() }, label = { Text("Employee ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
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
        Text("Activation and sign-in require a network connection. Sales save locally while this cashier session remains signed in; after it expires, sign in online again.")
    }
}
