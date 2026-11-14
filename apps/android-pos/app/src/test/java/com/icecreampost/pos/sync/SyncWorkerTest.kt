package com.icecreampost.pos.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.data.repository.CloudSignInRequiredException
import com.icecreampost.pos.data.repository.SyncReport
import com.icecreampost.pos.data.repository.SyncRepository
import com.icecreampost.pos.security.PosCredentialVault
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncWorkerTest {
    @Test
    fun `background sync restores persisted credentials without opening the UI`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val tokenStore = SessionTokenStore()
        val syncRepository = mockk<SyncRepository>()
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            displayName = "Cashier", role = "cashier", credentialRef = "persisted-session-token",
            stallId = "stall-1", isActivated = true,
        )
        val sessionRepository = SessionRepository(sessionDao, mockk<SupabaseApi>(), tokenStore, mockk<DeviceIdentity>(),
            mockk(relaxed = true), emptySyncStateDao(), testVault())
        coEvery { syncRepository.sync() } coAnswers {
            assertEquals("persisted-session-token", tokenStore.token)
            SyncReport(pushed = 1, permanentFailures = 0)
        }
        val worker = SyncWorker(
            mockk<Context>(), mockk<WorkerParameters>(relaxed = true),
            syncRepository, sessionRepository, mockk<AppLogger>(relaxed = true),
        )
        assertNull(tokenStore.token)

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        coVerify(exactly = 1) { syncRepository.sync() }
    }

    @Test
    fun `background sync is skipped before a cashier signs in`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val syncRepository = mockk<SyncRepository>()
        coEvery { sessionDao.getCurrent() } returns null
        val sessionRepository = SessionRepository(
            sessionDao,
            mockk<SupabaseApi>(),
            SessionTokenStore(),
            mockk<DeviceIdentity>(),
            mockk(relaxed = true),
            emptySyncStateDao(),
            testVault(),
        )
        val worker = SyncWorker(
            mockk<Context>(), mockk<WorkerParameters>(relaxed = true),
            syncRepository, sessionRepository, mockk<AppLogger>(relaxed = true),
        )

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        coVerify(exactly = 0) { syncRepository.sync() }
    }

    @Test
    fun `failed post-sale sync never clears the local cashier session`() = runTest {
        val sessionDao = mockk<SessionDao>()
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            displayName = "Cashier",
            role = "cashier",
            credentialRef = "cloud-token",
            stallId = "stall-1",
            isActivated = true,
        )
        val syncRepository = mockk<SyncRepository>()
        coEvery { syncRepository.sync() } throws CloudSignInRequiredException()
        val sessionRepository = SessionRepository(
            sessionDao,
            mockk<SupabaseApi>(),
            SessionTokenStore(),
            mockk<DeviceIdentity>(),
            mockk(relaxed = true),
            emptySyncStateDao(),
            testVault(),
        )
        val worker = SyncWorker(
            mockk<Context>(), mockk<WorkerParameters>(relaxed = true),
            syncRepository, sessionRepository, mockk<AppLogger>(relaxed = true),
        )

        assertEquals(ListenableWorker.Result.failure(), worker.doWork())

        coVerify(exactly = 0) { sessionDao.clear() }
    }

    private fun emptySyncStateDao(): SyncStateDao = mockk<SyncStateDao>().also { dao ->
        coEvery { dao.find("device-stall") } returns null
        coEvery { dao.findPersistedStallIds() } returns emptyList()
    }

    private fun testVault(): PosCredentialVault = mockk<PosCredentialVault>().also { vault ->
        every { vault.readSession(any()) } answers { firstArg() }
        every { vault.pendingRevocations() } returns emptyList()
    }
}
