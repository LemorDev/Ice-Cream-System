package com.icecreampost.pos.ui.screen.login

import androidx.biometric.BiometricManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icecreampost.pos.ui.PosViewModel
import com.icecreampost.pos.ui.component.CoolerzLogo
import com.icecreampost.pos.ui.component.FingerprintActionButton
import com.icecreampost.pos.ui.component.PosIcon
import com.icecreampost.pos.ui.component.PosSymbol

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
        title = { Text("Enable fingerprint sign-in?") },
        text = { Text("Use your fingerprint to open the operating day or unlock this POS.") },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FingerprintActionButton(
                    label = "Enable fingerprint sign-in",
                    title = "Enable fingerprint sign-in",
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

enum class LoginPurpose { SignIn, OpenDay, ReconnectSync }

@Composable
fun LoginScreen(
    viewModel: PosViewModel,
    purpose: LoginPurpose = LoginPurpose.SignIn,
    onDone: () -> Unit = {},
) {
    val opening = purpose == LoginPurpose.OpenDay
    val reconnecting = purpose == LoginPurpose.ReconnectSync
    val savedSignInDetails by viewModel.offlineSignInDetails.collectAsStateWithLifecycle()
    var stallCode by rememberSaveable { mutableStateOf(savedSignInDetails?.first.orEmpty()) }
    var employeeId by rememberSaveable { mutableStateOf(savedSignInDetails?.second.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showPasswordForm by rememberSaveable { mutableStateOf(false) }
    val locked by viewModel.posLocked.collectAsStateWithLifecycle()
    val fingerprintEnabled by viewModel.fingerprintEnabled.collectAsStateWithLifecycle()
    val fingerprintSignInAvailable by viewModel.fingerprintSignInAvailable.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val showFingerprint = !reconnecting && (fingerprintSignInAvailable || (locked || opening) && fingerprintEnabled)
    val showForm = !showFingerprint || showPasswordForm
    val canSubmit = !busy && stallCode.isNotBlank() && employeeId.isNotBlank() && password.isNotEmpty()
    fun submitPassword() {
        if (!canSubmit) return
        val enteredPassword = password
        passwordVisible = false
        password = ""
        focus.clearFocus()
        when (purpose) {
            LoginPurpose.SignIn -> viewModel.signIn(stallCode, employeeId, enteredPassword)
            LoginPurpose.OpenDay -> viewModel.openDay(stallCode, employeeId, enteredPassword, onDone)
            LoginPurpose.ReconnectSync -> viewModel.reconnectCloud(stallCode, employeeId, enteredPassword, onDone)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(lerp(colors.background, colors.primaryContainer, 0.24f))
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CoolerzLogo(Modifier.size(88.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val title = when (purpose) {
                        LoginPurpose.SignIn -> "Coolerz POS"
                        LoginPurpose.OpenDay -> "Open today's stall"
                        LoginPurpose.ReconnectSync -> "Reconnect sync"
                    }
                    Text(title, Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(
                        when {
                            reconnecting -> "Use your cashier password to connect this POS to IMS."
                            opening -> "Verify your cashier account to start the operating day."
                            locked -> "Unlock your saved cashier session."
                            fingerprintSignInAvailable -> "Sign in to your saved cashier account."
                            else -> "Sign in with your stall code and cashier account."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (showFingerprint) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FingerprintActionButton(
                            label = if (locked) "Unlock with fingerprint" else "Sign in with fingerprint",
                            title = when {
                                opening -> "Open today's stall"
                                locked -> "Unlock Coolerz POS"
                                else -> "Sign in to Coolerz POS"
                            },
                            enabled = !busy,
                            prepareCipher = viewModel::prepareFingerprintUnlock,
                            onAuthenticated = { cipher ->
                                when {
                                    opening -> viewModel.openDayWithFingerprint(cipher, onDone)
                                    locked -> viewModel.unlockWithFingerprint(cipher)
                                    else -> viewModel.signInWithFingerprint(cipher)
                                }
                            },
                        )
                        TextButton(
                            onClick = {
                                showPasswordForm = !showPasswordForm
                                if (!showPasswordForm) {
                                    passwordVisible = false
                                    password = ""
                                    focus.clearFocus()
                                }
                                viewModel.dismissError()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (showPasswordForm) "Hide password sign-in" else "Use password instead") }
                    }
                }
                if (showForm) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val codeKeyboard = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        )
                        LoginField(
                            value = stallCode,
                            onValueChange = { stallCode = it.uppercase() },
                            label = "Stall code",
                            symbol = PosSymbol.Store,
                            enabled = !busy,
                            keyboardOptions = codeKeyboard,
                            placeholder = { Text("MAIN-001") },
                        )
                        LoginField(
                            value = employeeId,
                            onValueChange = { employeeId = it.uppercase() },
                            label = "Employee ID",
                            symbol = PosSymbol.EmployeeBadge,
                            enabled = !busy,
                            keyboardOptions = codeKeyboard,
                        )
                        LoginField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Password",
                            symbol = PosSymbol.Lock,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val description = if (passwordVisible) "Hide password" else "Show password"
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    enabled = !busy,
                                    modifier = Modifier.semantics { contentDescription = description },
                                ) {
                                    PosIcon(if (passwordVisible) PosSymbol.EyeOff else PosSymbol.Eye)
                                }
                            },
                        )
                    }
                }
                error?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
                        shape = MaterialTheme.shapes.small,
                        color = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                    ) {
                        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showForm) {
                    Button(
                        onClick = ::submitPassword,
                        enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = colors.onSurfaceVariant,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                busy -> "Verifying…"
                                reconnecting -> "Reconnect sync"
                                opening -> "Sign in and open stall"
                                locked -> "Unlock with password"
                                else -> "Sign in with password"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (!busy) {
                            Spacer(Modifier.width(8.dp))
                            PosIcon(PosSymbol.ArrowRight)
                        }
                    }
                    if (savedSignInDetails == null && !reconnecting) Text(
                        "Connect to the internet to set up offline password sign-in for this cashier.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (purpose != LoginPurpose.SignIn) {
                    TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    symbol: PosSymbol,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    placeholder: (@Composable () -> Unit)? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder,
        leadingIcon = { PosIcon(symbol) },
        trailingIcon = trailingIcon,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.background,
            unfocusedContainerColor = colors.background,
        ),
    )
}
