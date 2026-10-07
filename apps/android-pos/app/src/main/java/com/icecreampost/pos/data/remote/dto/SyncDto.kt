package com.icecreampost.pos.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushTransactionPayload(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("business_day_id") val businessDayId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("receipt_number") val receiptNumber: String,
    val status: String,
    val subtotal: Double,
    @SerialName("total_amount") val totalAmount: Double,
    @SerialName("cash_received") val cashReceived: Double? = null,
    @SerialName("change_amount") val changeAmount: Double? = null,
    @SerialName("occurred_at") val occurredAt: String,
    val items: List<PushTransactionItemPayload>,
    val components: List<PushSaleComponentPayload>,
)

@Serializable
data class PushTransactionRpcRequest(
    @SerialName("p_transaction") val transaction: PushTransactionPayload,
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
data class PushSaleComponentPayload(
    val id: String,
    @SerialName("product_id") val productId: String,
    val quantity: Double,
    @SerialName("cost_total") val costTotal: Double,
)

@Serializable
data class PushTransactionResponse(
    val status: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("receipt_number") val receiptNumber: String? = null,
    @SerialName("inserted_items") val insertedItems: Int = 0,
    @SerialName("inserted_ledger") val insertedLedger: Int = 0,
)

@Serializable
data class ProductPullRequest(
    @SerialName("p_updated_after") val updatedAfter: String? = null,
)

@Serializable
data class CatalogPageRequest(
    @SerialName("p_after_updated_at") val afterUpdatedAt: String? = null,
    @SerialName("p_after_id") val afterId: String? = null,
    @SerialName("p_limit") val limit: Int = 250,
)

@Serializable
data class PushBusinessDayPayload(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("opening_notes") val openingNotes: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("closing_cash_total") val closingCashTotal: Double? = null,
    @SerialName("closing_notes") val closingNotes: String? = null,
)

@Serializable
data class PushBusinessDayRequest(
    @SerialName("p_day") val day: PushBusinessDayPayload,
)

@Serializable
data class PushBusinessDayResponse(
    val status: String,
    @SerialName("business_day_id") val businessDayId: String,
)

@Serializable
data class PushInventoryEntryPayload(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("business_day_id") val businessDayId: String? = null,
    @SerialName("product_id") val productId: String,
    @SerialName("quantity_delta") val quantityDelta: Double,
    @SerialName("movement_type") val movementType: String,
    val reason: String? = null,
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
)

@Serializable data class PushInventoryEntryRequest(@SerialName("p_entry") val entry: PushInventoryEntryPayload)
@Serializable data class PushInventoryEntryResponse(val status: String, @SerialName("ledger_id") val ledgerId: String)

@Serializable
data class PushDailyClosingPayload(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("business_day_id") val businessDayId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("gross_sales") val grossSales: Double,
    val cogs: Double,
    @SerialName("waste_cost") val wasteCost: Double,
    @SerialName("overhead_cost") val overheadCost: Double,
    @SerialName("net_profit") val netProfit: Double,
    @SerialName("expected_cash") val expectedCash: Double,
    @SerialName("collected_cash") val collectedCash: Double,
    @SerialName("device_id") val deviceId: String,
    @SerialName("closed_at") val closedAt: String,
    @SerialName("revenue_deduction") val revenueDeduction: Double = 0.0,
    @SerialName("deduction_reason") val deductionReason: String? = null,
    @SerialName("sale_count") val saleCount: Int = 0,
    @SerialName("movement_count") val movementCount: Int = 0,
    @SerialName("deduction_count") val deductionCount: Int = 0,
    @SerialName("reversal_count") val reversalCount: Int = 0,
)

@Serializable data class PushDailyClosingRequest(@SerialName("p_closing") val closing: PushDailyClosingPayload)
@Serializable data class PushDailyClosingResponse(val status: String, @SerialName("closing_id") val closingId: String)

@Serializable
data class PushRevenueDeductionPayload(
    val id: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("business_day_id") val businessDayId: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("amount") val amount: Double,
    val reason: String,
    @SerialName("affects_profit") val affectsProfit: Boolean = true,
    @SerialName("cashier_id") val cashierId: String,
    @SerialName("occurred_at") val occurredAt: String,
)

@Serializable data class PushRevenueDeductionRequest(@SerialName("p_deduction") val deduction: PushRevenueDeductionPayload)
@Serializable data class PushRevenueDeductionResponse(val status: String, @SerialName("deduction_id") val deductionId: String)

@Serializable data class PushReversalMovementPayload(
    val id: String,
    @SerialName("product_id") val productId: String,
)

@Serializable data class PushSaleReversalPayload(
    val id: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("stall_id") val stallId: String,
    @SerialName("payout_day_id") val payoutDayId: String,
    val kind: String,
    val reason: String,
    val restock: Boolean,
    @SerialName("cash_returned") val cashReturned: Double,
    @SerialName("occurred_at") val occurredAt: String,
    val movements: List<PushReversalMovementPayload>,
)

@Serializable data class PushSaleReversalRequest(@SerialName("p_reversal") val reversal: PushSaleReversalPayload)
@Serializable data class PushSaleReversalResponse(
    val status: String,
    @SerialName("reversal_id") val reversalId: String,
    @SerialName("transaction_id") val transactionId: String,
)
