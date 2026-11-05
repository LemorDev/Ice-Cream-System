package com.icecreampost.pos.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PosDeviceKey @Inject constructor() {
    private val alias = "coolerz-pos-device-v1"

    @Synchronized
    fun sign(message: String): DeviceProof {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return try {
            signWith(getOrCreate(store), message)
        } catch (_: KeyPermanentlyInvalidatedException) {
            store.deleteEntry(alias)
            signWith(getOrCreate(store), message)
        }
    }

    private fun getOrCreate(store: KeyStore): KeyStore.PrivateKeyEntry {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build())
                generateKeyPair()
            }
        }
        return store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    private fun signWith(entry: KeyStore.PrivateKeyEntry, message: String): DeviceProof {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(message.toByteArray(Charsets.UTF_8))
            sign()
        }
        return DeviceProof(Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP),
            Base64.encodeToString(signature, Base64.NO_WRAP))
    }
}

data class DeviceProof(val publicKey: String, val signature: String)
