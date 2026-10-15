package com.icecreampost.pos.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64

/** A biometric-protected account marker. No passwords or fingerprint data are stored. */
@Singleton
class FingerprintLoginStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "fingerprint-login.json"))
    private val keyAlias = "coolerz-fingerprint-login"
    val isEnabled: Boolean get() = file.baseFile.exists()

    fun prepareEnableCipher(): Cipher {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        return cipher().apply { init(Cipher.ENCRYPT_MODE, generator.generateKey()) }
    }

    fun enable(userId: String, authenticatedCipher: Cipher) {
        require(userId.isNotBlank()) { "Sign in before enabling fingerprint login." }
        val encrypted = authenticatedCipher.doFinal(userId.toByteArray(Charsets.UTF_8))
        val json = JSONObject()
            .put("iv", Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
            .put("account", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun prepareUnlockCipher(): Cipher {
        val record = readRecord()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(keyAlias, null) as? SecretKey
            ?: error("Fingerprint login needs to be set up again. Sign in with your password.")
        return cipher().apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(record.getString("iv"), Base64.NO_WRAP)))
        }
    }

    fun verifyAccount(userId: String?, authenticatedCipher: Cipher) {
        val encrypted = Base64.decode(readRecord().getString("account"), Base64.NO_WRAP)
        val authenticatedUserId = try {
            authenticatedCipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Fingerprint login changed. Sign in with your password and enable it again in POS Settings.", error)
        }
        require(userId != null && authenticatedUserId == userId) {
            "The saved Cashier account changed. Sign in with your password."
        }
    }

    fun disable() {
        file.delete()
        // Removing the marker disables login even if an unavailable Keystore
        // prevents cleanup of the old key. Password sign-in must still work.
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun readRecord() = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    private fun cipher() = Cipher.getInstance("AES/GCM/NoPadding")
}
