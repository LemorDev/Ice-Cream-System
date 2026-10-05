package com.icecreampost.pos.data.repository

import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.DailyStoreClosingEntity
import com.icecreampost.pos.data.local.dao.DailyStoreClosingDao
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.RevenueDeductionDao
import com.icecreampost.pos.data.local.entity.RevenueDeductionEntity
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import androidx.room.withTransaction
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
    private val database: CoolerzDatabase,
    private val dailyStoreClosingDao: DailyStoreClosingDao,
    private val inventoryLedgerDao: InventoryLedgerDao,
    private val revenueDeductionDao: RevenueDeductionDao,
) {
    fun observeLatest(): Flow<BusinessDayEntity?> = businessDayDao.observeLatest()
    fun observeDeductions(): Flow<List<RevenueDeductionEntity>> = revenueDeductionDao.observeAll()

    suspend fun hasOpenDay(stallId: String): Boolean = businessDayDao.findOpen(stallId) != null

    suspend fun recordDeduction(amountCents: Long, reason: String, affectsProfit: Boolean = true) {
        val session = sessionDao.getCurrent() ?: error("Sign in before recording a deduction.")
        require(session.role == "cashier" && session.isActivated) { "An activated Cashier account is required." }
        check(!session.transferReady) { "This POS is prepared for replacement. Sign in again to resume operations." }
        val stallId = requireNotNull(session.stallId) { "This Cashier has no assigned stall." }
        val cashierId = requireNotNull(session.userId) { "The Cashier identity is missing." }
        val openDay = businessDayDao.findOpen(stallId) ?: error("Open the operating day before recording a deduction.")
        require(openDay.deviceId == session.deviceId) { "Record deductions on the POS that opened the day." }
        require(amountCents > 0) { "Enter a deduction greater than zero." }
        val normalizedReason = reason.trim()
        require(normalizedReason.isNotEmpty()) { "Provide a reason for the deduction." }
        database.withTransaction {
            val now = Instant.now().toString()
            val localGross = transactionDao.getCompletedTotalBetween(stallId, openDay.openedAt, now)
            val gross = localGross + openDay.recoveryKnownSalesCents
            val existing = revenueDeductionDao.totalForDay(openDay.id)
            require(amountCents <= gross - existing) { "Deduction cannot exceed remaining sales revenue." }
            revenueDeductionDao.upsert(RevenueDeductionEntity(
                id = UUID.randomUUID().toString(), stallId = stallId, businessDayId = openDay.id,
                businessDate = openDay.businessDate,
                amountCents = amountCents, reason = normalizedReason, cashierId = cashierId, occurredAt = now,
                affectsProfit = affectsProfit,
            ))
        }
        syncTrigger.triggerNow()
    }

    suspend fun openDay(notes: String = "") {
        val session = sessionDao.getCurrent() ?: error("Sign in before opening the stall.")
        require(session.role == "cashier" && session.isActivated) { "An activated Cashier account is required." }
        check(!session.transferReady) { "This POS is prepared for replacement. Sign in again to resume operations." }
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

    suspend fun closeDay(
        notes: String = "",
        collectedCashCents: Long? = null,
    ) {
        val session = sessionDao.getCurrent() ?: error("Sign in before closing the day.")
        require(session.role == "cashier" && session.isActivated) { "An activated Cashier account is required." }
        val stallId = requireNotNull(session.stallId) { "This Cashier has no assigned stall." }
        val openDay = businessDayDao.findOpen(stallId) ?: error("Open the stall before closing the day.")
        require(openDay.deviceId == session.deviceId) { "Close the day from the POS that opened it." }
        val now = Instant.now().toString()
        val cashTotal = transactionDao.getCompletedTotalBetween(stallId, openDay.openedAt, now) + openDay.recoveryKnownSalesCents
        val cogs = transactionDao.getCompletedCogsBetween(stallId, openDay.openedAt, now) + openDay.recoveryKnownCogsCents
        val waste = inventoryLedgerDao.getWasteCostBetween(stallId, openDay.openedAt, now) + openDay.recoveryKnownWasteCents
        val revenueDeductionCents = revenueDeductionDao.totalForDay(openDay.id) + openDay.recoveryKnownDeductionsCents
        val profitDeductionCents = revenueDeductionDao.profitAffectingTotalForDay(openDay.id) + openDay.recoveryKnownProfitDeductionsCents
        require(revenueDeductionCents <= cashTotal) { "Revenue deduction cannot exceed gross sales." }
        val expectedCash = cashTotal - revenueDeductionCents
        val collected = collectedCashCents ?: expectedCash
        require(collected >= 0) { "Collected cash cannot be negative." }
        database.withTransaction {
            businessDayDao.upsert(openDay.copy(
                closedAt = now, closingCashCents = collected,
                closingNotes = notes.trim().ifBlank { null }, updatedAt = now,
                isSynced = false, syncError = null,
            ))
            dailyStoreClosingDao.upsert(DailyStoreClosingEntity(
                id = UUID.randomUUID().toString(), stallId = stallId, businessDayId = openDay.id,
                businessDate = openDay.businessDate, grossSalesCents = cashTotal, cogsCents = cogs,
                wasteCostCents = waste, overheadCostCents = 0,
                netProfitCents = cashTotal - cogs - waste - profitDeductionCents,
                expectedCashCents = expectedCash, collectedCashCents = collected,
                deviceId = openDay.deviceId, closedAt = now,
                revenueDeductionCents = revenueDeductionCents,
                deductionReason = if (revenueDeductionCents > 0) "See recorded deductions" else null,
            ))
        }
        syncTrigger.triggerNow()
    }
}
