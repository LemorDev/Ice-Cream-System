package com.icecreampost.pos.data.repository

import com.icecreampost.pos.core.time.parsePosTimestamp
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.LoginResponse
import com.icecreampost.pos.data.remote.dto.FingerprintCredentialRequest
import com.icecreampost.pos.data.remote.dto.ActivateDeviceRequest
import com.icecreampost.pos.data.remote.dto.PrepareReplacementRequest
import com.icecreampost.pos.data.remote.dto.RecoveredBusinessDay
import com.icecreampost.pos.data.remote.dto.RecoveredDayRequest
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.security.OfflineCashier
import com.icecreampost.pos.security.OfflinePasswordProof
import com.icecreampost.pos.security.PosCredentialVault
import com.icecreampost.pos.security.passwordProof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToLong
import javax.inject.Inject
import javax.inject.Singleton

private const val INVALID_CASHIER_LOGIN =
    "Check the stall code, employee ID, and password. If you’ve tried several times, wait 15 minutes before trying again."

class FingerprintSignInRejectedException : IllegalStateException(
    "Fingerprint sign-in needs to be set up again. Sign in with your password, then enable it in Settings.",
)

class CloudSignInRequiredException(cause: Throwable? = null) : IllegalStateException(
    "Sign in online to reconnect cloud sync. Your records are saved on this device.", cause,
)

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

    suspend fun offlineSignInDetails(): Pair<String, String>? = sessionLock.withLock {
        val saved = readOfflineCashier() ?: return@withLock null
        val stallCode = saved.stallCode ?: return@withLock null
        val employeeId = saved.employeeId ?: return@withLock null
        stallCode to employeeId
    }

    suspend fun isLocked(): Boolean = sessionLock.withLock {
        sessionDao.getCurrent() != null && credentials.isLocked()
    }

    suspend fun checkFingerprintUnlock(session: AppSessionEntity) = sessionLock.withLock {
        check(readOfflineCashier()?.let { it.matches(session) && it.cloudLoginRequired } != true) {
            "Connect to the internet and sign in with your password to verify this cashier again."
        }
    }

    suspend fun lock() = sessionLock.withLock {
        val current = sessionDao.getCurrent() ?: error("Sign in before locking the POS.")
        check(current.isActivated) { "Activate this POS before locking it." }
        credentials.setLocked(true)
    }

    suspend fun unlock(session: AppSessionEntity) = sessionLock.withLock {
        val current = sessionDao.getCurrent()
        check(current?.credentialRef == session.credentialRef && current?.userId == session.userId) {
            "The saved cashier changed. Sign in again."
        }
        credentials.setLocked(false)
    }

    suspend fun restoreStoredSession(allowExpiredForOfflineWork: Boolean = false, activateToken: Boolean = true): AppSessionEntity? = sessionLock.withLock {
        val current = sessionDao.getCurrent()
        if (current?.stallId != null && !isCompatibleStall(current.stallId)) {
            sessionTokenStore.token = null
            credentials.clearSession()
            sessionDao.clear()
            return@withLock null
        }
        val storedToken = current?.credentialRef?.let(credentials::readSession)?.takeIf { it.isNotBlank() }
        val isExpired = current?.cloudTokenExpired() == true
        val canWorkOffline = current != null && allowExpiredForOfflineWork && current.isActivated &&
            (storedToken != null || readOfflineCashier()?.let { it.matches(current) && it.reference == current.credentialRef } == true)
        if (current != null && !canWorkOffline && (storedToken == null || isExpired)) {
            sessionTokenStore.token = null
            credentials.clearSession()
            sessionDao.clear()
            return@withLock null
        }
        sessionTokenStore.token = if (activateToken && !isExpired) storedToken else null
        current
    }

    suspend fun signIn(
        stallCode: String,
        employeeId: String,
        password: String,
        requireActivation: Boolean = false,
    ): AppSessionEntity = sessionLock.withLock {
        require(stallCode.isNotBlank()) { "Stall code is required." }
        require(employeeId.isNotBlank()) { "Employee ID is required." }
        require(password.isNotEmpty()) { "Password is required." }
        val code = stallCode.trim().uppercase()
        val employee = employeeId.trim().uppercase()
        val cached = readOfflineCashier()
        val current = sessionDao.getCurrent()
        val savedPassword = cached?.takeIf {
            !it.cloudLoginRequired && current?.transferReady != true &&
                it.stallCode == code && it.employeeId == employee && it.passwordHash != null
        }
        if (savedPassword != null) {
            credentials.checkOfflinePasswordAttempts()
            if (savedPassword.acceptsPassword(code, employee, password)) {
                return@withLock signInOffline(savedPassword, current)
            }
            credentials.recordOfflinePasswordFailure()
        }
        try {
            signInOnline(code, employee, password, requireActivation)
        } catch (error: IOException) {
            if (savedPassword != null) throw IllegalArgumentException(INVALID_CASHIER_LOGIN)
            val message = if (cached?.cloudLoginRequired == true) "Connect to the internet to verify this cashier again."
                else "Connect to the internet for the first sign-in on this POS or to switch cashiers."
            throw IOException(message, error)
        }
    }

    suspend fun reconnectCloud(stallCode: String, employeeId: String, password: String): AppSessionEntity =
        sessionLock.withLock {
            val current = sessionDao.getCurrent()?.takeIf { it.isActivated && !it.transferReady }
                ?: error("Sign in on this activated POS before reconnecting cloud sync.")
            try {
                signInOnline(stallCode.trim().uppercase(), employeeId.trim().uppercase(), password,
                    requireActivation = true, expectedCashier = current)
            } catch (error: IOException) {
                throw IOException("Connect to the internet to reconnect cloud sync.", error)
            }
        }

    private suspend fun signInOnline(
        code: String,
        employee: String,
        password: String,
        requireActivation: Boolean,
        expectedCashier: AppSessionEntity? = null,
    ): AppSessionEntity {
        val response = try {
            api.login(LoginRequest(code, employee, password)).firstOrNull()
        } catch (httpError: HttpException) {
            when (httpError.code()) {
                429 -> error("Too many sign-in attempts from this network. Wait a minute and try again.")
                401, 403 -> throw IllegalArgumentException(INVALID_CASHIER_LOGIN)
                else -> throw httpError
            }
        }
        requireNotNull(response) { INVALID_CASHIER_LOGIN }
        if (expectedCashier != null) {
            require(response.userId == expectedCashier.userId && response.stallId == expectedCashier.stallId) {
                "Use the current cashier account to reconnect cloud sync."
            }
            if (!response.isActivated || response.deviceId != expectedCashier.deviceId) {
                markCloudLoginRequired(expectedCashier)
                error("This cashier has no active registration on this POS. Check the account and device in IMS.")
            }
        }
        check(!requireActivation || response.isActivated && response.deviceId != null) {
            "This cashier has no active registration on this POS. Check the account and device in IMS."
        }
        val proof = passwordProof(code, employee, password)
        val signedIn = saveSession(response)
        if (signedIn.isActivated) rememberOfflineCashier(signedIn, proof)
        else {
            credentials.writeActivationPassword(requireNotNull(signedIn.credentialRef), proof)
            credentials.clearOfflineCashier()
        }
        credentials.clearOfflinePasswordAttempts()
        return signedIn
    }

    suspend fun signInWithFingerprint(userId: String, secret: String?): AppSessionEntity = sessionLock.withLock {
        val current = sessionDao.getCurrent()
        val cached = readOfflineCashier()?.takeIf { it.userId == userId }
        if (cached != null && !cached.cloudLoginRequired && current?.transferReady != true) {
            return@withLock signInOffline(cached, current)
        }
        if (current?.isActivated == true && current.userId == userId && !current.transferReady &&
            current.stallId?.let { isCompatibleStall(it) } == true &&
            current.credentialRef?.let(credentials::readSession) != null &&
            cached == null) {
            return@withLock current
        }
        requireNotNull(secret) { "Sign in with your password to set up offline access on this POS." }
        val response = try {
            api.loginWithFingerprint(FingerprintCredentialRequest(secret)).firstOrNull()
        } catch (error: IOException) {
            throw IOException("Connect to the internet to set up or verify fingerprint sign-in on this POS.", error)
        } catch (error: HttpException) {
            when (error.code()) {
                401, 403 -> throw FingerprintSignInRejectedException()
                404 -> error("Fingerprint sign-in is unavailable on the server. Use your password.")
                429 -> error("Too many sign-in attempts from this network. Wait a minute and try again.")
                else -> throw error
            }
        }
        if (response == null || response.userId != userId || !response.isActivated || response.deviceId == null) {
            throw FingerprintSignInRejectedException()
        }
        val signedIn = saveSession(response)
        rememberOfflineCashier(signedIn)
        signedIn
    }

    private fun readOfflineCashier(): OfflineCashier? =
        credentials.readOfflineCashier()?.takeIf { it.hardwareId == deviceIdentity.id }

    private suspend fun signInOffline(cashier: OfflineCashier, current: AppSessionEntity?): AppSessionEntity {
        check(isCompatibleStall(cashier.stallId) && (current == null || cashier.matches(current))) {
            "The saved cashier or device changed. Connect to the internet and sign in again."
        }
        val open = businessDayDao.findOpen(cashier.stallId)
        check(open == null || open.deviceId == cashier.deviceId) {
            "Sync before signing in. This phone has records from the previous POS device."
        }
        if (current != null) {
            sessionTokenStore.token = if (current.cloudTokenExpired()) null else
                current.credentialRef?.let(credentials::readSession)
            credentials.clearOfflinePasswordAttempts()
            return current
        }
        sessionTokenStore.token = null
        val session = cashier.session()
        sessionDao.save(session)
        credentials.clearOfflinePasswordAttempts()
        return session
    }

    private fun rememberOfflineCashier(session: AppSessionEntity, proof: OfflinePasswordProof? = null) {
        val saved = readOfflineCashier()?.takeIf { it.matches(session) }
        credentials.writeOfflineCashier(OfflineCashier(
            reference = requireNotNull(session.credentialRef),
            userId = requireNotNull(session.userId), stallId = requireNotNull(session.stallId),
            deviceId = requireNotNull(session.deviceId), hardwareId = deviceIdentity.id,
            displayName = session.displayName,
            stallCode = proof?.stallCode ?: saved?.stallCode,
            employeeId = proof?.employeeId ?: saved?.employeeId,
            salt = proof?.salt ?: saved?.salt,
            passwordHash = proof?.hash ?: saved?.passwordHash,
        ))
        credentials.clearActivationPassword()
    }

    private fun AppSessionEntity.cloudTokenExpired(withinSeconds: Long = 0) = expiresAt?.let {
        runCatching { !parsePosTimestamp(it).isAfter(Instant.now().plusSeconds(withinSeconds)) }.getOrDefault(true)
    } ?: false

    suspend fun prepareCloudSync() {
        val (current, token) = sessionLock.withLock {
            val current = sessionDao.getCurrent()?.takeIf { it.isActivated } ?: throw CloudSignInRequiredException()
            check(!current.transferReady) { "Sign in again before syncing this POS." }
            val token = current.credentialRef?.let(credentials::readSession)?.takeIf { it.isNotBlank() }
                ?: throw CloudSignInRequiredException()
            if (!current.cloudTokenExpired(withinSeconds = 120)) {
                sessionTokenStore.token = token
                return
            }
            current to token
        }
        // Let this cashier open the day or unlock locally while the network request is in flight.
        val renewedUntil = try {
            api.renewPosSession(token)
        } catch (error: HttpException) {
            if (error.code() in listOf(401, 403, 404)) {
                sessionLock.withLock {
                    val saved = sessionDao.getCurrent()
                    val reference = saved?.credentialRef
                    if (reference != current.credentialRef || reference?.let(credentials::readSession) != token) {
                        throw RetryableSyncException("The POS sign-in changed. Try syncing again.")
                    }
                    val rejection = error.response()?.errorBody()?.string().orEmpty()
                    if ("POS_SESSION_RENEWAL_DENIED" in rejection) markCloudLoginRequired(current)
                }
                throw CloudSignInRequiredException(error)
            }
            throw error
        }
        val renewal = parsePosTimestamp(renewedUntil)
        check(renewal.isAfter(Instant.now())) { "Cloud session renewal expired." }
        sessionLock.withLock {
            val saved = sessionDao.getCurrent()
            val reference = saved?.credentialRef
            if (saved == null || saved.transferReady || reference != current.credentialRef ||
                reference?.let(credentials::readSession) != token) {
                throw RetryableSyncException("The POS sign-in changed. Try syncing again.")
            }
            val savedExpiry = saved.expiresAt?.let { runCatching { parsePosTimestamp(it) }.getOrNull() }
            if (savedExpiry == null || renewal.isAfter(savedExpiry)) sessionDao.save(saved.copy(expiresAt = renewedUntil))
            sessionTokenStore.token = token
        }
    }

    private fun markCloudLoginRequired(current: AppSessionEntity) {
        val cached = readOfflineCashier()?.takeIf { it.matches(current) && !it.cloudLoginRequired } ?: return
        credentials.writeOfflineCashier(cached.copy(cloudLoginRequired = true))
    }

    suspend fun setFingerprintCredential(userId: String, secret: String?) {
        prepareCloudSync()
        sessionLock.withLock {
            val current = sessionDao.getCurrent()
            check(current != null && current.userId == userId && current.isActivated && !current.transferReady) {
                "Sign in on an activated POS before changing fingerprint login."
            }
            val saved = try {
                api.setFingerprintCredential(FingerprintCredentialRequest(secret))
            } catch (error: HttpException) {
                when (error.code()) {
                    401, 403 -> error("Sign in again before enabling fingerprint sign-in.")
                    404 -> error("Fingerprint sign-in is unavailable on the server.")
                    else -> throw error
                }
            }
            check(saved) { "The server could not save fingerprint sign-in." }
        }
    }

    private suspend fun saveSession(response: LoginResponse): AppSessionEntity {
        require(response.role == "cashier") { "Use the Owner web dashboard for this account." }
        val previousSession = sessionDao.getCurrent()
        check(isCompatibleStall(response.stallId) &&
            (previousSession?.stallId == null || previousSession.stallId == response.stallId)) {
            "This phone contains data from another stall. Use that stall's account and resolve its queue on this phone; a different stall needs a separate POS device."
        }
        val previousToken = sessionTokenStore.token
        val previousReference = previousSession?.credentialRef
        val previousCredential = previousReference?.let(credentials::readSession)
        val credentialRef = previousReference?.takeIf {
            previousSession.userId == response.userId && previousSession.deviceId == response.deviceId &&
                previousSession.stallId == response.stallId &&
                previousSession.isActivated && response.isActivated
        } ?: UUID.randomUUID().toString()
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
                val stallId = requireNotNull(session.stallId)
                val cachedOpen = businessDayDao.findOpen(stallId)
                val recovered = api.getResumableDay(RecoveredDayRequest(session.deviceId)).day
                reconcileOperatingDay(stallId, session.deviceId, cachedOpen, recovered)
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
        return session
    }

    suspend fun activate(deviceCode: String) = sessionLock.withLock {
        require(deviceCode.trim().isNotEmpty()) { "Device activation code is required." }
        val current = sessionDao.getCurrent() ?: error("Sign in before activating this device.")
        require(current.role == "cashier") { "Only a Cashier account can activate the POS." }
        val stallId = requireNotNull(current.stallId) { "The stall is missing." }
        val cachedOpen = businessDayDao.findOpen(stallId)
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
        reconcileOperatingDay(activation.stallId, activation.deviceId, cachedOpen, recovered)
        val activated = current.copy(deviceId = activation.deviceId, isActivated = true)
        val proof = current.credentialRef?.let(credentials::readActivationPassword)
        sessionDao.save(activated)
        rememberOfflineCashier(activated, proof)
    }

    suspend fun refreshPreviousDeviceDay() = sessionLock.withLock {
        val session = sessionDao.getCurrent() ?: return@withLock
        if (!session.isActivated || session.transferReady || !hasCloudToken()) return@withLock
        val stallId = session.stallId ?: return@withLock
        val deviceId = session.deviceId ?: return@withLock
        val cachedOpen = businessDayDao.findOpen(stallId) ?: return@withLock
        if (cachedOpen.deviceId == deviceId) return@withLock
        val recovered = api.getResumableDay(RecoveredDayRequest(deviceId)).day
        reconcileOperatingDay(stallId, deviceId, cachedOpen, recovered)
    }

    private suspend fun reconcileOperatingDay(
        stallId: String,
        deviceId: String,
        cachedOpen: BusinessDayEntity?,
        recovered: RecoveredBusinessDay?,
    ) {
        businessDayDao.reconcileActiveDay(stallId, deviceId, cachedOpen, recovered?.toEntity(stallId, deviceId))
    }

    private fun RecoveredBusinessDay.toEntity(stallId: String, deviceId: String): BusinessDayEntity {
        require(knownSales >= 0 && knownOrders >= 0 && knownDeductions >= 0 && knownProfitDeductions >= 0 && knownCogs >= 0 && knownWaste >= 0) {
            "Invalid recovery totals from IMS."
        }
        return BusinessDayEntity(
            id = id, stallId = stallId, deviceId = deviceId,
            cashierId = cashierId, businessDate = businessDate, openedAt = openedAt, openingNotes = openingNotes,
            recoveryKnownSalesCents = (knownSales * 100).roundToLong(),
            recoveryKnownOrders = knownOrders,
            recoveryKnownDeductionsCents = (knownDeductions * 100).roundToLong(),
            recoveryKnownProfitDeductionsCents = (knownProfitDeductions * 100).roundToLong(),
            recoveryKnownCogsCents = (knownCogs * 100).roundToLong(),
            recoveryKnownWasteCents = (knownWaste * 100).roundToLong(),
            isRecoveryDay = true, isSynced = true, updatedAt = Instant.now().toString(),
        )
    }

    suspend fun prepareReplacement() = sessionLock.withLock {
        val current = sessionDao.getCurrent() ?: error("Sign in before preparing this POS for replacement.")
        val deviceId = current.deviceId?.takeIf { current.isActivated }
            ?: error("This POS is not activated.")
        check(api.prepareReplacement(PrepareReplacementRequest(deviceId)).status == "ready") {
            "The IMS did not confirm replacement readiness."
        }
        sessionDao.save(current.copy(transferReady = true))
        credentials.clearOfflineCashier()
    }

    suspend fun signOut(): Boolean {
        val token = sessionLock.withLock {
            val current = sessionDao.getCurrent()
            val bearer = current?.credentialRef?.let { runCatching { credentials.readSession(it) }.getOrNull() }
                ?: sessionTokenStore.token
            sessionTokenStore.token = null
            val expiresAt = current?.expiresAt
            if (bearer != null && expiresAt != null) {
                runCatching {
                    val revokeUntil = if (current.isActivated) parsePosTimestamp(expiresAt).plusSeconds(30 * 86_400L).toString()
                        else expiresAt
                    credentials.queueRevocation(bearer, revokeUntil)
                }
            }
            credentials.clearSession()
            credentials.setLocked(false)
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
