package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val api: SupabaseApi,
    private val sessionTokenStore: SessionTokenStore,
) {
    fun observeSession(): Flow<AppSessionEntity?> = sessionDao.observeCurrent()

    suspend fun signIn(email: String, password: String) {
        require(email.isNotBlank()) { "Email is required." }
        require(password.isNotBlank()) { "Password is required." }
        val response = api.login(LoginRequest(email.trim(), password)).firstOrNull()
            ?: error("Invalid email or password.")
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
        // Device activation is intentionally local until the device endpoint is added.
        val current = sessionDao.getCurrent()
        sessionDao.save(
            AppSessionEntity(
                userId = current?.userId,
                stallId = current?.stallId,
                displayName = "Coolerz Cashier",
                role = "cashier",
                sessionToken = current?.sessionToken,
                expiresAt = current?.expiresAt,
                isActivated = true,
            ),
        )
    }

    suspend fun signOut() {
        sessionTokenStore.token = null
        sessionDao.clear()
    }
}
