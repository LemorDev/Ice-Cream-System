package com.icecreampost.pos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_session")
data class AppSessionEntity(
    @PrimaryKey val id: String = "current",
    val userId: String? = null,
    val stallId: String? = null,
    val displayName: String,
    val role: String,
    val sessionToken: String? = null,
    val expiresAt: String? = null,
    val isActivated: Boolean = false,
)
