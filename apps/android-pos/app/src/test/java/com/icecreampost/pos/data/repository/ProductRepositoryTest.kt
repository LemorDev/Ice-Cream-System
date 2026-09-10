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
        coEvery { api.getInventoryLedger(any(), any()) } returns listOf(receipt)
        coEvery { api.getProducts(any()) } returns listOf(remoteProduct)
        coEvery { api.getRecipes(any()) } returns emptyList()
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
    fun `legacy server without recipe RPC still refreshes products`() = runTest {
        coEvery { api.getRecipes(any()) } throws httpError(404)

        repository.refreshFromCloud()

        assertEquals("Vanilla", products.getValue("product-1").name)
        assertEquals(10.0, products.getValue("product-1").unitsInStock, 0.001)
    }

    @Test
    fun `first pull sums fractional movements before storing integer stock`() = runTest {
        coEvery { api.getInventoryLedger(any(), any()) } returns listOf(
            receipt.copy(id = "receive-half-1", quantityDelta = 0.5),
            receipt.copy(id = "receive-half-2", quantityDelta = 0.5),
        )

        repository.refreshFromCloud()

        assertEquals(1.0, products.getValue("product-1").unitsInStock, 0.001)
    }

    @Test
    fun `new product in incremental pull does not double its received stock`() = runTest {
        states["catalog"] = SyncStateEntity(key = "catalog", value = "legacy", cursorUpdatedAt = firstTime)
        coEvery { api.getProducts(any()) } returns listOf(remoteProduct.copy(updatedAt = secondTime))
        coEvery { api.getInventoryLedger(any(), any()) } returns listOf(receipt.copy(updatedAt = secondTime))

        repository.refreshFromCloud()

        assertEquals(10.0, products.getValue("product-1").unitsInStock, 0.001)
        coVerify { api.getProducts(match { it.updatedAfter == firstTime }) }
        coVerify { api.getInventoryLedger(updatedAtFilter = "gt.$firstTime") }
    }

    @Test
    fun `newer product changes do not advance the ledger cursor past unseen stock`() = runTest {
        coEvery { api.getProducts(any()) } returns listOf(remoteProduct.copy(updatedAt = thirdTime))
        repository.refreshFromCloud()
        coEvery { api.getInventoryLedger(any(), any()) } returns listOf(
            receipt.copy(id = "receive-2", quantityDelta = 2.0, updatedAt = secondTime),
        )
        coEvery { api.getProducts(any()) } returns emptyList()

        repository.refreshFromCloud()

        coVerify { api.getInventoryLedger(updatedAtFilter = "gt.$firstTime") }
        coVerify { api.getProducts(match { it.updatedAfter == thirdTime }) }
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
        coEvery { api.getInventoryLedger(any(), any()) } returns emptyList()
        coEvery { api.getProducts(any()) } coAnswers {
            products["product-1"] = products.getValue("product-1").copy(unitsInStock = 7.0)
            listOf(remoteProduct.copy(salePrice = 50.0, updatedAt = secondTime))
        }

        repository.refreshFromCloud()

        assertEquals(7.0, products.getValue("product-1").unitsInStock, 0.001)
        assertEquals(5_000L, products.getValue("product-1").priceCents)
    }

    private fun httpError(code: Int): HttpException {
        val body = "{\"message\":\"Not found\"}".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }
}
