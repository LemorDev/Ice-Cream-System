package com.icecreampost.pos.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InventoryLedgerDto(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("quantity_delta") val quantityDelta: Double,
    @SerialName("movement_type") val movementType: String,
    val reason: String? = null,
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)
