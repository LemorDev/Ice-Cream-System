package com.icecreampost.pos.ui.component

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.BuildConfig
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

// Use the same business timezone as opening/closing; retain stored UTC values.
val businessZone: ZoneId = ZoneId.of("Asia/Manila")

fun formatReceiptTime(value: String, zone: ZoneId = businessZone): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())
        .withZone(zone).format(Instant.parse(value))
}.getOrDefault("Date unavailable")

fun formatBusinessDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
}.getOrDefault("Date unavailable")

fun formatQuantity(value: Double): String = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US)).format(value)
fun displayUnit(unit: String): String = when (unit.lowercase(Locale.ROOT)) {
    "piece", "pieces", "pc", "pcs" -> "pcs"
    else -> unit
}
fun formatStock(value: Double, unit: String): String = "${formatQuantity(value)} ${displayUnit(unit)}"

@Composable
fun AppEnvironmentBanner(modifier: Modifier = Modifier) {
    val development = BuildConfig.APP_ENV == "development"
    val color = if (development) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
    val contentColor = if (development) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(modifier = modifier.fillMaxWidth(), color = color, tonalElevation = 2.dp) {
        Text(
            if (development) "DEVELOPMENT DATABASE · TEST DATA ONLY" else "PRODUCTION DATABASE · LIVE BUSINESS DATA",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

internal fun syncStatusLabel(state: SyncStateEntity?, pending: Int, online: Boolean, now: Instant = Instant.now()): String {
    val queued = "$pending ${if (pending == 1) "transaction" else "transactions"} pending"
    if (!online) return "Offline · $queued"
    return when (state?.status) {
        "running" -> "Syncing${if (pending > 0) " · $queued" else "…"}"
        "error" -> "Sync failed · $queued"
        "retrying" -> "Sync retry scheduled · $queued"
        else -> {
            val last = state?.lastSyncAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            when {
                pending > 0 -> queued.replaceFirstChar { it.uppercase() }
                last == null -> "Ready to sync"
                Duration.between(last, now).seconds < 60 -> "Synced just now"
                Duration.between(last, now).toMinutes() < 60 -> "Synced ${Duration.between(last, now).toMinutes()} min ago"
                else -> "Synced ${formatReceiptTime(last.toString())}"
            }
        }
    }
}

@Composable
private fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService<ConnectivityManager>() }
    fun connected(): Boolean = manager?.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    var online by remember(manager) { mutableStateOf(connected()) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = connected() }
            override fun onLost(network: Network) { online = connected() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { online = connected() }
        }
        manager?.registerDefaultNetworkCallback(callback)
        onDispose { manager?.unregisterNetworkCallback(callback) }
    }
    return online
}

@Composable
fun SyncStatusLine(viewModel: PosViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.syncState.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.isSyncing.collectAsStateWithLifecycle()
    val online = rememberOnline()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(state) {
        while (true) { now = Instant.now(); kotlinx.coroutines.delay(30_000) }
    }
    val pending = transactions.count { it.stallId == session?.stallId && !it.isSynced }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(syncStatusLabel(state, pending, online, now), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (online && state?.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (online && state?.status in listOf("error", "retrying")) {
            TextButton(onClick = viewModel::syncToIms, enabled = !busy) { Text("Retry") }
        }
    }
}
