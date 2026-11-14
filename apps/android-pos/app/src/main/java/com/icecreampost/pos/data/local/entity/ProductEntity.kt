package com.icecreampost.pos.data.local.entity

import androidx.room.ColumnInfo
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
    val tracksPowderPacks: Boolean = false,
    val isSellable: Boolean = true,
    val productType: String = if (isSellable) "sellable" else "raw",
    val baseUnit: String = "piece",
    val unitsInStock: Double,
    val updatedAt: String,
    val deletedAt: String? = null,
    val localUpdatedAt: String = updatedAt,
    val imageUrl: String? = null,
    val catalogCostPrice: String? = null,
    @ColumnInfo(defaultValue = "0") val countAtClosing: Boolean = false,
    val menuGroup: String? = null,
    val servingType: String? = null,
    val servingSize: String? = null,
    val flavorName: String? = null,
)
