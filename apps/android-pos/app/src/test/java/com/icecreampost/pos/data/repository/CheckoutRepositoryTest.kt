package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.core.logging.AppLogger
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.TransactionDao
import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.ProductRecipeDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.entity.TransactionItemEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.sync.SyncTrigger
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CheckoutRepositoryTest {
    private val database = mockk<CoolerzDatabase>()
    private val productDao = mockk<ProductDao>(relaxed = true)
    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val ledgerDao = mockk<InventoryLedgerDao>(relaxed = true)
    private val sessionDao = mockk<SessionDao>()
    private val businessDayDao = mockk<BusinessDayDao>()
    private val recipeDao = mockk<ProductRecipeDao>()
    private val stateDao = mockk<SyncStateDao>()
    private val syncTrigger = mockk<SyncTrigger>(relaxed = true)
    private lateinit var repository: CheckoutRepository
    private val checkoutId = UUID.randomUUID()
    private val cartProduct = ProductEntity(
        id = "product-1",
        stallId = "stall-1",
        catalogCostPrice = "0",
        name = "Vanilla",
        category = "Ice cream",
        priceCents = 2_000,
        unitsInStock = 10.0,
        updatedAt = "2026-09-08T00:00:00Z",
    )

    @Before
    fun setUp() {
        // Exercise repository behavior while replacing only Room's Android transaction runner.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<CheckoutReceipt>(any()) } coAnswers {
            secondArg<suspend () -> CheckoutReceipt>().invoke()
        }
        coEvery { transactionDao.findById(any()) } returns null
        coEvery { sessionDao.getCurrent() } returns AppSessionEntity(
            stallId = "stall-1", deviceId = "device-1", displayName = "Cashier", role = "cashier", isActivated = true,
        )
        coEvery { businessDayDao.findOpen("stall-1") } returns BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-08", openedAt = "2026-09-08T00:00:00Z", updatedAt = "2026-09-08T00:00:00Z",
        )
        coEvery { recipeDao.findForParents(any()) } returns emptyList()
        coEvery { stateDao.find("catalog-revision:device-1") } returns SyncStateEntity(
            key = "catalog-revision:device-1", value = "revision-1", cursorUpdatedAt = "2099-01-01T00:00:00.478915+00:00",
        )
        repository = CheckoutRepository(
            database, productDao, transactionDao, ledgerDao, sessionDao, businessDayDao,
            recipeDao, stateDao, syncTrigger, mockk<AppLogger>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `historical receipt loads the selected local transaction and its items`() = runTest {
        val transaction = TransactionEntity(id = "sale-1", receiptNumber = "LOCAL-1", totalCents = 5_000, createdAt = "2026-09-29T00:00:00Z")
        val item = TransactionItemEntity(id = "item-1", transactionId = "sale-1", productId = "product-1", productName = "Twirl", quantity = 2.0, unitPriceCents = 2_500, lineTotalCents = 5_000, updatedAt = "2026-09-29T00:00:00Z")
        coEvery { transactionDao.findById("sale-1") } returns transaction
        coEvery { transactionDao.getItems("sale-1") } returns listOf(item)

        val receipt = repository.getHistoricalReceipt("sale-1")

        assertEquals("LOCAL-1", receipt?.transaction?.receiptNumber)
        assertEquals(listOf(item), receipt?.items)
        coVerify(exactly = 1) { transactionDao.getItems("sale-1") }
    }

    @Test
    fun `add-on charges only the selected servings and deducts normal stock`() = runTest {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct
        coEvery { stateDao.find("catalog-revision:device-1") } returns SyncStateEntity(
            key = "catalog-revision:device-1", value = "revision-1",
            cursorUpdatedAt = "2099-01-01T00:00:00Z", status = "success_with_add_ons",
        )

        val receipt = repository.checkout(listOf(CartLine(cartProduct, 3, addOnQuantity = 1)), 7_000, checkoutId)

        assertEquals(6_500L, receipt.subtotalCents)
        assertEquals(500L, receipt.changeAmountCents)
        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 7.0, any()) }
        coVerify(exactly = 1) {
            transactionDao.upsertItems(match { items ->
                items.size == 2 &&
                    items.all { it.productId == cartProduct.id } &&
                    items.any { it.productName == "Vanilla" && it.quantity == 2.0 && it.lineTotalCents == 4_000L } &&
                    items.any { it.productName == "Vanilla + Add-on" && it.quantity == 1.0 && it.lineTotalCents == 2_500L }
            })
        }
    }

    @Test
    fun `add-on checkout is blocked before the catalog confirms support`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 1, addOnQuantity = 1)), 2_500, checkoutId) }
        }
        assertEquals("Sync the sales catalog to sell add-ons.", failure.message)
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
    }

    @Test
    fun `checkout preserves a stock reduction received after the cart snapshot`() = runTest {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 5.0)

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000, checkoutId)

        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 3.0, any()) }
        coVerify(exactly = 1) { transactionDao.upsert(match { it.catalogRevisionId == "revision-1" }) }
        coVerify(exactly = 0) { sessionDao.clear() }
        verify(exactly = 1) { syncTrigger.triggerNow() }
    }

    @Test
    fun `checkout preserves stock received after the cart snapshot`() = runTest {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 15.0)

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000, checkoutId)

        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 13.0, any()) }
    }

    @Test
    fun `checkout rejects insufficient current stock even when the cart had enough`() {
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct.copy(unitsInStock = 1.0)

        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000, checkoutId) }
        }

        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
        coVerify(exactly = 0) { transactionDao.upsert(any()) }
    }

    @Test
    fun `checkout rejects a sale while the stall is closed`() {
        coEvery { businessDayDao.findOpen("stall-1") } returns null

        assertThrows(IllegalArgumentException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 1)), 2_000, checkoutId) }
        }

        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
    }

    @Test
    fun `checkout preserves stock and queue when the operating day has a sync error`() {
        coEvery { businessDayDao.findOpen("stall-1") } returns BusinessDayEntity(
            id = "day-1", stallId = "stall-1", deviceId = "device-1", cashierId = "cashier-1",
            businessDate = "2026-09-08", openedAt = "2026-09-08T00:00:00Z",
            updatedAt = "2026-09-08T00:00:00Z", syncError = "OPERATING_DAY_TRANSFER_REQUIRED",
        )

        assertThrows(IllegalStateException::class.java) {
            runTest { repository.checkout(listOf(CartLine(cartProduct, 1)), 2_000, checkoutId) }
        }

        coVerify(exactly = 0) { transactionDao.upsert(any()) }
        coVerify(exactly = 0) { productDao.updateStock(any(), any(), any()) }
        verify(exactly = 0) { syncTrigger.triggerNow() }
    }

    @Test
    fun `recipe checkout deducts packaging when tracked powder has no sealed stock`() = runTest {
        val powder = cartProduct.copy(id = "powder", name = "Vanilla powder", isSellable = false, productType = "raw", baseUnit = "g", unitsInStock = 0.0, costPriceCents = 50_000, packSize = 1_000.0, tracksPowderPacks = true)
        val cone = cartProduct.copy(id = "cone", name = "Cone", isSellable = false, productType = "packaging", unitsInStock = 30.0, costPriceCents = 10_000, catalogCostPrice = "100", packSize = 50.0)
        coEvery { recipeDao.findForParents(listOf(cartProduct.id)) } returns listOf(
            ProductRecipeEntity("r1", "stall-1", cartProduct.id, powder.id, 80.0, "2026-09-08T00:00:00Z"),
            ProductRecipeEntity("r2", "stall-1", cartProduct.id, cone.id, 1.0, "2026-09-08T00:00:00Z"),
        )
        coEvery { productDao.findById(any()) } answers { when (firstArg<String>()) { cartProduct.id -> cartProduct; powder.id -> powder; else -> cone } }
        val movements = io.mockk.slot<List<InventoryLedgerEntity>>()
        val savedSale = io.mockk.slot<TransactionEntity>()
        coEvery { ledgerDao.upsertAll(capture(movements)) } returns Unit
        coEvery { transactionDao.upsert(capture(savedSale)) } returns Unit

        repository.checkout(listOf(CartLine(cartProduct, 2)), 4_000, checkoutId)

        coVerify(exactly = 0) { productDao.updateStock(powder.id, any(), any()) }
        coVerify { productDao.updateStock(cone.id, 28.0, any()) }
        coVerify(exactly = 0) { productDao.updateStock(cartProduct.id, any(), any()) }
        assertEquals(setOf(cone.id to -2.0), movements.captured.map { it.productId to it.quantityDelta }.toSet())
        assertEquals("day-1", savedSale.captured.businessDayId)
        assertEquals(400L, savedSale.captured.cogsCents)
        assertEquals(setOf(cone.id to 400L),
            movements.captured.map { it.productId to it.costTotalCents }.toSet())
    }

    @Test
    fun `retry returns the committed receipt without changing stock even if sync scheduling failed`() = runTest {
        val savedSale = io.mockk.slot<TransactionEntity>()
        val savedItems = io.mockk.slot<List<TransactionItemEntity>>()
        coEvery { transactionDao.upsert(capture(savedSale)) } returns Unit
        coEvery { transactionDao.upsertItems(capture(savedItems)) } returns Unit
        coEvery { productDao.findById(cartProduct.id) } returns cartProduct
        every { syncTrigger.triggerNow() } throws IllegalStateException("Scheduler unavailable")
        val lines = listOf(CartLine(cartProduct, 2))

        val first = repository.checkout(lines, 5_000, checkoutId)
        coEvery { transactionDao.findById(checkoutId.toString()) } returns savedSale.captured
        coEvery { transactionDao.getItems(checkoutId.toString()) } returns savedItems.captured
        coEvery { businessDayDao.findOpen("stall-1") } returns null
        coEvery { stateDao.find("catalog-revision:device-1") } returns null

        assertEquals(first, repository.checkout(lines, 5_000, checkoutId))
        val conflict = runCatching { repository.checkout(lines, 6_000, checkoutId) }.exceptionOrNull()
        assertEquals("This checkout was already saved with different details. Review the original receipt.", conflict?.message)
        coVerify(exactly = 1) { transactionDao.upsert(any()) }
        coVerify(exactly = 1) { transactionDao.upsertItems(any()) }
        coVerify(exactly = 1) { productDao.updateStock(cartProduct.id, 8.0, any()) }
        coVerify(exactly = 1) { ledgerDao.upsertAll(any()) }
    }
}
