package com.icecreampost.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.icecreampost.pos.data.local.entity.AppSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM app_session WHERE id = 'current' LIMIT 1")
    fun observeCurrent(): Flow<AppSessionEntity?>

    @Query("SELECT * FROM app_session WHERE id = 'current' LIMIT 1")
    suspend fun getCurrent(): AppSessionEntity?

    @Upsert
    suspend fun save(session: AppSessionEntity)

    @Query("DELETE FROM app_session WHERE id = 'current'")
    suspend fun clear()
}
