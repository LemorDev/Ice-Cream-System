package com.icecreampost.pos.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.BuildConfig
import com.icecreampost.pos.ui.PosUpdateViewModel
import com.icecreampost.pos.ui.component.ScreenHeader

@Composable
fun PosUpdatesScreen(viewModel: PosUpdateViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    val installationAllowed = remember(context, lifecycleState) { context.packageManager.canRequestPackageInstalls() }
    var installWhenReady by remember { mutableStateOf(false) }

    fun openInstaller() {
        try {
            viewModel.installerIntent()?.let { context.startActivity(it) }
        } catch (_: Exception) {
            viewModel.showInstallerError("Could not open Android's installer. Check this device's installation settings.")
        }
    }

    LaunchedEffect(state.apk, lifecycleState, installWhenReady, state.error) {
        if (state.error != null) installWhenReady = false
        if (installWhenReady && state.apk != null && lifecycleState == Lifecycle.State.RESUMED) {
            installWhenReady = false
            if (installationAllowed) openInstaller()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("App updates", onBack = onBack) }
        item { Text("Installed version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium) }
        if (!viewModel.isConfigured) {
            item { Text("An update channel has yet to be configured for this build.") }
            return@LazyColumn
        }
        if (state.busy) {
            item {
                Text(if (state.downloading) "Downloading update…" else "Checking for updates…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } }
        if (state.upToDate && !state.busy && state.error == null) item { Text("This POS is up to date.") }
        state.release?.let { release ->
            item { Text("Version ${release.versionName} available", style = MaterialTheme.typography.titleLarge) }
            if (!installationAllowed) item { Text("Allow this POS to install updates in Android Settings, then return here.") }
            item {
                Button(
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = {
                        when {
                            !installationAllowed -> try {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.fromParts("package", context.packageName, null)))
                            } catch (_: Exception) {
                                viewModel.showInstallerError("This device could not open the installation setting.")
                            }
                            state.apk != null -> openInstaller()
                            else -> {
                                installWhenReady = true
                                viewModel.download()
                            }
                        }
                    },
                ) {
                    Text(when {
                        !installationAllowed -> "Allow installation"
                        state.apk != null -> "Install update"
                        else -> "Download and install"
                    })
                }
            }
        }
        item {
            OutlinedButton(onClick = { viewModel.check() }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Check for updates") }
        }
    }
}
