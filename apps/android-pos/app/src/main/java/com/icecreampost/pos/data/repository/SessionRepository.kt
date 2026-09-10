package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.ActivateDeviceRequest
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val api: SupabaseApi,
    private val sessionTokenStore: SessionTokenStore,
    private val deviceIdentity: DeviceIdentity,
) {
    fun observeSession(): Flow<AppSessionEntity?> = sessionDao.observeCurrent()

    suspend fun restoreStoredSession(allowExpiredForOfflineWork: Boolean = false): AppSessionEntity? {
        val current = sessionDao.getCurrent()
        val storedToken = current?.sessionToken?.takeIf { it.isNotBlank() }
        val isExpired = current?.expiresAt?.let { expiresAt ->
            runCatching { !Instant.parse(expiresAt).isAfter(Instant.now()) }.getOrDefault(true)
        } ?: false
        if (current != null && (storedToken == null || (isExpired && !allowExpiredForOfflineWork))) {
            sessionTokenStore.token = null
            sessionDao.clear()
            return null
        }
        sessionTokenStore.token = storedToken
        return current
    }

    suspend fun signIn(stallCode: String, email: String, password: String): AppSessionEntity {
        require(stallCode.isNotBlank()) { "Stall code is required." }
        require(email.isNotBlank()) { "Email is required." }
        require(password.isNotBlank()) { "Password is required." }
        val response = try {
            api.login(LoginRequest(stallCode.trim().uppercase(), email.trim(), password)).firstOrNull()
        } catch (error: HttpException) {
            if (error.code() == 401 || error.code() == 403) {
                throw IllegalArgumentException("The stall code, email, or password is incorrect, or this Cashier account is inactive.")
            }
            throw error
        } ?: error("The stall code, email, or password is incorrect.")
        require(response.role == "cashier") { "Use the Owner web dashboard for this account." }
        sessionTokenStore.token = response.sessionToken
        val session = AppSessionEntity(
                userId = response.userId,
                stallId = response.stallId,
                displayName = response.displayName,
                role = response.role,
                sessionToken = response.sessionToken,
                expiresAt = response.expiresAt,
                deviceId = response.deviceId.takeIf { response.isActivated },
                isActivated = response.isActivated && response.deviceId != null,
            )
        sessionDao.save(session)
        return session
    }

    suspend fun activate(deviceCode: String) {
        require(deviceCode.trim().isNotEmpty()) { "Device activation code is required." }
        val current = sessionDao.getCurrent() ?: error("Sign in before activating this device.")
        require(current.role == "cashier") { "Only a Cashier account can activate the POS." }
        val activation = try {
            api.activateDevice(ActivateDeviceRequest(deviceCode.trim(), deviceIdentity.id))
        } catch (error: HttpException) {
            if (error.code() == 401 || error.code() == 403) {
                throw IllegalArgumentException("That POS activation code is invalid or expired. Use the one-time code generated in Users & access, not the stall code.")
            }
            throw error
        }
        require(activation.stallId == current.stallId) { "The activation code belongs to another stall." }
        sessionDao.save(
            current.copy(
                deviceId = activation.deviceId,
                isActivated = true,
            ),
        )
    }

    suspend fun signOut() {
        sessionTokenStore.token = null
        sessionDao.clear()
    }
}
