package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.ProductRecipeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductRecipeDao {
    @Query("SELECT * FROM product_recipes")
    fun observeAll(): Flow<List<ProductRecipeEntity>>

    @Query("SELECT * FROM product_recipes WHERE parentProductId IN (:parentIds)")
    suspend fun findForParents(parentIds: List<String>): List<ProductRecipeEntity>

    @Upsert
    suspend fun upsertAll(recipes: List<ProductRecipeEntity>)

    @Query("DELETE FROM product_recipes WHERE stallId = :stallId")
    suspend fun deleteForStall(stallId: String)

    @Query("DELETE FROM product_recipes")
    suspend fun deleteAll()
}
