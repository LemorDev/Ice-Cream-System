package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessDayDao {
    @Query("SELECT * FROM business_days ORDER BY openedAt DESC LIMIT 1")
    fun observeLatest(): Flow<BusinessDayEntity?>

    @Query("SELECT * FROM business_days WHERE stallId = :stallId AND closedAt IS NULL ORDER BY openedAt DESC LIMIT 1")
    suspend fun findOpen(stallId: String): BusinessDayEntity?

    @Query("SELECT * FROM business_days WHERE stallId = :stallId AND businessDate = :businessDate LIMIT 1")
    suspend fun findByDate(stallId: String, businessDate: String): BusinessDayEntity?

    @Transaction
    suspend fun reconcileActiveDay(
        stallId: String,
        deviceId: String,
        cachedOpen: BusinessDayEntity?,
        serverOpen: BusinessDayEntity?,
    ) {
        // An offline opening or closing may have been saved during the cloud read.
        if (findOpen(stallId) != cachedOpen) return
        if (cachedOpen != null && cachedOpen.id != serverOpen?.id) {
            if (serverOpen == null && cachedOpen.deviceId == deviceId && !cachedOpen.isSynced) return
            releaseCachedDay(cachedOpen)
        }
        if (serverOpen == null) return

        val existing = findByDate(stallId, serverOpen.businessDate)
        if (existing?.id == serverOpen.id) {
            if (existing.deviceId != deviceId) {
                checkDayCanBeReplaced(existing)
                deleteAcknowledgedClosing(existing.id, null)
                // Keep the recovery totals already combined with this phone's local sales.
                upsert(existing.copy(
                    deviceId = deviceId,
                    openedAt = serverOpen.openedAt,
                    openingNotes = serverOpen.openingNotes,
                    closedAt = null,
                    closingCashCents = null,
                    closingNotes = null,
                    updatedAt = serverOpen.updatedAt,
                    syncError = null,
                ))
            }
            return
        }
        if (existing != null) releaseCachedDay(existing)
        upsert(serverOpen)
    }

    private suspend fun releaseCachedDay(day: BusinessDayEntity) {
        checkDayCanBeReplaced(day)
        deleteAcknowledgedClosing(day.id, null)
        deleteCachedDay(day.id)
    }

    private suspend fun checkDayCanBeReplaced(day: BusinessDayEntity) {
        check(day.isSynced && !hasPendingDayRecords(day.stallId, day.id)) {
            "The phone still has unsynced records for ${day.businessDate}. Resolve them in IMS before replacing this cached operating day."
        }
    }

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM transactions WHERE stallId = :stallId AND isSynced = 0
                AND (businessDayId = :dayId OR businessDayId IS NULL)
            UNION ALL
            SELECT 1 FROM inventory_ledger WHERE stallId = :stallId AND isSynced = 0
                AND (businessDayId = :dayId OR businessDayId IS NULL)
            UNION ALL
            SELECT 1 FROM revenue_deductions WHERE businessDayId = :dayId AND isSynced = 0
            UNION ALL
            SELECT 1 FROM sale_reversals WHERE isSynced = 0 AND (originalDayId = :dayId OR payoutDayId = :dayId)
            UNION ALL
            SELECT 1 FROM daily_store_closings WHERE businessDayId = :dayId AND isSynced = 0
        )
    """)
    suspend fun hasPendingDayRecords(stallId: String, dayId: String): Boolean

    @Query("DELETE FROM business_days WHERE id = :id")
    suspend fun deleteCachedDay(id: String)

    @Query("SELECT * FROM business_days WHERE businessDate = :businessDate AND closedAt IS NOT NULL AND isSynced = 1 LIMIT 1")
    suspend fun findSyncedClosedDay(businessDate: String): BusinessDayEntity?

    @Transaction
    suspend fun clearResetDay(id: String, acknowledgedClosingId: String?) {
        if (deleteSyncedClosedDay(id, acknowledgedClosingId) == 1) deleteAcknowledgedClosing(id, acknowledgedClosingId)
    }

    @Query("""DELETE FROM business_days WHERE id = :id AND closedAt IS NOT NULL AND isSynced = 1
        AND NOT EXISTS (SELECT 1 FROM daily_store_closings WHERE businessDayId = :id AND isSynced = 0
            AND id IS NOT :acknowledgedClosingId)""")
    suspend fun deleteSyncedClosedDay(id: String, acknowledgedClosingId: String?): Int

    @Query("DELETE FROM daily_store_closings WHERE businessDayId = :dayId AND (isSynced = 1 OR id = :acknowledgedClosingId)")
    suspend fun deleteAcknowledgedClosing(dayId: String, acknowledgedClosingId: String?)

    @Query("SELECT * FROM business_days WHERE isSynced = 0 ORDER BY openedAt")
    suspend fun getUnsynced(): List<BusinessDayEntity>

    @Upsert suspend fun upsert(day: BusinessDayEntity)
    @Query("""UPDATE business_days SET isSynced = 1, syncError = NULL
        WHERE id = :id AND updatedAt = :updatedAt AND closedAt IS :closedAt AND isSynced = 0""")
    suspend fun markSynced(id: String, updatedAt: String, closedAt: String?): Int

    @Query("""UPDATE business_days SET syncError = :message
        WHERE id = :id AND updatedAt = :updatedAt AND closedAt IS :closedAt AND isSynced = 0""")
    suspend fun markSyncError(id: String, updatedAt: String, closedAt: String?, message: String): Int
}
