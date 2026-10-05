package com.icecreampost.pos.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    @SerialName("p_stall_code") val stallCode: String,
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
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("is_activated") val isActivated: Boolean = false,
)

@Serializable
data class ActivateDeviceRequest(
    @SerialName("p_activation_code") val activationCode: String,
    @SerialName("p_hardware_id") val hardwareId: String,
)

@Serializable
data class ActivateDeviceResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("resume_day") val resumeDay: RecoveredBusinessDay? = null,
)

@Serializable
data class RecoveredBusinessDay(
    val id: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("cashier_id") val cashierId: String,
    @SerialName("opening_notes") val openingNotes: String? = null,
    @SerialName("known_sales") val knownSales: Double = 0.0,
    @SerialName("known_orders") val knownOrders: Int = 0,
    @SerialName("known_deductions") val knownDeductions: Double = 0.0,
    @SerialName("known_profit_deductions") val knownProfitDeductions: Double = 0.0,
    @SerialName("known_cogs") val knownCogs: Double = 0.0,
    @SerialName("known_waste") val knownWaste: Double = 0.0,
)

@Serializable
data class RecoveredDayRequest(@SerialName("p_device_id") val deviceId: String)

@Serializable
data class PrepareReplacementRequest(@SerialName("p_device_id") val deviceId: String)

@Serializable
data class PrepareReplacementResponse(val status: String)
