package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.ActivateDeviceResponse
import com.icecreampost.pos.data.remote.dto.LoginResponse
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {
    private val sessionDao = mockk<SessionDao>(relaxed = true)
    private val api = mockk<SupabaseApi>()
    private val tokenStore = SessionTokenStore()
    private val deviceIdentity = mockk<DeviceIdentity>()
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        every { deviceIdentity.id } returns "hardware-1"
        repository = SessionRepository(sessionDao, api, tokenStore, deviceIdentity)
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
    }

    @Test
    fun `cashier login restores an active registration for the same device`() = runTest {
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
    fun `activation is verified by the backend and stores the assigned device`() = runTest {
        val current = AppSessionEntity(
            userId = "cashier-1", stallId = "stall-1", displayName = "Cashier",
            role = "cashier", sessionToken = "token-1",
        )
        coEvery { sessionDao.getCurrent() } returns current
        coEvery { api.activateDevice(any()) } returns ActivateDeviceResponse("device-1", "stall-1")
        val saved = slot<AppSessionEntity>()
        coEvery { sessionDao.save(capture(saved)) } returns Unit

        repository.activate(" ABC123 ")

        coVerify(exactly = 1) {
            api.activateDevice(match { it.activationCode == "ABC123" && it.hardwareId == "hardware-1" })
        }
        assertEquals("device-1", saved.captured.deviceId)
        assertEquals(true, saved.captured.isActivated)
    }

    @Test
    fun `sign out clears the token and local session`() = runTest {
        tokenStore.token = "token-1"

        repository.signOut()

        assertEquals(null, tokenStore.token)
        coVerify(exactly = 1) { sessionDao.clear() }
    }

    @Test
    fun `stored cashier session remains available for offline sales when cloud token expires`() = runTest {
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            displayName = "Cashier",
            role = "cashier",
            sessionToken = "expired-token",
            expiresAt = "2000-01-01T00:00:00Z",
        )

        val restored = repository.restoreStoredSession(allowExpiredForOfflineWork = true)

        assertEquals("Cashier", restored?.displayName)
        assertEquals("expired-token", tokenStore.token)
        coVerify(exactly = 0) { sessionDao.clear() }
    }

    @Test
    fun `expired session requires sign in again on the next app launch`() = runTest {
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            displayName = "Cashier",
            role = "cashier",
            sessionToken = "expired-token",
            expiresAt = "2000-01-01T00:00:00Z",
        )

        val restored = repository.restoreStoredSession()

        assertEquals(null, restored)
        assertEquals(null, tokenStore.token)
        coVerify(exactly = 1) { sessionDao.clear() }
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
