package com.icecreampost.pos.ui.component

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

@Composable
fun FingerprintActionButton(
    label: String,
    title: String,
    enabled: Boolean,
    prepareCipher: () -> Cipher,
    onAuthenticated: (Cipher) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.fragmentActivity() }
    val onSuccess by rememberUpdatedState(onAuthenticated)
    var prompting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val prompt = remember(activity) {
        activity?.let {
            BiometricPrompt(it, ContextCompat.getMainExecutor(it), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    message = null
                    result.cryptoObject?.cipher?.let(onSuccess)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    if (errorCode !in listOf(BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON)) {
                        message = "$errString You can sign in with your password."
                    }
                }
            })
        }
    }
    DisposableEffect(prompt) {
        onDispose { prompt?.cancelAuthentication() }
    }
    OutlinedButton(
        onClick = {
            message = null
            val availability = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (prompt == null) {
                message = "Fingerprint login is unavailable. Use your password."
            } else if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
                message = when (availability) {
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Add a fingerprint in this phone's Android Settings first."
                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "The fingerprint sensor is temporarily unavailable. Try again or use your password."
                    else -> "This phone does not support secure fingerprint login. Use your password."
                }
            } else {
                try {
                    val cryptoObject = BiometricPrompt.CryptoObject(prepareCipher())
                    val info = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setSubtitle("Verify your identity to use the saved Cashier session")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setNegativeButtonText("Cancel")
                        .build()
                    prompting = true
                    prompt.authenticate(info, cryptoObject)
                } catch (_: Exception) {
                    prompting = false
                    message = "Fingerprint login needs to be set up again. Sign in with your password, then enable it in POS Settings."
                }
            }
        },
        enabled = enabled && !prompting,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (prompting) "Waiting for fingerprint…" else label) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}
