package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.CategoryEntity

@Dao
interface CategoryDao {
    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)
}
