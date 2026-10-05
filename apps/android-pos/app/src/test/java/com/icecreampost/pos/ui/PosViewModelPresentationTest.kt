package com.icecreampost.pos.ui

import androidx.lifecycle.ViewModelStore
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.repository.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import com.icecreampost.pos.ui.component.ServingFilter
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PosViewModelPresentationTest {
    private val checkout = mockk<CheckoutRepository>(relaxed = true)
    private val products = mockk<ProductRepository>()
    private val sessions = mockk<SessionRepository>(relaxed = true)
    private val sync = mockk<SyncRepository>(relaxed = true)
    private val state = mockk<SyncStateDao>()
    private val days = mockk<BusinessDayRepository>()
    private val store = ViewModelStore()
    private lateinit var model: PosViewModel
    private val catalog = MutableStateFlow<List<ProductEntity>>(emptyList())
    private val recipes = MutableStateFlow<List<ProductRecipeEntity>>(emptyList())

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { sessions.observeSession() } returns flowOf(null)
        coEvery { sessions.restoreStoredSession() } returns null
        every { products.observeProducts() } returns catalog
        every { products.observeRecipes() } returns recipes
        every { checkout.observeTransactions() } returns flowOf(emptyList())
        every { state.observe(any()) } returns flowOf(null)
        every { days.observeLatest() } returns flowOf(null)
        every { days.observeDeductions() } returns flowOf(emptyList())
        model = PosViewModel(mockk(relaxed = true), products, checkout, sessions, sync, state, days)
        store.put("pos", model)
    }

    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun `serving filters use recipes keep search and reset when selecting a category`() = runTest {
        fun product(id: String, name: String, category: String, type: String = "sellable") = ProductEntity(
            id = id, name = name, category = category, priceCents = 4000, unitsInStock = 10.0,
            updatedAt = "2026-10-01", productType = type, isSellable = type == "sellable")
        catalog.value = listOf(product("vanilla", "Vanilla", "Ice cream"), product("chocolate", "Chocolate cone", "Ice cream"),
            product("cup", "Sundae cup", "Packaging", "packaging"), product("deleted", "Deleted cup", "Ice cream").copy(deletedAt = "today"))
        recipes.value = listOf(ProductRecipeEntity("recipe", "stall", "vanilla", "cup", 1.0, "2026-10-01"))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.filteredProducts.collect() }
        runCurrent()
        model.setServing(ServingFilter.CUP)
        runCurrent()
        assertEquals(listOf("vanilla"), model.filteredProducts.value.map { it.id })
        model.setQuery("chocolate")
        runCurrent()
        assertTrue(model.filteredProducts.value.isEmpty())
        model.setServing(ServingFilter.CONE)
        runCurrent()
        assertEquals(listOf("chocolate"), model.filteredProducts.value.map { it.id })
        model.setQuery("")
        model.setCategory("Ice cream")
        runCurrent()
        assertNull(model.serving.value)
        assertEquals(2, model.filteredProducts.value.size)
        model.setServing(ServingFilter.CUP)
        model.setCategory(null)
        runCurrent()
        assertNull(model.serving.value)
        assertEquals(2, model.filteredProducts.value.size)
    }

    @Test fun `sync in progress allows a local checkout and does not surface network error as a sale error`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { sync.sync() } coAnswers { release.await(); throw java.io.IOException("Offline") }
        val saved = CheckoutReceipt("sale", "LOCAL-1", 4000, 5000, 1000)
        coEvery { checkout.checkout(any(), 5000) } returns saved
        coEvery { checkout.getHistoricalReceipt("sale") } returns null
        runCurrent()
        model.syncToIms()
        runCurrent()
        assertTrue(model.isSyncing.value)
        assertFalse(model.isBusy.value)
        var complete = false
        model.checkout(5000) { complete = true }
        runCurrent()
        assertTrue(complete)
        assertEquals(saved, model.receipt.value)
        release.complete(Unit)
        runCurrent()
        assertFalse(model.isSyncing.value)
        assertNull(model.error.value)
        assertEquals("Offline", model.syncMessage.value)
        coVerify(exactly = 1) { checkout.checkout(any(), 5000) }
    }

    @Test fun `receipt detail read failure cannot cause a committed checkout to fail`() = runTest {
        val saved = CheckoutReceipt("sale", "LOCAL-1", 4000, 5000, 1000)
        coEvery { checkout.checkout(any(), 5000) } returns saved
        coEvery { checkout.getHistoricalReceipt("sale") } throws IllegalStateException("Read unavailable")
        runCurrent()
        var complete = false
        model.checkout(5000) { complete = true }
        runCurrent()
        assertTrue(complete)
        assertNull(model.error.value)
        assertEquals(saved, model.receipt.value)
        assertNull(model.completedReceiptDetails.value)
    }
}
