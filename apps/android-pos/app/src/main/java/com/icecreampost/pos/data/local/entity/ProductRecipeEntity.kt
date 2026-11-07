package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "product_recipes", indices = [Index(value = ["parentProductId", "ingredientProductId"], unique = true)])
data class ProductRecipeEntity(
    @PrimaryKey val id: String,
    val stallId: String,
    val parentProductId: String,
    val ingredientProductId: String,
    val quantity: Double,
    val updatedAt: String,
)
