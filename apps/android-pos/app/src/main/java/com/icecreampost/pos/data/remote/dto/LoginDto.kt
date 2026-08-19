package com.icecreampost.pos.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    @SerialName("p_email") val email: String,
    @SerialName("p_password") val password: String,
)

@Serializable
data class LoginResponse(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("user_id") val userId: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("expires_at") val expiresAt: String,
)
