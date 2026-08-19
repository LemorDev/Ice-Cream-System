package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val stallId: String = "",
    val categoryId: String? = null,
    val sku: String = "",
    val name: String,
    val category: String,
    val unit: String = "scoop",
    val priceCents: Long,
    val costPriceCents: Long = 0,
    val lowStockThreshold: Double = 0.0,
    val packSize: Double = 1.0,
    val conversionRate: Double = 1.0,
    val isSellable: Boolean = true,
    val unitsInStock: Int,
    val updatedAt: String,
    val deletedAt: String? = null,
    val localUpdatedAt: String = updatedAt,
)
