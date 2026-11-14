package com.icecreampost.pos.domain.model

import com.icecreampost.pos.data.local.entity.ProductEntity
import java.math.BigDecimal

const val CLOSING_STOCK_COUNT = "closing_count"
const val CLOSING_COUNTS_CATALOG = "closing-counts-catalog"
private val maxStockQuantity = BigDecimal("999999999.999")
private val stockQuantityPattern = Regex("""[0-9]+(?:\.[0-9]{1,3})?""")

data class ClosingStockCount(val quantity: Double, val unit: String)

val ProductEntity.closingCountUnit: String
    get() = if (tracksPowderPacks) "pack" else baseUnit

val ProductEntity.requiresClosingCount: Boolean
    get() = countAtClosing && deletedAt == null && productType in listOf("raw", "packaging")

fun ProductEntity.parseClosingCount(input: String): ClosingStockCount? {
    val unit = closingCountUnit
    val value = input.trim().replace(',', '.')
    if (value.length > 16 || !stockQuantityPattern.matches(value)) return null
    val quantity = value.toBigDecimal().stripTrailingZeros()
    val precision = if (unit == "piece" || unit == "pack") 0 else 3
    if (quantity.signum() < 0 || quantity > maxStockQuantity || quantity.scale() > precision) return null
    val count = ClosingStockCount(quantity.toDouble(), unit)
    return count.takeIf { closingCountInBaseUnits(it) <= maxStockQuantity }
}

fun ProductEntity.closingCountInBaseUnits(count: ClosingStockCount): BigDecimal =
    BigDecimal.valueOf(count.quantity).let { quantity ->
        if (count.unit == "pack") quantity * BigDecimal.valueOf(packSize) * BigDecimal.valueOf(conversionRate)
        else quantity
    }
