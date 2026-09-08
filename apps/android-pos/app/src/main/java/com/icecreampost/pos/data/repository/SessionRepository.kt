package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.ActivateDeviceRequest
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import kotlinx.coroutines.flow.Flow
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

    suspend fun restoreStoredSession() {
        val current = sessionDao.getCurrent()
        val storedToken = current?.sessionToken?.takeIf { it.isNotBlank() }
        sessionTokenStore.token = storedToken
        if (current != null && storedToken == null) {
            sessionDao.clear()
        }
    }

    suspend fun signIn(email: String, password: String) {
        require(email.isNotBlank()) { "Email is required." }
        require(password.isNotBlank()) { "Password is required." }
        val response = api.login(LoginRequest(email.trim(), password)).firstOrNull()
            ?: error("Invalid email or password.")
        require(response.role == "cashier") { "Use the Owner web dashboard for this account." }
        sessionTokenStore.token = response.sessionToken
        sessionDao.save(
            AppSessionEntity(
                userId = response.userId,
                stallId = response.stallId,
                displayName = response.displayName,
                role = response.role,
                sessionToken = response.sessionToken,
                expiresAt = response.expiresAt,
            ),
        )
    }

    suspend fun activate(deviceCode: String) {
        require(deviceCode.trim().isNotEmpty()) { "Device activation code is required." }
        val current = sessionDao.getCurrent() ?: error("Sign in before activating this device.")
        require(current.role == "cashier") { "Only a Cashier account can activate the POS." }
        val activation = api.activateDevice(ActivateDeviceRequest(deviceCode.trim(), deviceIdentity.id))
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
