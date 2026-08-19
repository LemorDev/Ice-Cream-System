package com.icecreampost.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import com.icecreampost.pos.data.local.entity.ProductEntity
import com.icecreampost.pos.data.local.entity.TransactionEntity
import com.icecreampost.pos.data.local.entity.SyncStateEntity
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.repository.CheckoutReceipt
import com.icecreampost.pos.data.repository.CheckoutRepository
import com.icecreampost.pos.data.repository.ProductRepository
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.domain.model.CartLine
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
    private val productRepository: ProductRepository,
    private val checkoutRepository: CheckoutRepository,
    private val sessionRepository: SessionRepository,
    private val syncStateDao: SyncStateDao,
) : ViewModel() {
    val session: StateFlow<AppSessionEntity?> = sessionRepository.observeSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val products: StateFlow<List<ProductEntity>> = productRepository.observeProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = checkoutRepository.observeTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncState: StateFlow<SyncStateEntity?> = syncStateDao.observe("sync")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val searchQuery = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val cart = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _busy = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _receipt = MutableStateFlow<CheckoutReceipt?>(null)

    val isBusy = _busy.asStateFlow()
    val error = _error.asStateFlow()
    val receipt = _receipt.asStateFlow()
    val query: StateFlow<String> = searchQuery.asStateFlow()
    val category: StateFlow<String?> = selectedCategory.asStateFlow()

    val filteredProducts: StateFlow<List<ProductEntity>> = combine(
        products,
        searchQuery,
        selectedCategory,
    ) { allProducts, query, category ->
        allProducts.filter { product ->
            product.isSellable &&
                product.name.contains(query.trim(), ignoreCase = true) &&
                (category == null || product.category == category)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<String>> = products
        .combine(MutableStateFlow(Unit)) { values, _ -> values.map { it.category }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cartLines: StateFlow<List<CartLine>> = combine(products, cart) { allProducts, quantities ->
        quantities.mapNotNull { (productId, quantity) ->
            allProducts.firstOrNull { it.id == productId }?.let { CartLine(it, quantity) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) { searchQuery.value = value }
    fun setCategory(value: String?) { selectedCategory.value = value }

    fun addToCart(product: ProductEntity) {
        val currentQuantity = cart.value[product.id] ?: 0
        if (currentQuantity < product.unitsInStock) {
            cart.value = cart.value + (product.id to currentQuantity + 1)
        } else {
            _error.value = "No more ${product.name} is available locally."
        }
    }

    fun removeFromCart(productId: String) {
        val currentQuantity = cart.value[productId] ?: return
        cart.value = if (currentQuantity <= 1) cart.value - productId
        else cart.value + (productId to currentQuantity - 1)
    }

    fun clearCart() { cart.value = emptyMap() }

    fun signIn(email: String, password: String, onSuccess: () -> Unit) {
        runAction(onSuccess) { sessionRepository.signIn(email, password) }
    }

    fun activate(deviceCode: String, onSuccess: () -> Unit) {
        runAction(onSuccess) { sessionRepository.activate(deviceCode) }
    }

    fun signOut(onComplete: () -> Unit) {
        runAction(onComplete) { sessionRepository.signOut() }
    }

    fun checkout(cashReceivedCents: Long, onSuccess: () -> Unit) {
        runAction(onSuccess) {
            _receipt.value = checkoutRepository.checkout(cartLines.value, cashReceivedCents)
            cart.value = emptyMap()
        }
    }

    fun dismissError() { _error.value = null }
    fun dismissReceipt() { _receipt.value = null }

    fun refreshProducts() {
        runAction { productRepository.refreshFromCloud() }
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
