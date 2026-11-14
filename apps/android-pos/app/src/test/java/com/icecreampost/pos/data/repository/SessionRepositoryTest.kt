package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.ActivateDeviceResponse
import com.icecreampost.pos.data.remote.dto.LoginResponse
import com.icecreampost.pos.data.remote.dto.RecoveredDayResponse
import com.icecreampost.pos.data.remote.dto.RecoveredBusinessDay
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.security.PosCredentialVault
import com.icecreampost.pos.security.OfflineCashier
import com.icecreampost.pos.security.passwordProof
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {
    private val sessionDao = mockk<SessionDao>(relaxed = true)
    private val api = mockk<SupabaseApi>()
    private val tokenStore = SessionTokenStore()
    private val credentials = mockk<PosCredentialVault>(relaxed = true)
    private val deviceIdentity = mockk<DeviceIdentity>()
    private val syncStateDao = mockk<SyncStateDao>(relaxed = true)
    private val businessDayDao = mockk<BusinessDayDao>(relaxed = true)
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        every { deviceIdentity.id } returns "hardware-1"
        coEvery { syncStateDao.find("device-stall") } returns null
        coEvery { syncStateDao.findPersistedStallIds() } returns emptyList()
        coEvery { sessionDao.getCurrent() } returns null
        coEvery { businessDayDao.findOpen(any()) } returns null
        every { credentials.readSession(any()) } answers { firstArg() }
        repository = SessionRepository(sessionDao, api, tokenStore, deviceIdentity,
            businessDayDao, syncStateDao, credentials)
    }

    @Test
    fun `owner accounts are rejected by the Android POS`() {
        coEvery { api.login(any()) } returns listOf(login(role = "owner"))

        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.signIn("MAIN-001", "owner@example.com", "password") }
        }

        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { sessionDao.save(any()) }
    }

    @Test
    fun `cashier login stores the cloud identity and token`() = runTest {
        coEvery { api.login(any()) } returns listOf(login(role = "cashier"))
        val saved = slot<AppSessionEntity>()
        coEvery { sessionDao.save(capture(saved)) } returns Unit

        repository.signIn(" main-001 ", "cashier@example.com", "password")

        coVerify(exactly = 1) { api.login(match { it.stallCode == "MAIN-001" }) }
        assertEquals("token-1", tokenStore.token)
        assertEquals("cashier", saved.captured.role)
        assertEquals("stall-1", saved.captured.stallId)
        assertEquals(false, saved.captured.isActivated)
        org.junit.Assert.assertNotEquals("token-1", saved.captured.credentialRef)
        io.mockk.verify { credentials.writeSession(requireNotNull(saved.captured.credentialRef), "token-1") }
    }

    @Test
    fun `another stall cannot sign in over persisted local sales`() = runTest {
        coEvery { api.login(any()) } returns listOf(login(role = "cashier"))
        coEvery { syncStateDao.findPersistedStallIds() } returns listOf("other-stall")

        assertThrows(IllegalStateException::class.java) {
            runTest { repository.signIn("MAIN-001", "cashier@example.com", "password") }
        }

        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { sessionDao.save(any()) }
        coVerify(exactly = 0) { syncStateDao.upsert(any()) }
    }

    @Test
    fun `a phone remains bound to its original stall after sign out and an empty queue`() = runTest {
        coEvery { api.login(any()) } returns listOf(login(role = "cashier"))
        coEvery { syncStateDao.find("device-stall") } returns SyncStateEntity(
            key = "device-stall", value = "other-stall",
        )

        assertThrows(IllegalStateException::class.java) {
            runTest { repository.signIn("MAIN-001", "cashier@example.com", "password") }
        }

        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { sessionDao.save(any()) }
    }

    @Test
    fun `cashier login restores an active registration for the same device`() = runTest {
        coEvery { api.getResumableDay(any()) } returns RecoveredDayResponse(null)
        coEvery { api.login(any()) } returns listOf(
            login(role = "cashier", deviceId = "device-1", isActivated = true),
        )
        val saved = slot<AppSessionEntity>()
        coEvery { sessionDao.save(capture(saved)) } returns Unit

        repository.signIn("MAIN-001", "cashier@example.com", "password")

        assertEquals("device-1", saved.captured.deviceId)
        assertEquals(true, saved.captured.isActivated)
    }

    @Test
    fun `fresh install resumes the active server day with its known totals`() = runTest {
        val serverDay = RecoveredBusinessDay(
            id = "server-day", businessDate = "2026-10-13", openedAt = "2026-10-12T18:04:46Z",
            cashierId = "cashier-1", knownSales = 50.0, knownOrders = 1, knownCogs = 4.04,
        )
        coEvery { api.login(any()) } returns listOf(login(role = "cashier", deviceId = "device-1", isActivated = true))
        coEvery { api.getResumableDay(any()) } returns RecoveredDayResponse(serverDay)
        val saved = slot<BusinessDayEntity>()
        coEvery { businessDayDao.reconcileActiveDay("stall-1", "device-1", null, capture(saved)) } returns Unit

        repository.signIn("MAIN-001", "cashier-1", "password")

        assertEquals("server-day", saved.captured.id)
        assertEquals(5000L, saved.captured.recoveryKnownSalesCents)
        assertEquals(1, saved.captured.recoveryKnownOrders)
        assertEquals(404L, saved.captured.recoveryKnownCogsCents)
        assertEquals(true, saved.captured.isSynced)
        coVerify(exactly = 1) { businessDayDao.reconcileActiveDay("stall-1", "device-1", null, any()) }
    }

    @Test
    fun `refresh reconciles the previous device only after an authenticated cloud read`() = runTest {
        val cachedOpen = BusinessDayEntity(
            id = "cached-day", stallId = "stall-1", deviceId = "previous-device", cashierId = "cashier-1",
            businessDate = "2026-10-07", openedAt = "2026-10-07T07:13:19.401+00:00",
            updatedAt = "2026-10-07T07:13:19.401+00:00", recoveryKnownSalesCents = 9000, isSynced = true,
        )
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            userId = "cashier-1", stallId = "stall-1", deviceId = "device-1",
            role = "cashier", displayName = "Cashier", isActivated = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns cachedOpen
        coEvery { api.getResumableDay(any()) } returns RecoveredDayResponse(null)

        repository.refreshPreviousDeviceDay()
        coVerify(exactly = 0) { api.getResumableDay(any()) }

        tokenStore.token = "token-1"
        repository.refreshPreviousDeviceDay()
        coVerify(exactly = 1) { api.getResumableDay(match { it.deviceId == "device-1" }) }
        coVerify(exactly = 1) { businessDayDao.reconcileActiveDay("stall-1", "device-1", cachedOpen, null) }
    }

    @Test
    fun `same-day resume supplies both local snapshot and cloud day for reconciliation`() = runTest {
        val serverDay = RecoveredBusinessDay(
            id = "server-day", businessDate = "2026-10-13", openedAt = "2026-10-12T18:04:46Z",
            cashierId = "cashier-1", knownSales = 75.0, knownOrders = 2, knownCogs = 6.06,
        )
        coEvery { api.login(any()) } returns listOf(login(role = "cashier", deviceId = "device-1", isActivated = true))
        coEvery { api.getResumableDay(any()) } returns RecoveredDayResponse(serverDay)
        val cachedDay = BusinessDayEntity(
            id = "server-day", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-10-13", openedAt = "2026-10-12T18:04:46Z",
            updatedAt = "2026-10-13T09:00:00Z", recoveryKnownSalesCents = 5000,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns cachedDay

        repository.signIn("MAIN-001", "cashier-1", "password")

        coVerify(exactly = 1) {
            businessDayDao.reconcileActiveDay("stall-1", "device-1", cachedDay,
                match { it != null && it.id == cachedDay.id && it.recoveryKnownSalesCents == 7500L })
        }
    }

    @Test
    fun `activation is verified by the backend and stores the assigned device`() = runTest {
        val current = AppSessionEntity(
            userId = "cashier-1", stallId = "stall-1", displayName = "Cashier",
            role = "cashier", credentialRef = "token-1",
        )
        coEvery { sessionDao.getCurrent() } returns current
        coEvery { api.activateDevice(any()) } returns ActivateDeviceResponse("device-1", "stall-1")
        coEvery { api.getResumableDay(any()) } returns RecoveredDayResponse(null)
        val proof = passwordProof("MAIN-001", "CASHIER01", "Cached cashier password")
        every { credentials.readActivationPassword("token-1") } returns proof
        val saved = slot<AppSessionEntity>()
        coEvery { sessionDao.save(capture(saved)) } returns Unit

        repository.activate(" ABC123 ")

        coVerify(exactly = 1) {
            api.activateDevice(match { it.activationCode == "ABC123" && it.hardwareId == "hardware-1" })
        }
        assertEquals("device-1", saved.captured.deviceId)
        assertEquals(true, saved.captured.isActivated)
        verify(exactly = 1) {
            credentials.writeOfflineCashier(match {
                it.matches(saved.captured) && it.employeeId == proof.employeeId
            })
        }
    }

    @Test
    fun `sign out clears the token and local session`() = runTest {
        tokenStore.token = "token-1"
        coEvery { api.revokeSession("token-1") } returns Unit

        assertEquals(true, repository.signOut())

        assertEquals(null, tokenStore.token)
        coVerify(exactly = 1) { sessionDao.clear() }
        coVerify(exactly = 1) { api.revokeSession("token-1") }
    }

    @Test
    fun `offline sign out queues only its own bearer for retry`() = runTest {
        val expiry = "2999-01-01T00:00:00Z"
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            displayName = "Cashier", role = "cashier", credentialRef = "reference", expiresAt = expiry,
        )
        every { credentials.readSession("reference") } returns "bearer"
        coEvery { api.revokeSession("bearer") } throws java.io.IOException("offline")
        assertEquals(false, repository.signOut())
        assertEquals(null, tokenStore.token)
        io.mockk.verify { credentials.queueRevocation("bearer", expiry) }
        coVerify(exactly = 1) { sessionDao.clear() }
    }

    @Test
    fun `stored cashier session remains available for offline sales when cloud token expires`() = runTest {
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            displayName = "Cashier",
            role = "cashier",
            isActivated = true,
            credentialRef = "expired-token",
            expiresAt = "2000-01-01T00:00:00Z",
        )

        val restored = repository.restoreStoredSession(allowExpiredForOfflineWork = true)

        assertEquals("Cashier", restored?.displayName)
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { sessionDao.clear() }
    }

    @Test
    fun `activated cashier can use cached password with an expired cloud token`() = runTest {
        val cashier = offlineCashier()
        val local = cashier.session().copy(expiresAt = "2000-01-01T00:00:00Z")
        coEvery { sessionDao.getCurrent() } returns local
        every { credentials.readOfflineCashier() } returns cashier

        assertEquals(local, repository.signIn(" main-001 ", " cashier01 ", "Cached cashier password"))
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { api.login(any()) }

        coEvery { api.login(any()) } throws java.io.IOException("offline")
        val rejected = runCatching { repository.signIn("MAIN-001", "CASHIER01", "wrong password") }.exceptionOrNull()
        org.junit.Assert.assertTrue(rejected is IllegalArgumentException)
        verify(exactly = 1) { credentials.recordOfflinePasswordFailure(any()) }
        coVerify(exactly = 0) { sessionDao.save(any()) }
    }

    @Test
    fun `offline sign in after sign out creates a local session without the revoked token`() = runTest {
        val cashier = offlineCashier()
        var current: AppSessionEntity? = null
        every { credentials.readOfflineCashier() } returns cashier
        every { credentials.readSession(any()) } returns null
        coEvery { sessionDao.getCurrent() } answers { current }
        coEvery { sessionDao.save(any()) } answers { current = firstArg() }

        val signedIn = repository.signIn("MAIN-001", "CASHIER01", "Cached cashier password")
        assertEquals(cashier.session(), signedIn)
        assertEquals(signedIn, repository.restoreStoredSession(allowExpiredForOfflineWork = true))
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { api.login(any()) }
    }

    @Test
    fun `reconnecting cloud sync requires the server to accept the password`() = runTest {
        val cashier = offlineCashier()
        coEvery { sessionDao.getCurrent() } returns cashier.session()
        every { credentials.readOfflineCashier() } returns cashier
        coEvery { api.login(any()) } returns emptyList()

        val rejected = runCatching { repository.reconnectCloud("MAIN-001", "CASHIER01", "Cached cashier password") }
            .exceptionOrNull()

        org.junit.Assert.assertTrue(rejected is IllegalArgumentException)
        coVerify(exactly = 1) { api.login(any()) }
        coVerify(exactly = 0) { sessionDao.save(any()) }
    }

    @Test
    fun `reconnecting sync restores cloud credentials without replacing the local cashier reference`() = runTest {
        val cashier = offlineCashier()
        coEvery { sessionDao.getCurrent() } returns cashier.session()
        every { credentials.readOfflineCashier() } returns cashier
        every { credentials.readSession(any()) } returns null
        coEvery { api.login(any()) } returns listOf(login(role = "cashier", deviceId = "device-1", isActivated = true))
        coEvery { api.getResumableDay(any()) } returns RecoveredDayResponse(null)

        val signedIn = repository.reconnectCloud("MAIN-001", "CASHIER01", "Cached cashier password")

        assertEquals(cashier.reference, signedIn.credentialRef)
        assertEquals("token-1", tokenStore.token)
        coVerify { sessionDao.save(signedIn) }
    }

    @Test
    fun `registered fingerprint keeps working locally with an expired cloud token`() = runTest {
        val cashier = offlineCashier()
        val current = cashier.session().copy(expiresAt = "2000-01-01T00:00:00Z")
        coEvery { sessionDao.getCurrent() } returns current
        every { credentials.readOfflineCashier() } returns cashier

        assertEquals(current, repository.signInWithFingerprint(cashier.userId, "registered-secret"))
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { api.loginWithFingerprint(any()) }
    }

    @Test
    fun `a different device cannot open a day using a cached password`() = runTest {
        val cashier = offlineCashier()
        coEvery { sessionDao.getCurrent() } returns cashier.session()
        every { credentials.readOfflineCashier() } returns cashier
        coEvery { businessDayDao.findOpen(cashier.stallId) } returns BusinessDayEntity(
            id = "previous-day", stallId = cashier.stallId, deviceId = "previous-device", cashierId = cashier.userId,
            businessDate = "2026-11-13", openedAt = "2026-11-13T00:00:00Z", updatedAt = "2026-11-13T00:00:00Z",
        )

        val error = runCatching {
            repository.signIn("MAIN-001", "CASHIER01", "Cached cashier password", requireActivation = true)
        }.exceptionOrNull()

        org.junit.Assert.assertTrue(error is IllegalStateException)
        coVerify(exactly = 0) { sessionDao.save(any()) }
    }

    @Test
    fun `sync renews the expired device bearer without changing the local session reference`() = runTest {
        val current = offlineCashier().session().copy(expiresAt = "2000-01-01T00:00:00Z")
        val renewed = "2999-01-01T00:00:00Z"
        coEvery { sessionDao.getCurrent() } returns current
        every { credentials.readSession(requireNotNull(current.credentialRef)) } returns "device-bearer"
        coEvery { api.renewPosSession("device-bearer") } returns renewed

        repository.prepareCloudSync()

        assertEquals("device-bearer", tokenStore.token)
        coVerify(exactly = 1) { sessionDao.save(current.copy(expiresAt = renewed)) }
        coVerify(exactly = 0) { api.login(any()) }
    }

    @Test
    fun `renewal in flight does not delay a local sign in or restore a signed-out session`() = runTest {
        val cashier = offlineCashier()
        val signedIn = cashier.session().copy(expiresAt = "2000-01-01T00:00:00Z")
        var saved: AppSessionEntity? = signedIn
        every { credentials.readOfflineCashier() } returns cashier
        every { credentials.readSession(cashier.reference) } returns "device-bearer"
        coEvery { sessionDao.getCurrent() } answers { saved }
        coEvery { sessionDao.clear() } answers { saved = null }
        coEvery { api.revokeSession("device-bearer") } returns Unit
        val renewalStarted = CompletableDeferred<Unit>()
        val renewalResult = CompletableDeferred<String>()
        coEvery { api.renewPosSession("device-bearer") } coAnswers {
            renewalStarted.complete(Unit)
            renewalResult.await()
        }
        var syncError: Throwable? = null
        val sync = launch { syncError = runCatching { repository.prepareCloudSync() }.exceptionOrNull() }
        renewalStarted.await()

        assertEquals(signedIn, repository.signIn("MAIN-001", "CASHIER01", "Cached cashier password"))
        assertEquals(true, repository.signOut())
        renewalResult.complete("2999-01-01T00:00:00Z")
        sync.join()

        org.junit.Assert.assertTrue(syncError is RetryableSyncException)
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { sessionDao.save(any()) }
    }

    @Test
    fun `biometric-locked session can be checked without exposing its token`() = runTest {
        val current = AppSessionEntity(
            userId = "cashier-1", displayName = "Cashier", role = "cashier",
            credentialRef = "token-1", expiresAt = "2999-01-01T00:00:00.650144+00:00",
        )
        coEvery { sessionDao.getCurrent() } returns current
        tokenStore.token = "previous-token"

        assertEquals(current, repository.restoreStoredSession(activateToken = false))
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 0) { sessionDao.clear() }

        assertEquals(current, repository.restoreStoredSession())
        assertEquals("token-1", tokenStore.token)
    }

    @Test
    fun `expired session requires sign in again on the next app launch`() = runTest {
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            displayName = "Cashier",
            role = "cashier",
            credentialRef = "expired-token",
            expiresAt = "2000-01-01T00:00:00Z",
        )

        val restored = repository.restoreStoredSession()

        assertEquals(null, restored)
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 1) { sessionDao.clear() }
    }

    private suspend fun offlineCashier(): OfflineCashier {
        val proof = passwordProof("MAIN-001", "CASHIER01", "Cached cashier password")
        return OfflineCashier(
            reference = "saved-cashier", userId = "user-1", stallId = "stall-1", deviceId = "device-1",
            hardwareId = "hardware-1", displayName = "Test user",
            stallCode = proof.stallCode, employeeId = proof.employeeId, salt = proof.salt, passwordHash = proof.hash,
        )
    }

    private fun login(role: String, deviceId: String? = null, isActivated: Boolean = false) = LoginResponse(
        sessionToken = "token-1",
        userId = "user-1",
        stallId = "stall-1",
        displayName = "Test user",
        role = role,
        expiresAt = "2026-09-08T12:00:00Z",
        deviceId = deviceId,
        isActivated = isActivated,
    )
}
