package com.icecreampost.pos.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.repository.RetryableSyncException
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.data.repository.SyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val sessionRepository: SessionRepository,
    private val logger: AppLogger,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            logger.info("Sync started")
            val session = sessionRepository.restoreStoredSession()
            if (session == null || !session.isActivated) {
                logger.info("Sync skipped: no activated cashier session")
                Result.success()
            } else {
                val report = syncRepository.sync()
                logger.info("Sync completed: pushed=${report.pushed}, permanentFailures=${report.permanentFailures}")
                Result.success()
            }
        } catch (error: RetryableSyncException) {
            logger.error("Retryable sync failure", error)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (error: Exception) {
            logger.error("Sync failed", error)
            Result.failure()
        }
    }
}
