package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.ActivateDeviceRequest
import com.icecreampost.pos.data.remote.dto.PrepareReplacementRequest
import com.icecreampost.pos.data.remote.dto.RecoveredBusinessDay
import com.icecreampost.pos.data.remote.dto.RecoveredDayRequest
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.security.PosCredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val api: SupabaseApi,
    private val sessionTokenStore: SessionTokenStore,
    private val deviceIdentity: DeviceIdentity,
    private val businessDayDao: BusinessDayDao,
    private val syncStateDao: SyncStateDao,
    private val credentials: PosCredentialVault,
) {
    private val sessionLock = Mutex()
    private val revocationLock = Mutex()

    fun observeSession(): Flow<AppSessionEntity?> = sessionDao.observeCurrent()
    fun hasCloudToken(): Boolean = sessionTokenStore.token != null

    suspend fun restoreStoredSession(allowExpiredForOfflineWork: Boolean = false, activateToken: Boolean = true): AppSessionEntity? = sessionLock.withLock {
        val current = sessionDao.getCurrent()
        if (current?.stallId != null && !isCompatibleStall(current.stallId)) {
            sessionTokenStore.token = null
            credentials.clearSession()
            sessionDao.clear()
            return@withLock null
        }
        val storedToken = current?.credentialRef?.let(credentials::readSession)?.takeIf { it.isNotBlank() }
        val isExpired = current?.expiresAt?.let { expiresAt ->
            runCatching { !Instant.parse(expiresAt).isAfter(Instant.now()) }.getOrDefault(true)
        } ?: false
        if (current != null && (storedToken == null || (isExpired && !allowExpiredForOfflineWork))) {
            sessionTokenStore.token = null
            credentials.clearSession()
            sessionDao.clear()
            return@withLock null
        }
        sessionTokenStore.token = if (activateToken && !isExpired) storedToken else null
        current
    }

    suspend fun signIn(stallCode: String, employeeId: String, password: String): AppSessionEntity = sessionLock.withLock {
        require(stallCode.isNotBlank()) { "Stall code is required." }
        require(employeeId.isNotBlank()) { "Employee ID is required." }
        require(password.isNotEmpty()) { "Password is required." }
        val invalidCredentials = "Check the stall code, employee ID, and password. If you’ve tried several times, wait 15 minutes before trying again."
        val response = try {
            api.login(LoginRequest(stallCode.trim().uppercase(), employeeId.trim().uppercase(), password)).firstOrNull()
        } catch (httpError: HttpException) {
            when (httpError.code()) {
                429 -> error("Too many sign-in attempts from this network. Wait a minute and try again.")
                401, 403 -> throw IllegalArgumentException(invalidCredentials)
                else -> throw httpError
            }
        } ?: error(invalidCredentials)
        require(response.role == "cashier") { "Use the Owner web dashboard for this account." }
        check(isCompatibleStall(response.stallId) &&
            (sessionDao.getCurrent()?.stallId == null || sessionDao.getCurrent()?.stallId == response.stallId)) {
            "This phone contains data from another stall. Use that stall's account and resolve its queue on this phone; a different stall needs a separate POS device."
        }
        val previousToken = sessionTokenStore.token
        val previousReference = sessionDao.getCurrent()?.credentialRef
        val previousCredential = previousReference?.let(credentials::readSession)
        val credentialRef = UUID.randomUUID().toString()
        sessionTokenStore.token = response.sessionToken
        val session = AppSessionEntity(
                userId = response.userId,
                stallId = response.stallId,
                displayName = response.displayName,
                role = response.role,
                credentialRef = credentialRef,
                expiresAt = response.expiresAt,
                deviceId = response.deviceId.takeIf { response.isActivated },
                isActivated = response.isActivated && response.deviceId != null,
            )
        try {
            if (session.isActivated && session.deviceId != null) {
                importRecoveredDay(session.stallId ?: error("The stall is missing."), session.deviceId,
                    api.getResumableDay(RecoveredDayRequest(session.deviceId)).day)
            }
            // Persist the assignment even when there are no sales yet. Sign-out
            // must never make another stall's catalog or queue visible on this phone.
            syncStateDao.upsert(SyncStateEntity(key = "device-stall", value = response.stallId))
            credentials.writeSession(credentialRef, response.sessionToken)
            sessionDao.save(session)
        } catch (error: Throwable) {
            sessionTokenStore.token = previousToken
            if (previousReference != null && previousCredential != null) {
                runCatching { credentials.writeSession(previousReference, previousCredential) }
            } else {
                credentials.clearSession()
            }
            throw error
        }
        session
    }

    suspend fun activate(deviceCode: String) {
        require(deviceCode.trim().isNotEmpty()) { "Device activation code is required." }
        val current = sessionDao.getCurrent() ?: error("Sign in before activating this device.")
        require(current.role == "cashier") { "Only a Cashier account can activate the POS." }
        val activation = try {
            api.activateDevice(ActivateDeviceRequest(deviceCode.trim(), deviceIdentity.id))
        } catch (error: HttpException) {
            if (error.code() == 401 || error.code() == 403) {
                throw IllegalArgumentException("That code is invalid or expired. Ask the System Administrator for a new activation or enrollment code.")
            }
            throw error
        }
        require(activation.stallId == current.stallId) { "The activation code belongs to another stall." }
        val recovered = activation.resumeDay ?: api.getResumableDay(RecoveredDayRequest(activation.deviceId)).day
        importRecoveredDay(activation.stallId, activation.deviceId, recovered)
        sessionDao.save(
            current.copy(
                deviceId = activation.deviceId,
                isActivated = true,
            ),
        )
    }

    private suspend fun importRecoveredDay(stallId: String, deviceId: String, recovered: RecoveredBusinessDay?) {
        if (recovered == null) return
        val existing = businessDayDao.findByDate(stallId, recovered.businessDate)
        check(existing == null || existing.id == recovered.id) {
            "This phone has a different local operating day for ${recovered.businessDate}. Resolve its queued data before continuing."
        }
        // Existing local sales already contribute to this day's totals. Import
        // server-known totals only when this installation has no local day.
        if (existing != null) return
        require(recovered.knownSales >= 0 && recovered.knownOrders >= 0 && recovered.knownDeductions >= 0 && recovered.knownProfitDeductions >= 0 && recovered.knownCogs >= 0 && recovered.knownWaste >= 0) {
            "Invalid recovery totals from IMS."
        }
        val day = BusinessDayEntity(
            id = recovered.id, stallId = stallId, deviceId = deviceId,
            cashierId = recovered.cashierId, businessDate = recovered.businessDate,
            openedAt = recovered.openedAt, openingNotes = recovered.openingNotes,
            updatedAt = Instant.now().toString(),
        ).copy(
            deviceId = deviceId,
            recoveryKnownSalesCents = (recovered.knownSales * 100).roundToLong(),
            recoveryKnownOrders = recovered.knownOrders,
            recoveryKnownDeductionsCents = (recovered.knownDeductions * 100).roundToLong(),
            recoveryKnownProfitDeductionsCents = (recovered.knownProfitDeductions * 100).roundToLong(),
            recoveryKnownCogsCents = (recovered.knownCogs * 100).roundToLong(),
            recoveryKnownWasteCents = (recovered.knownWaste * 100).roundToLong(),
            isRecoveryDay = true, isSynced = true, updatedAt = Instant.now().toString(),
        )
        businessDayDao.upsert(day)
    }

    suspend fun prepareReplacement() {
        val current = sessionDao.getCurrent() ?: error("Sign in before preparing this POS for replacement.")
        val deviceId = current.deviceId?.takeIf { current.isActivated }
            ?: error("This POS is not activated.")
        check(api.prepareReplacement(PrepareReplacementRequest(deviceId)).status == "ready") {
            "The IMS did not confirm replacement readiness."
        }
        sessionDao.save(current.copy(transferReady = true))
    }

    suspend fun signOut(): Boolean {
        val token = sessionLock.withLock {
            val current = sessionDao.getCurrent()
            val bearer = current?.credentialRef?.let { runCatching { credentials.readSession(it) }.getOrNull() }
                ?: sessionTokenStore.token
            sessionTokenStore.token = null
            if (bearer != null && current?.expiresAt != null) {
                runCatching { credentials.queueRevocation(bearer, current.expiresAt) }
            }
            credentials.clearSession()
            sessionDao.clear()
            bearer
        } ?: return true
        return revocationLock.withLock { revoke(token) }
    }

    suspend fun retryPendingRevocations() = revocationLock.withLock {
        for (entry in runCatching { credentials.pendingRevocations() }.getOrDefault(emptyList())) {
            if (!revoke(entry.token)) break
        }
    }

    private suspend fun revoke(token: String): Boolean = try {
        withTimeoutOrNull(5_000) {
            api.revokeSession(token)
            credentials.removeRevocation(token)
            true
        } ?: false
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun isCompatibleStall(stallId: String): Boolean {
        val bound = syncStateDao.find("device-stall")?.value
        val persisted = syncStateDao.findPersistedStallIds()
        return (bound == null || bound == stallId) && persisted.all { it == stallId }
    }
}
