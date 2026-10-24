package com.icecreampost.pos.ui

import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.repository.BusinessDayRepository
import com.icecreampost.pos.data.repository.CheckoutRepository
import com.icecreampost.pos.data.repository.ProductRepository
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.data.repository.SyncRepository
import com.icecreampost.pos.security.FingerprintLoginStore
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
        userId = "cashier-1", displayName = "Cashier", role = "cashier",
        sessionToken = "token-1", expiresAt = "2999-01-01T00:00:00Z",
    )
    private val rawSession = MutableStateFlow<AppSessionEntity?>(savedSession)
    private lateinit var viewModel: PosViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { fingerprintStore.isEnabled } returns true
        every { sessionRepository.observeSession() } returns rawSession
        every { productRepository.observeProducts() } returns MutableStateFlow(emptyList())
        every { checkoutRepository.observeTransactions() } returns MutableStateFlow(emptyList())
        every { syncStateDao.observe(any()) } returns MutableStateFlow(null)
        every { businessDayRepository.observeLatest() } returns MutableStateFlow(null)
        coEvery { sessionRepository.restoreStoredSession(false, false) } returns savedSession
        coEvery { sessionRepository.restoreStoredSession(false, true) } returns savedSession
        viewModel = PosViewModel(
            productRepository, checkoutRepository, sessionRepository, syncRepository,
            syncStateDao, businessDayRepository, fingerprintStore,
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `startup keeps a stored session behind fingerprint authentication`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel.sessionReady.value)
        assertTrue(viewModel.fingerprintUnlockRequired.value)
        assertNull(viewModel.session.value)
        coVerify(exactly = 0) { sessionRepository.restoreStoredSession(false, true) }
        coVerify(exactly = 0) { syncRepository.sync() }
    }

    @Test
    fun `failed biometric account verification never unlocks the session`() = runTest(dispatcher) {
        advanceUntilIdle()
        every { fingerprintStore.verifyAccount(any(), any()) } throws IllegalArgumentException("Account changed")
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.fingerprintUnlockRequired.value)
        assertEquals("Account changed", viewModel.error.value)
        coVerify(exactly = 0) { sessionRepository.restoreStoredSession(false, true) }
    }

    @Test
    fun `authenticated session unlocks and locks again when the app is backgrounded`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertEquals(savedSession, viewModel.session.value)
        assertFalse(viewModel.fingerprintUnlockRequired.value)
        verify(exactly = 1) { fingerprintStore.verifyAccount("cashier-1", cipher) }

        viewModel.onAppBackgrounded()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.fingerprintUnlockRequired.value)
        viewModel.onAppForegrounded()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
    }

    @Test
    fun `session expiry during unlock requires password sign in`() = runTest(dispatcher) {
        advanceUntilIdle()
        coEvery { sessionRepository.restoreStoredSession(false, false) } returns null
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertFalse(viewModel.fingerprintUnlockRequired.value)
        assertEquals("Your session has expired. Sign in with your password.", viewModel.error.value)
        verify(exactly = 0) { fingerprintStore.verifyAccount(any(), any()) }
    }

    @Test
    fun `password fallback never exposes the previous cashier while Room updates`() = runTest(dispatcher) {
        advanceUntilIdle()
        val newSession = savedSession.copy(userId = "cashier-2", sessionToken = "token-2")
        coEvery { sessionRepository.signIn(any(), any(), any()) } returns newSession

        viewModel.signIn("MAIN-001", "second@example.com", "password")
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertFalse(viewModel.fingerprintEnabled.value)
        verify(exactly = 1) { fingerprintStore.disable() }

        rawSession.value = newSession
        advanceUntilIdle()
        assertEquals(newSession, viewModel.session.value)
    }

    @Test
    fun `authentication that finishes in the background does not unlock the screen`() = runTest(dispatcher) {
        advanceUntilIdle()
        coEvery { sessionRepository.restoreStoredSession(false, true) } coAnswers {
            viewModel.onAppBackgrounded()
            savedSession
        }
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertTrue(viewModel.fingerprintUnlockRequired.value)
        viewModel.onAppForegrounded()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
    }

    @Test
    fun `signing out removes fingerprint enrollment and clears the session`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.unlockWithFingerprint(cipher)
        advanceUntilIdle()
        coEvery { sessionRepository.signOut() } coAnswers { rawSession.value = null }
        viewModel.signOut()
        advanceUntilIdle()
        assertNull(viewModel.session.value)
        assertFalse(viewModel.fingerprintUnlockRequired.value)
        assertFalse(viewModel.fingerprintEnabled.value)
        verify(exactly = 1) { fingerprintStore.disable() }
    }
}
