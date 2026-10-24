package com.icecreampost.pos.ui.screen.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel

@Composable
fun ActivationScreen(viewModel: PosViewModel, onSuccess: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Activate this device", style = MaterialTheme.typography.headlineMedium)
        Text("Enter the one-time POS activation code created by the System Administrator. This is different from the stall code used during sign-in.")
        OutlinedTextField(code, { code = it.trim().uppercase() }, label = { Text("Device activation code") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { viewModel.activate(code, onSuccess) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator() else Text("Activate device")
        }
        TextButton(onClick = viewModel::signOut, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in with another cashier")
        }
    }
}
