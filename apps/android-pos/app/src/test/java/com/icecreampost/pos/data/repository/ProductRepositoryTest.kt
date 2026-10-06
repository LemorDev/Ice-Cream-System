package com.icecreampost.pos.data.repository

import androidx.room.withTransaction
import com.icecreampost.pos.data.local.dao.InventoryLedgerDao
import com.icecreampost.pos.data.local.dao.ProductDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.local.dao.ProductRecipeDao
import com.icecreampost.pos.data.local.database.CoolerzDatabase
import com.icecreampost.pos.data.local.entity.InventoryLedgerEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.dto.InventoryLedgerDto
import com.icecreampost.pos.data.remote.dto.ProductDto
import com.icecreampost.pos.data.remote.dto.CatalogPageRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class ProductRepositoryTest {
    private val database = mockk<CoolerzDatabase>()
    private val productDao = mockk<ProductDao>()
    private val ledgerDao = mockk<InventoryLedgerDao>()
    private val stateDao = mockk<SyncStateDao>()
    private val recipeDao = mockk<ProductRecipeDao>(relaxed = true)
    private val api = mockk<SupabaseApi>()
    private val products = mutableMapOf<String, ProductEntity>()
    private val ledger = mutableMapOf<String, InventoryLedgerEntity>()
    private val states = mutableMapOf<String, SyncStateEntity>()
    private var inTransaction = false
    private var failLedgerWrite = false
    private lateinit var repository: ProductRepository
    private val firstTime = "2026-09-08T01:00:00Z"
    private val secondTime = "2026-09-08T02:00:00Z"
    private val thirdTime = "2026-09-08T03:00:00Z"
    private val remoteProduct = ProductDto(
        id = "product-1", stallId = "stall-1", name = "Vanilla", updatedAt = firstTime,
    )
    private val receipt = InventoryLedgerDto(
        id = "receive-1", stallId = "stall-1", productId = "product-1",
        quantityDelta = 10.0, movementType = "receive", occurredAt = firstTime, updatedAt = firstTime,
    )

    @Before
    fun setUp() {
        // Model Room's commit/rollback boundary without requiring an Android device.
        // DAO writes assert that the repository includes them in that boundary.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction<Unit>(any()) } coAnswers {
            val oldProducts = products.toMap()
            val oldLedger = ledger.toMap()
            val oldStates = states.toMap()
            inTransaction = true
            try {
                secondArg<suspend () -> Unit>().invoke()
            } catch (error: Exception) {
                products.clear(); products.putAll(oldProducts)
                ledger.clear(); ledger.putAll(oldLedger)
                states.clear(); states.putAll(oldStates)
                throw error
            } finally {
                inTransaction = false
            }
        }
        coEvery { productDao.findById(any()) } coAnswers { products[firstArg<String>()] }
        coEvery { productDao.upsertAll(any()) } coAnswers {
            assertTrue(inTransaction)
            firstArg<List<ProductEntity>>().forEach { products[it.id] = it }
        }
        coEvery { productDao.updateStock(any(), any(), any()) } coAnswers {
            assertTrue(inTransaction)
            val id = firstArg<String>()
            products[id] = products.getValue(id).copy(unitsInStock = secondArg())
        }
        coEvery { ledgerDao.findById(any()) } coAnswers { ledger[firstArg<String>()] }
        coEvery { ledgerDao.findByReferenceAndMovement(any(), any(), any()) } coAnswers {
            ledger.values.firstOrNull { it.referenceId == firstArg<String>() && it.movementType == secondArg<String>() && it.productId == thirdArg<String>() }
        }
        coEvery { ledgerDao.upsertAll(any()) } coAnswers {
            assertTrue(inTransaction)
            if (failLedgerWrite) error("Simulated interrupted ledger write")
            firstArg<List<InventoryLedgerEntity>>().forEach { ledger[it.id] = it }
        }
        coEvery { stateDao.find(any()) } coAnswers { states[firstArg<String>()] }
        coEvery { stateDao.upsert(any()) } coAnswers {
            assertTrue(inTransaction)
            val state = firstArg<SyncStateEntity>()
            states[state.key] = state
        }
        stubLedger(listOf(receipt))
        stubProducts(listOf(remoteProduct))
        coEvery { api.getRecipesPage(any()) } returns emptyList()
        repository = ProductRepository(database, productDao, ledgerDao, stateDao, recipeDao, api)
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `first pull counts opening inventory once`() = runTest {
        repository.refreshFromCloud()
        assertEquals(10.0, products.getValue("product-1").unitsInStock, 0.001)
        assertEquals(firstTime, states["catalog-ledger"]?.cursorUpdatedAt)
    }

    @Test
    fun `older server without paged recipe RPC is rejected before changing stock`() = runTest {
        coEvery { api.getRecipesPage(any()) } throws httpError(404)

        assertTrue(runCatching { repository.refreshFromCloud() }.isFailure)

        assertTrue(products.isEmpty())
        assertTrue(ledger.isEmpty())
    }

    @Test
    fun `first pull sums fractional movements before storing integer stock`() = runTest {
        stubLedger(listOf(
            receipt.copy(id = "receive-half-1", quantityDelta = 0.5),
            receipt.copy(id = "receive-half-2", quantityDelta = 0.5),
        ))

        repository.refreshFromCloud()

        assertEquals(1.0, products.getValue("product-1").unitsInStock, 0.001)
    }

    @Test
    fun `new product in incremental pull does not double its received stock`() = runTest {
        states["catalog"] = SyncStateEntity(key = "catalog", value = "legacy", cursorUpdatedAt = firstTime)
        stubProducts(listOf(remoteProduct.copy(updatedAt = secondTime)))
        stubLedger(listOf(receipt.copy(updatedAt = secondTime)))

        repository.refreshFromCloud()

        assertEquals(10.0, products.getValue("product-1").unitsInStock, 0.001)
        coVerify { api.getProductsPage(match { it.afterUpdatedAt == "2026-09-08T00:59:59Z" && it.afterId == null }) }
        coVerify { api.getInventoryLedgerPage(match { it.afterUpdatedAt == "2026-09-08T00:59:59Z" && it.afterId == null }) }
    }

    @Test
    fun `newer product changes do not advance the ledger cursor past unseen stock`() = runTest {
        stubProducts(listOf(remoteProduct.copy(updatedAt = thirdTime)))
        repository.refreshFromCloud()
        stubLedger(listOf(
            receipt.copy(id = "receive-2", quantityDelta = 2.0, updatedAt = secondTime),
        ))
        stubProducts(emptyList())

        repository.refreshFromCloud()

        coVerify { api.getInventoryLedgerPage(match { it.afterUpdatedAt == "2026-09-08T00:59:59Z" && it.afterId == null }) }
        coVerify { api.getProductsPage(match { it.afterUpdatedAt == "2026-09-08T02:59:59Z" && it.afterId == null }) }
        assertEquals(12.0, products.getValue("product-1").unitsInStock, 0.001)
        assertEquals(secondTime, states["catalog-ledger"]?.cursorUpdatedAt)
    }

    @Test
    fun `replayed response does not add inventory again`() = runTest {
        repository.refreshFromCloud()
        repository.refreshFromCloud()
        assertEquals(10.0, products.getValue("product-1").unitsInStock, 0.001)
    }

    @Test
    fun `interrupted pull rolls back stock and cursor before retry`() = runTest {
        failLedgerWrite = true
        assertTrue(runCatching { repository.refreshFromCloud() }.isFailure)
        assertTrue(products.isEmpty())
        assertTrue(ledger.isEmpty())
        assertTrue(states.isEmpty())

        failLedgerWrite = false
        repository.refreshFromCloud()
        assertEquals(10.0, products.getValue("product-1").unitsInStock, 0.001)
    }

    @Test
    fun `catalog refresh preserves a checkout completed during the network request`() = runTest {
        repository.refreshFromCloud()
        stubLedger(emptyList())
        coEvery { api.getProductsPage(any()) } coAnswers {
            if (firstArg<CatalogPageRequest>().afterId != null) emptyList()
            else {
                products["product-1"] = products.getValue("product-1").copy(unitsInStock = 7.0)
                listOf(remoteProduct.copy(salePrice = 50.0, updatedAt = secondTime))
            }
        }

        repository.refreshFromCloud()

        assertEquals(7.0, products.getValue("product-1").unitsInStock, 0.001)
        assertEquals(5_000L, products.getValue("product-1").priceCents)
    }

    @Test
    fun `equal-time product rows continue past the page boundary`() = runTest {
        val cloud = (0..250).map { index ->
            remoteProduct.copy(id = "product-${index.toString().padStart(4, '0')}")
        }
        coEvery { api.getProductsPage(any()) } coAnswers {
            when (firstArg<CatalogPageRequest>().afterId) {
                null -> cloud.take(250)
                cloud[249].id -> cloud.drop(250)
                else -> emptyList()
            }
        }

        repository.refreshFromCloud()

        assertEquals(251, products.size)
        coVerify { api.getProductsPage(match { it.afterUpdatedAt == firstTime && it.afterId == cloud[249].id }) }
        assertEquals(firstTime, states["catalog-products"]?.cursorUpdatedAt)
    }

    @Test
    fun `interrupted ledger page leaves stock and cursor untouched until retry`() = runTest {
        val receipts = (0..250).map { index ->
            receipt.copy(id = "receive-${index.toString().padStart(4, '0')}", quantityDelta = 0.5)
        }
        coEvery { api.getInventoryLedgerPage(any()) } coAnswers {
            if (firstArg<CatalogPageRequest>().afterId == null) receipts.take(250)
            else throw IOException("page interrupted")
        }

        assertTrue(runCatching { repository.refreshFromCloud() }.isFailure)
        assertTrue(ledger.isEmpty())
        assertTrue(states.isEmpty())

        coEvery { api.getInventoryLedgerPage(any()) } coAnswers {
            when (firstArg<CatalogPageRequest>().afterId) {
                null -> receipts.take(250)
                receipts[249].id -> receipts.drop(250)
                else -> emptyList()
            }
        }
        repository.refreshFromCloud()

        assertEquals(251, ledger.size)
        assertEquals(125.5, products.getValue("product-1").unitsInStock, 0.001)
        assertEquals(firstTime, states["catalog-ledger"]?.cursorUpdatedAt)
    }

    private fun httpError(code: Int): HttpException {
        val body = "{\"message\":\"Not found\"}".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }

    private fun stubProducts(rows: List<ProductDto>) {
        coEvery { api.getProductsPage(any()) } coAnswers {
            if (firstArg<CatalogPageRequest>().afterId == null) rows else emptyList()
        }
    }

    private fun stubLedger(rows: List<InventoryLedgerDto>) {
        coEvery { api.getInventoryLedgerPage(any()) } coAnswers {
            if (firstArg<CatalogPageRequest>().afterId == null) rows else emptyList()
        }
    }
}
