package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "product_categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val stallId: String,
    val name: String,
    val sortOrder: Int = 0,
    val updatedAt: String,
    val deletedAt: String? = null,
)
