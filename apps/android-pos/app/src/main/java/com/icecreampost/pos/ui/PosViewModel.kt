package com.icecreampost.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.repository.CheckoutReceipt
import com.icecreampost.pos.data.repository.HistoricalReceipt
import com.icecreampost.pos.data.repository.CheckoutRepository
import com.icecreampost.pos.data.repository.ProductRepository
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.data.repository.SyncRepository
import com.icecreampost.pos.data.repository.BusinessDayRepository
import com.icecreampost.pos.data.repository.SaleReversalRepository
import com.icecreampost.pos.data.local.entity.BusinessDayEntity
import com.icecreampost.pos.domain.model.CartLine
import com.icecreampost.pos.domain.model.additionalMenuPortions
import com.icecreampost.pos.domain.model.MenuAvailability
import com.icecreampost.pos.domain.model.menuAvailability
import com.icecreampost.pos.ui.component.ServingFilter
import com.icecreampost.pos.ui.component.menuServings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PosViewModel @Inject constructor(
    private val stockReceivingRepository: com.icecreampost.pos.data.repository.StockReceivingRepository,
    private val productRepository: ProductRepository,
    private val checkoutRepository: CheckoutRepository,
    private val sessionRepository: SessionRepository,
    private val syncRepository: SyncRepository,
    private val syncStateDao: SyncStateDao,
    private val businessDayRepository: BusinessDayRepository,
    private val saleReversalRepository: SaleReversalRepository,
) : ViewModel() {
    val session: StateFlow<AppSessionEntity?> = sessionRepository.observeSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val products: StateFlow<List<ProductEntity>> = productRepository.observeProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recipes = productRepository.observeRecipes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val lowStockProducts: StateFlow<List<ProductEntity>> = products
        .combine(MutableStateFlow(Unit)) { values, _ ->
            values.filter { product ->
                if (product.productType == "sellable" || product.deletedAt != null) false
                else if (product.lowStockThreshold > 0) product.unitsInStock <= product.lowStockThreshold
                else when {
                    product.baseUnit == "g" && product.name.contains("powder", true) -> product.unitsInStock <= 1000.0
                    product.name.contains("cone", true) -> product.unitsInStock < 20.0
                    product.name.contains("cup", true) -> product.unitsInStock < 15.0
                    else -> false
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = checkoutRepository.observeTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val saleReversals = saleReversalRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncState: StateFlow<SyncStateEntity?> = syncStateDao.observe("sync")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val inventorySyncState: StateFlow<SyncStateEntity?> = syncStateDao.observe("catalog-ledger")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val businessDay: StateFlow<BusinessDayEntity?> = businessDayRepository.observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val revenueDeductions = businessDayRepository.observeDeductions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val searchQuery = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val selectedServing = MutableStateFlow<ServingFilter?>(null)
    val serving = selectedServing.asStateFlow()
    private val cart = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _busy = MutableStateFlow(false)
    private val _syncBusy = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _receipt = MutableStateFlow<CheckoutReceipt?>(null)
    private val _historicalReceipt = MutableStateFlow<HistoricalReceipt?>(null)
    private val _completedReceiptDetails = MutableStateFlow<HistoricalReceipt?>(null)
    private val _syncMessage = MutableStateFlow<String?>(null)
    private val _sessionReady = MutableStateFlow(false)
    private val _amountsVisible = MutableStateFlow(true)

    val isBusy = _busy.asStateFlow()
    val isSyncing = _syncBusy.asStateFlow()
    val error = _error.asStateFlow()
    val receipt = _receipt.asStateFlow()
    val historicalReceipt = _historicalReceipt.asStateFlow()
    val completedReceiptDetails = _completedReceiptDetails.asStateFlow()
    val syncMessage = _syncMessage.asStateFlow()
    val sessionReady = _sessionReady.asStateFlow()
    val amountsVisible = _amountsVisible.asStateFlow()
    val query: StateFlow<String> = searchQuery.asStateFlow()
    val category: StateFlow<String?> = selectedCategory.asStateFlow()

    init {
        viewModelScope.launch {
            var restoredSession: AppSessionEntity? = null
            try {
                restoredSession = sessionRepository.restoreStoredSession()
                if (restoredSession != null) syncRepository.resetStatus()
            } finally {
                _sessionReady.value = true
            }
            if (restoredSession?.isActivated == true) syncToIms()
        }
    }

    val filteredProducts: StateFlow<List<ProductEntity>> = combine(
        products,
        searchQuery,
        selectedCategory,
        selectedServing,
        recipes,
    ) { allProducts, query, category, serving, allRecipes ->
        val productsById = allProducts.associateBy { it.id }
        val recipesByMenu = allRecipes.groupBy { it.parentProductId }
        allProducts.filter { product ->
            product.isSellable && product.productType == "sellable" && product.deletedAt == null &&
                product.name.contains(query.trim(), ignoreCase = true) &&
                (category == null || product.category == category) &&
                (serving == null || serving in menuServings(product, productsById, recipesByMenu[product.id].orEmpty()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<String>> = products
        .combine(MutableStateFlow(Unit)) { values, _ -> values.filter { it.isSellable && it.productType == "sellable" && it.deletedAt == null }.map { it.category }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cartLines: StateFlow<List<CartLine>> = combine(products, cart) { allProducts, quantities ->
        quantities.mapNotNull { (productId, quantity) ->
            allProducts.firstOrNull { it.id == productId }?.let { CartLine(it, quantity) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availability: StateFlow<Map<String, MenuAvailability>> = combine(products, recipes, cart) { allProducts, allRecipes, quantities ->
        allProducts.filter { it.isSellable && it.productType == "sellable" && it.deletedAt == null }
            .associate { it.id to menuAvailability(it, allProducts, allRecipes, quantities) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setQuery(value: String) { searchQuery.value = value }
    fun setCategory(value: String?) {
        selectedServing.value = null
        selectedCategory.value = value
    }

    fun setServing(value: ServingFilter?) {
        selectedCategory.value = null
        selectedServing.value = value
    }

    fun addToCart(product: ProductEntity) {
        val currentQuantity = cart.value[product.id] ?: 0
        if (additionalAvailable(product) > 0) {
            cart.value = cart.value + (product.id to currentQuantity + 1)
        } else {
            _error.value = if (hasRecipe(product)) "Not enough ingredients for ${product.name}."
                else "${product.name} is sold out."
        }
    }

    fun hasRecipe(product: ProductEntity): Boolean = recipes.value.any { it.parentProductId == product.id }

    fun additionalAvailable(product: ProductEntity): Int =
        additionalMenuPortions(product, products.value, recipes.value, cart.value)

    fun removeFromCart(productId: String) {
        val currentQuantity = cart.value[productId] ?: return
        cart.value = if (currentQuantity <= 1) cart.value - productId
        else cart.value + (productId to currentQuantity - 1)
    }

    fun clearCart() { cart.value = emptyMap() }

    fun openDay(notes: String = "") { runAction { businessDayRepository.openDay(notes) } }
    fun closeDay(
        notes: String = "",
        collectedCashCents: Long? = null,
        onSuccess: () -> Unit = {},
    ) {
        runAction(onSuccess) {
            businessDayRepository.closeDay(notes, collectedCashCents)
        }
    }

    fun recordRevenueDeduction(amountCents: Long, reason: String, affectsProfit: Boolean = true, onSuccess: () -> Unit = {}) {
        runAction(onSuccess) { businessDayRepository.recordDeduction(amountCents, reason, affectsProfit) }
    }

    fun reverseSale(transactionId: String, kind: String, reason: String, restock: Boolean, cashReturnedCents: Long,
                    onSuccess: () -> Unit = {}) {
        runAction(onSuccess) { saleReversalRepository.reverse(transactionId, kind, reason, restock, cashReturnedCents) }
    }

    fun signIn(stallCode: String, email: String, password: String) {
        runAction {
            val signedIn = sessionRepository.signIn(stallCode, email, password)
            syncRepository.resetStatus()
            if (signedIn.isActivated) {
                syncRepository.sync()
            }
        }
    }

    fun activate(deviceCode: String, onSuccess: () -> Unit) {
        runAction(onSuccess) {
            sessionRepository.activate(deviceCode)
            syncRepository.sync()
        }
    }

    fun prepareReplacement() {
        runAction {
            val stallId = session.value?.stallId ?: error("Sign in before preparing replacement.")
            check(!businessDayRepository.hasOpenDay(stallId)) { "Close the operating day before preparing replacement." }
            val report = syncRepository.sync()
            check(report.permanentFailures == 0 && !syncRepository.hasPendingWork()) {
                "Some POS records have not synced. Resolve them before replacing this device."
            }
            sessionRepository.prepareReplacement()
            _syncMessage.value = "This POS is ready for replacement for 30 minutes. Ask the System Administrator to create the new code in Users & access."
        }
    }

    fun signOut() {
        runAction {
            sessionRepository.signOut()
            clearCart()
            searchQuery.value = ""
            selectedCategory.value = null
            selectedServing.value = null
            dismissReceipt()
            dismissHistoricalReceipt()
        }
    }

    fun checkout(cashReceivedCents: Long, onSuccess: () -> Unit) {
        runAction(onSuccess) {
            _receipt.value = checkoutRepository.checkout(cartLines.value, cashReceivedCents)
            cart.value = emptyMap()
            // This presentation read must not turn an already committed sale into a failed checkout.
            _completedReceiptDetails.value = runCatching {
                checkoutRepository.getHistoricalReceipt(requireNotNull(_receipt.value).transactionId)
            }.getOrNull()
        }
    }

    fun receiveStock(productId: String, quantity: Double, notes: String, onSuccess: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        runAction(onSuccess) { stockReceivingRepository.receive(productId, quantity, notes) }
    }

    fun dismissError() { _error.value = null }
    fun dismissReceipt() { _receipt.value = null; _completedReceiptDetails.value = null }
    fun viewHistoricalReceipt(transactionId: String) {
        _historicalReceipt.value = null
        _error.value = null
        viewModelScope.launch {
            try {
                _historicalReceipt.value = checkoutRepository.getHistoricalReceipt(transactionId)
                    ?: error("Receipt is no longer available on this device.")
            } catch (exception: Exception) {
                _error.value = exception.message ?: "Could not load the receipt."
            }
        }
    }
    fun dismissHistoricalReceipt() { _historicalReceipt.value = null }
    fun toggleAmountsVisible() { _amountsVisible.value = !_amountsVisible.value }

    fun syncToIms() {
        if (_syncBusy.value || syncState.value?.status == "running") return
        _syncBusy.value = true
        viewModelScope.launch {
            try {
                _syncMessage.value = null
                val report = syncRepository.sync()
                _syncMessage.value = if (report.permanentFailures == 0) {
                    val uploads = buildList {
                        if (report.pushed > 0) add(if (report.pushed == 1) "1 sale" else "${report.pushed} sales")
                        if (report.businessDaysSynced > 0) add(if (report.businessDaysSynced == 1) "1 operating-day update" else "${report.businessDaysSynced} operating-day updates")
                        if (report.ledgerEntriesSynced > 0) add(if (report.ledgerEntriesSynced == 1) "1 inventory movement" else "${report.ledgerEntriesSynced} inventory movements")
                        if (report.closingsSynced > 0) add(if (report.closingsSynced == 1) "1 daily closing" else "${report.closingsSynced} daily closings")
                        if (report.deductionsSynced > 0) add(if (report.deductionsSynced == 1) "1 deduction" else "${report.deductionsSynced} deductions")
                        if (report.reversalsSynced > 0) add(if (report.reversalsSynced == 1) "1 reversal" else "${report.reversalsSynced} reversals")
                    }
                    "${uploads.joinToString(" and ").ifBlank { "No queued changes" }} uploaded. Catalog and inventory updated."
                } else {
                    "${report.pushed} sales uploaded; ${report.permanentFailures} queued records need attention. ${report.failureMessage.orEmpty()} Catalog and inventory updated."
                }
            } catch (error: Exception) {
                _syncMessage.value = error.message ?: "Sync failed. Saved sales remain on this device."
            } finally {
                _syncBusy.value = false
            }
        }
    }

    private fun runAction(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                action()
                onSuccess()
            } catch (error: Exception) {
                _error.value = error.message ?: "Something went wrong."
            } finally {
                _busy.value = false
            }
        }
    }
}
