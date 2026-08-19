package com.icecreampost.pos.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushTransactionPayload(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("receipt_number") val receiptNumber: String,
    val status: String,
    val subtotal: Double,
    @SerialName("total_amount") val totalAmount: Double,
    @SerialName("cash_received") val cashReceived: Double? = null,
    @SerialName("change_amount") val changeAmount: Double? = null,
    @SerialName("occurred_at") val occurredAt: String,
    val items: List<PushTransactionItemPayload>,
)

@Serializable
data class PushTransactionItemPayload(
    val id: String,
    @SerialName("product_id") val productId: String?,
    @SerialName("product_name") val productName: String,
    val quantity: Double,
    @SerialName("unit_price") val unitPrice: Double,
    @SerialName("line_total") val lineTotal: Double,
)

@Serializable
data class PushTransactionResponse(
    val status: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("receipt_number") val receiptNumber: String? = null,
    @SerialName("inserted_items") val insertedItems: Int = 0,
    @SerialName("inserted_ledger") val insertedLedger: Int = 0,
)
