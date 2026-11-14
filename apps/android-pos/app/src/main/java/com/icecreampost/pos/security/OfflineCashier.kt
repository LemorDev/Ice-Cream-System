package com.icecreampost.pos.security

import com.icecreampost.pos.data.local.entity.AppSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Serializable
internal data class OfflineCashier(
    val reference: String,
    val userId: String,
    val stallId: String,
    val deviceId: String,
    val hardwareId: String,
    val displayName: String,
    val stallCode: String? = null,
    val employeeId: String? = null,
    val salt: ByteArray? = null,
    val passwordHash: ByteArray? = null,
    val cloudLoginRequired: Boolean = false,
) {
    fun matches(session: AppSessionEntity) =
        session.isActivated && session.role == "cashier" && userId == session.userId &&
            stallId == session.stallId && deviceId == session.deviceId

    fun session() = AppSessionEntity(
        userId = userId, stallId = stallId, deviceId = deviceId, displayName = displayName,
        role = "cashier", credentialRef = reference, isActivated = true,
    )

    suspend fun acceptsPassword(stallCode: String, employeeId: String, password: String): Boolean {
        if (this.stallCode != stallCode || this.employeeId != employeeId) return false
        val savedSalt = salt ?: return false
        val savedHash = passwordHash ?: return false
        return MessageDigest.isEqual(savedHash, derivePasswordHash(password, savedSalt))
    }
}

@Serializable
internal class OfflinePasswordProof(val stallCode: String, val employeeId: String, val salt: ByteArray, val hash: ByteArray)

internal suspend fun passwordProof(stallCode: String, employeeId: String, password: String): OfflinePasswordProof {
    val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    return OfflinePasswordProof(stallCode, employeeId, salt, derivePasswordHash(password, salt))
}

private suspend fun derivePasswordHash(password: String, salt: ByteArray): ByteArray = withContext(Dispatchers.Default) {
    val chars = password.toCharArray()
    val spec = PBEKeySpec(chars, salt, 600_000, 256)
    try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
        chars.fill('\u0000')
    }
}
