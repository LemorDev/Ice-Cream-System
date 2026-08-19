package com.icecreampost.pos.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.SyncStatusCard

@Composable
fun HomeScreen(
    viewModel: PosViewModel,
    onCatalog: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Good day, ${session?.displayName ?: "Cashier"}", style = MaterialTheme.typography.headlineMedium)
        Text("Coolerz Ice Cream point of sale")
        SyncStatusCard(syncState)
        Button(onClick = onCatalog, modifier = Modifier.fillMaxWidth()) { Text("Start sale") }
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Transaction history") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings and device info") }
    }
}
