package com.icecreampost.pos.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.data.repository.SyncReport
import com.icecreampost.pos.data.repository.SyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
            displayName = "Cashier", role = "cashier", sessionToken = "persisted-session-token",
            stallId = "stall-1", isActivated = true,
        )
        val sessionRepository = SessionRepository(sessionDao, mockk<SupabaseApi>(), tokenStore, mockk<DeviceIdentity>())
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
}
