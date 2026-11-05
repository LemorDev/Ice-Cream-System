package com.icecreampost.pos.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PosCredentialVault @Inject constructor(@ApplicationContext context: Context) {
    private val active = AtomicFile(File(context.noBackupFilesDir, "pos-session.enc"))
    private val revocations = AtomicFile(File(context.noBackupFilesDir, "pos-revocations.enc"))
    private val keyAlias = "coolerz-pos-session-v1"

    @Synchronized
    fun readSession(reference: String): String? = read(active, reference)

    @Synchronized
    fun writeSession(reference: String, token: String) = write(active, reference, token)

    @Synchronized
    fun clearSession() = active.delete()

    @Synchronized
    fun queueRevocation(token: String, expiresAt: String) {
        val pending = pendingRevocations()
        if (pending.none { it.token == token }) {
            saveRevocations(pending + PendingRevocation(token, expiresAt))
        }
    }

    @Synchronized
    fun pendingRevocations(): List<PendingRevocation> {
        val stored = read(revocations, "logout") ?: return emptyList()
        val array = JSONArray(stored)
        val now = Instant.now()
        val pending = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            PendingRevocation(entry.getString("token"), entry.getString("expiresAt"))
        }.filter { runCatching { Instant.parse(it.expiresAt).isAfter(now) }.getOrDefault(false) }
        if (pending.size != array.length()) saveRevocations(pending)
        return pending
    }

    @Synchronized
    fun removeRevocation(token: String) = saveRevocations(pendingRevocations().filterNot { it.token == token })

    private fun saveRevocations(pending: List<PendingRevocation>) {
        if (pending.isEmpty()) {
            revocations.delete()
            return
        }
        val array = JSONArray()
        pending.forEach { array.put(JSONObject().put("token", it.token).put("expiresAt", it.expiresAt)) }
        write(revocations, "logout", array.toString())
    }

    private fun read(file: AtomicFile, purpose: String): String? {
        val payload = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        if (payload.size < 28) return null
        return try {
            val key = keyStore().getKey(keyAlias, null) as? SecretKey ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload, 0, 12))
                updateAAD(purpose.toByteArray(Charsets.UTF_8))
                doFinal(payload, 12, payload.size - 12).toString(Charsets.UTF_8)
            }
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun write(file: AtomicFile, purpose: String, value: String) {
        val key = keyStore().getKey(keyAlias, null) as? SecretKey ?: generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

class PendingRevocation(val token: String, val expiresAt: String)
