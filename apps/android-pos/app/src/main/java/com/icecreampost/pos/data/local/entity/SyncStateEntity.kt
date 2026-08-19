package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val cursorUpdatedAt: String? = null,
    val lastSyncAt: String? = null,
    val status: String = "idle",
    val errorMessage: String? = null,
)
