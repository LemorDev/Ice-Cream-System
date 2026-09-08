package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.sync.SyncTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class CheckoutRepositoryTest {
    private val database = mockk<CoolerzDatabase>()
    private val productDao = mockk<ProductDao>(relaxed = true)
    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val ledgerDao = mockk<InventoryLedgerDao>(relaxed = true)
    private val sessionDao = mockk<SessionDao>()
    private val businessDayDao = mockk<BusinessDayDao>()
    private lateinit var repository: CheckoutRepository
    private val cartProduct = ProductEntity(
        id = "product-1",
        name = "Vanilla",
        category = "Ice cream",
        priceCents = 2_000,
        unitsInStock = 10,
        updatedAt = "2026-09-08T00:00:00Z",
    )

    @Before
    fun setUp() {
        // Exercise repository behavior while replacing only Room's Android transaction runner.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Unit>(any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
        }
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            stallId = "stall-1", deviceId = "device-1", displayName = "Cashier", role = "cashier", isActivated = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-08", openedAt = "2026-09-08T00:00:00Z", updatedAt = "2026-09-08T00:00:00Z",
        )
        repository = CheckoutRepository(
            database, productDao, transactionDao, ledgerDao, sessionDao, businessDayDao,
            mockk<SyncTrigger>(relaxed = true), mockk<AppLogger>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `checkout preserves a stock reduction received after the cart snapshot`() = runTest {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 5)

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000)

        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 3, any()) }
        coVerify(exactly = 1) { transactionDao.upsert(any()) }
    }

    @Test
    fun `checkout preserves stock received after the cart snapshot`() = runTest {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 15)

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000)

        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 13, any()) }
    }

    @Test
    fun `checkout rejects insufficient current stock even when the cart had enough`() {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 1)

        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000) }
        }

        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
    }

    @Test
    fun `checkout rejects a sale while the stall is closed`() {
        coEvery { businessDayDao.findOpen("stall-1") } returns null

        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 1)), 2_000) }
        }

        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
    }
}
