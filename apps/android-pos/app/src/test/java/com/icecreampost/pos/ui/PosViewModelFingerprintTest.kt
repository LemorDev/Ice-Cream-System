package com.icecreampost.pos.ui

import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.repository.SaleReversalRepository
import com.icecreampost.pos.data.repository.StockReceivingRepository
import com.icecreampost.pos.data.repository.PowderPackRepository
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.repository.BusinessDayRepository
import com.icecreampost.pos.data.repository.CheckoutRepository
import com.icecreampost.pos.data.repository.ProductRepository
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.data.repository.SyncRepository
import com.icecreampost.pos.security.FingerprintLoginStore
import com.icecreampost.pos.security.FingerprintCredential
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosViewModelFingerprintTest {
    private val dispatcher = StandardTestDispatcher()
    private val productRepository = mockk<ProductRepository>(relaxed = true)
    private val checkoutRepository = mockk<CheckoutRepository>(relaxed = true)
    private val sessionRepository = mockk<SessionRepository>(relaxed = true)
    private val syncRepository = mockk<SyncRepository>(relaxed = true)
    private val syncStateDao = mockk<SyncStateDao>(relaxed = true)
    private val businessDayRepository = mockk<BusinessDayRepository>(relaxed = true)
    private val fingerprintStore = mockk<FingerprintLoginStore>(relaxed = true)
    private val cipher = mockk<Cipher>()
    private val savedSession = AppSessionEntity(
        userId = "cashier-1", displayName = "Cashier", role = "cashier", isActivated = true,
        stallId = "stall-1", deviceId = "device-1", credentialRef = "token-1", expiresAt = "2000-01-01T00:00:00Z",
    )
    private val rawSession = MutableStateFlow<AppSessionEntity?>(savedSession)
    private lateinit var viewModel: PosViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { fingerprintStore.isEnabled } returns true
        every { fingerprintStore.signInAccountId } returns null
        every { sessionRepository.observeSession() } returns rawSession
        every { productRepository.observeProducts() } returns MutableStateFlow(emptyList())
        every { checkoutRepository.observeTransactions() } returns MutableStateFlow(emptyList())
        every { syncStateDao.observe(any()) } returns MutableStateFlow(null)
        every { syncRepository.observeSyncState() } returns MutableStateFlow(null)
        every { businessDayRepository.observeLatest() } returns MutableStateFlow(null)
        coEvery { sessionRepository.isLocked() } returns false
        coEvery { sessionRepository.restoreStoredSession(true, false) } returns savedSession
        coEvery { sessionRepository.restoreStoredSession(true, true) } returns savedSession
        viewModel = createViewModel()
    }

    private fun createViewModel(): PosViewModel {
        val ledger = mockk<InventoryLedgerDao>()
        val reversals = mockk<SaleReversalRepository>()
        every { ledger.observeActive() } returns MutableStateFlow(emptyList())
        every { reversals.observeAll() } returns MutableStateFlow(emptyList())
        every { productRepository.observeRecipes() } returns MutableStateFlow(emptyList())
        every { businessDayRepository.observeDeductions() } returns MutableStateFlow(emptyList())
        return PosViewModel(
            mockk<StockReceivingRepository>(relaxed = true), mockk<PowderPackRepository>(relaxed = true), ledger,
            productRepository, checkoutRepository, sessionRepository, syncRepository,
            syncStateDao, businessDayRepository, reversals, fingerprintStore,
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `startup and app backgrounding keep the cashier signed in after cloud expiry`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel.sessionReady.value)
        assertFalse(viewModel.offerFingerprintSetup.value)
        assertFalse(viewModel.posLocked.value)
        assertEquals(savedSession, viewModel.session.value)

        viewModel.onAppBackgrounded()
        viewModel.onAppForegrounded()
        advanceUntilIdle()
        assertEquals(savedSession, viewModel.session.value)
        assertFalse(viewModel.posLocked.value)
        coVerify(exactly = 0) { sessionRepository.lock() }
    }

    @Test
    fun `failed biometric account verification never unlocks the session`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.lockPos()
        advanceUntilIdle()
        every { fingerprintStore.verifyAccount(any(), any()) } throws IllegalArgumentException("Account changed")
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.posLocked.value)
        assertEquals("Account changed", viewModel.error.value)
        coVerify(exactly = 0) { sessionRepository.unlock(any()) }
    }

    @Test
    fun `manual lock survives restart and can be unlocked without a fresh cloud token`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.lockPos()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.posLocked.value)
        coVerify(exactly = 1) { sessionRepository.lock() }
        viewModel.viewModelScope.cancel()
        coEvery { sessionRepository.isLocked() } returns true
        viewModel = createViewModel()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.posLocked.value)

        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertEquals(savedSession, viewModel.session.value)
        assertFalse(viewModel.posLocked.value)
        verify(exactly = 1) { fingerprintStore.verifyAccount("cashier-1", cipher) }
        coVerify(exactly = 1) { sessionRepository.unlock(savedSession) }
    }

    @Test
    fun `missing local session during unlock requires password sign in`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.lockPos()
        advanceUntilIdle()
        coEvery { sessionRepository.restoreStoredSession(true, false) } returns null
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.posLocked.value)
        assertEquals("Sign in with your password to unlock this POS.", viewModel.error.value)
        verify(exactly = 0) { fingerprintStore.verifyAccount(any(), any()) }
    }

    @Test
    fun `password fallback never exposes the previous cashier while Room updates`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.lockPos()
        advanceUntilIdle()
        val newSession = savedSession.copy(userId = "cashier-2", credentialRef = "token-2")
        coEvery { sessionRepository.signIn(any(), any(), any()) } returns newSession

        viewModel.signIn("MAIN-001", "second@example.com", "password")
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertFalse(viewModel.fingerprintEnabled.value)
        verify(exactly = 1) { fingerprintStore.disable() }

        rawSession.value = newSession
        advanceUntilIdle()
        assertEquals(newSession, viewModel.session.value)
        assertTrue(viewModel.offerFingerprintSetup.value)
        viewModel.dismissFingerprintSetup()
        assertFalse(viewModel.offerFingerprintSetup.value)
        assertEquals(newSession, viewModel.session.value)
    }

    @Test
    fun `password fallback for the same cashier offers to upgrade legacy fingerprint unlock`() = runTest(dispatcher) {
        advanceUntilIdle()
        val renewed = savedSession.copy(credentialRef = "renewed-token")
        coEvery { sessionRepository.signIn(any(), any(), any()) } coAnswers {
            rawSession.value = renewed
            renewed
        }

        viewModel.signIn("MAIN-001", "CASHIER01", "password")
        advanceUntilIdle()
        assertEquals(renewed, viewModel.session.value)
        assertTrue(viewModel.offerFingerprintSetup.value)
        assertTrue(viewModel.fingerprintEnabled.value)
        verify(exactly = 0) { fingerprintStore.disable() }

        assertFalse(viewModel.posLocked.value)
    }

    @Test
    fun `a changed session cannot use the previous cashier's fingerprint verification`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.lockPos()
        advanceUntilIdle()
        coEvery { sessionRepository.restoreStoredSession(true, false) } returns
            savedSession.copy(userId = "cashier-2", credentialRef = "different-token")
        every { fingerprintStore.verifyAccount("cashier-2", cipher) } throws IllegalArgumentException("Account changed")

        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()

        assertNull(viewModel.session.value)
        assertTrue(viewModel.posLocked.value)
        coVerify(exactly = 0) { sessionRepository.unlock(any()) }
    }

    @Test
    fun `authentication that finishes in the background does not unlock the screen`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.lockPos()
        advanceUntilIdle()
        coEvery { sessionRepository.restoreStoredSession(true, false) } coAnswers {
            viewModel.onAppBackgrounded()
            savedSession
        }
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.posLocked.value)
        viewModel.onAppForegrounded()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
    }

    @Test
    fun `signing out clears a legacy unlock marker and its session`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        coEvery { sessionRepository.signOut() } coAnswers { rawSession.value = null; true }
        viewModel.signOut()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertFalse(viewModel.posLocked.value)
        assertFalse(viewModel.fingerprintEnabled.value)
        verify(exactly = 1) { fingerprintStore.disable() }
    }

    @Test
    fun `fingerprint sign in survives logout and app restart and obtains a new session`() = runTest(dispatcher) {
        viewModel.viewModelScope.cancel()
        every { fingerprintStore.signInAccountId } returns savedSession.userId
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        coEvery { sessionRepository.signOut() } coAnswers { rawSession.value = null; true }

        viewModel.signOut()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertFalse(viewModel.posLocked.value)
        assertTrue(viewModel.fingerprintSignInAvailable.value)
        coVerify(exactly = 1) { sessionRepository.signOut() }
        verify(exactly = 0) { fingerprintStore.disable() }

        viewModel.viewModelScope.cancel()
        coEvery { sessionRepository.restoreStoredSession(true, true) } returns null
        viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.fingerprintSignInAvailable.value)
        val credential = FingerprintCredential("cashier-1", "device-secret")
        every { fingerprintStore.readCredential(cipher) } returns credential
        val freshSession = savedSession.copy(credentialRef = "new-session-token", isActivated = true)
        coEvery { sessionRepository.signInWithFingerprint("cashier-1", "device-secret") } coAnswers {
            rawSession.value = freshSession
            freshSession
        }
        viewModel.signInWithFingerprint(cipher)
        advanceUntilIdle()
        assertEquals(freshSession, viewModel.session.value)
        assertFalse(viewModel.offerFingerprintSetup.value)
    }

    @Test
    fun `opening a day waits for cashier authentication and does not wait for sync`() = runTest(dispatcher) {
        advanceUntilIdle()
        coEvery { sessionRepository.signIn("MAIN-001", "CASHIER01", "wrong", true) } throws
            IllegalArgumentException("Wrong password")
        viewModel.openDay("MAIN-001", "CASHIER01", "wrong") { error("Wrong password opened a day") }
        advanceUntilIdle()
        coVerify(exactly = 0) { businessDayRepository.openDay() }
        assertEquals("Wrong password", viewModel.error.value)

        coEvery { sessionRepository.signIn("MAIN-001", "CASHIER01", "correct", true) } returns savedSession
        coEvery { syncRepository.sync() } throws java.io.IOException("Offline")
        var opened = false
        viewModel.openDay("MAIN-001", "CASHIER01", "correct") { opened = true }
        advanceUntilIdle()
        assertTrue(opened)
        assertNull(viewModel.error.value)
        coVerify(exactly = 1) { businessDayRepository.openDay() }
    }
}
