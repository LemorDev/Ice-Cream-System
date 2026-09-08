package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.sync.SyncTrigger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class BusinessDayRepository @Inject constructor(
    private val businessDayDao: BusinessDayDao,
    private val transactionDao: TransactionDao,
    private val sessionDao: SessionDao,
    private val syncTrigger: SyncTrigger,
) {
    fun observeLatest(): Flow<BusinessDayEntity?> = businessDayDao.observeLatest()

    suspend fun openDay(notes: String = "") {
        val session = sessionDao.getCurrent() ?: error("Sign in before opening the stall.")
        require(session.role == "cashier" && session.isActivated) { "An activated Cashier account is required." }
        val stallId = requireNotNull(session.stallId) { "This Cashier has no assigned stall." }
        val deviceId = requireNotNull(session.deviceId) { "This POS has not been activated." }
        val cashierId = requireNotNull(session.userId) { "The Cashier identity is missing." }
        require(businessDayDao.findOpen(stallId) == null) { "An operating day is already open." }
        val businessDate = LocalDate.now(ZoneId.of("Asia/Manila")).toString()
        require(businessDayDao.findByDate(stallId, businessDate) == null) { "This business day has already been opened." }
        val now = Instant.now().toString()
        businessDayDao.upsert(BusinessDayEntity(
            id = UUID.randomUUID().toString(), stallId = stallId, deviceId = deviceId,
            cashierId = cashierId, businessDate = businessDate, openedAt = now,
            openingNotes = notes.trim().ifBlank { null }, updatedAt = now,
        ))
        syncTrigger.triggerNow()
    }

    suspend fun closeDay(notes: String = "") {
        val session = sessionDao.getCurrent() ?: error("Sign in before closing the day.")
        require(session.role == "cashier" && session.isActivated) { "An activated Cashier account is required." }
        val stallId = requireNotNull(session.stallId) { "This Cashier has no assigned stall." }
        val openDay = businessDayDao.findOpen(stallId) ?: error("Open the stall before closing the day.")
        require(openDay.deviceId == session.deviceId) { "Close the day from the POS that opened it." }
        val now = Instant.now().toString()
        val cashTotal = transactionDao.getCompletedTotalBetween(stallId, openDay.openedAt, now)
        businessDayDao.upsert(openDay.copy(
            closedAt = now, closingCashCents = cashTotal,
            closingNotes = notes.trim().ifBlank { null }, updatedAt = now,
            isSynced = false, syncError = null,
        ))
        syncTrigger.triggerNow()
    }
}
