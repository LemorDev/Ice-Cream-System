package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.ActivateDeviceRequest
import com.icecreampost.pos.data.remote.dto.PrepareReplacementRequest
import com.icecreampost.pos.data.remote.dto.RecoveredBusinessDay
import com.icecreampost.pos.data.remote.dto.RecoveredDayRequest
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.time.Instant
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
        if (session.isActivated && session.deviceId != null) {
            importRecoveredDay(session.stallId ?: error("The stall is missing."), session.deviceId,
                api.getRecoveredDay(RecoveredDayRequest(session.deviceId)))
        }
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
        importRecoveredDay(activation.stallId, activation.deviceId, activation.resumeDay)
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
        require(recovered.knownSales >= 0 && recovered.knownOrders >= 0 && recovered.knownDeductions >= 0 && recovered.knownProfitDeductions >= 0 && recovered.knownCogs >= 0 && recovered.knownWaste >= 0) {
            "Invalid recovery totals from IMS."
        }
        val day = (existing ?: BusinessDayEntity(
            id = recovered.id, stallId = stallId, deviceId = deviceId,
            cashierId = recovered.cashierId, businessDate = recovered.businessDate,
            openedAt = recovered.openedAt, openingNotes = recovered.openingNotes,
            updatedAt = Instant.now().toString(),
        )).copy(
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
        val now = Instant.now().toString()
        businessDayDao.upsert(businessDayDao.findByDate(stallId, recovered.businessDate)!!
            .copy(deviceId = deviceId, isRecoveryDay = true, isSynced = true, updatedAt = now))
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

    suspend fun signOut() {
        sessionTokenStore.token = null
        sessionDao.clear()
    }
}
