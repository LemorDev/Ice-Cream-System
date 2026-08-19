package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stalls")
data class StallEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)
