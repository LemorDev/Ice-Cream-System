package com.icecreampost.pos.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProductDto(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String = "Uncategorized",
    val sku: String = "",
    val name: String,
    val unit: String = "scoop",
    @SerialName("sale_price") val salePrice: Double = 0.0,
    @SerialName("cost_price") val costPrice: Double = 0.0,
    @SerialName("low_stock_threshold") val lowStockThreshold: Double = 0.0,
    @SerialName("pack_size") val packSize: Double = 1.0,
    @SerialName("conversion_rate") val conversionRate: Double = 1.0,
    @SerialName("is_sellable") val isSellable: Boolean = true,
    @SerialName("product_type") val productType: String = if (isSellable) "sellable" else "raw",
    @SerialName("base_unit") val baseUnit: String = "piece",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class ProductRecipeDto(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("parent_product_id") val parentProductId: String,
    @SerialName("ingredient_product_id") val ingredientProductId: String,
    val quantity: Double,
    @SerialName("updated_at") val updatedAt: String,
)
