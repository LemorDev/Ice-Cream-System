package com.icecreampost.pos.domain.model

import com.icecreampost.pos.data.local.entity.ProductEntity

internal const val ADD_ON_PRICE_CENTS = 500L
internal const val ADD_ON_CATALOG_STATUS = "success_with_add_ons"

data class CartLine(
    val product: ProductEntity,
    val quantity: Int,
    val addOnQuantity: Int = 0,
) {
    init {
        require(quantity > 0 && addOnQuantity in 0..quantity)
    }

    val lineTotalCents: Long get() = product.priceCents * quantity + ADD_ON_PRICE_CENTS * addOnQuantity
}
