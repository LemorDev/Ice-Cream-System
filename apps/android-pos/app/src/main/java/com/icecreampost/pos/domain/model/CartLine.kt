package com.icecreampost.pos.domain.model

import com.icecreampost.pos.data.local.entity.ProductEntity

data class CartLine(
    val product: ProductEntity,
    val quantity: Int,
) {
    val lineTotalCents: Long get() = product.priceCents * quantity
}
