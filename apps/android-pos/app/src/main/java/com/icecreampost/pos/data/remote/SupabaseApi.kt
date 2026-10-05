package com.icecreampost.pos.data.remote

import com.icecreampost.pos.data.remote.dto.ProductDto
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.LoginResponse
import com.icecreampost.pos.data.remote.dto.ActivateDeviceRequest
import com.icecreampost.pos.data.remote.dto.ActivateDeviceResponse
import com.icecreampost.pos.data.remote.dto.PrepareReplacementRequest
import com.icecreampost.pos.data.remote.dto.PrepareReplacementResponse
import com.icecreampost.pos.data.remote.dto.RecoveredBusinessDay
import com.icecreampost.pos.data.remote.dto.RecoveredDayRequest
import com.icecreampost.pos.data.remote.dto.InventoryLedgerDto
import com.icecreampost.pos.data.remote.dto.PushTransactionPayload
import com.icecreampost.pos.data.remote.dto.PushTransactionResponse
import com.icecreampost.pos.data.remote.dto.PushTransactionRpcRequest
import com.icecreampost.pos.data.remote.dto.ProductPullRequest
import com.icecreampost.pos.data.remote.dto.PushBusinessDayRequest
import com.icecreampost.pos.data.remote.dto.PushBusinessDayResponse
import com.icecreampost.pos.data.remote.dto.ProductRecipeDto
import com.icecreampost.pos.data.remote.dto.PushInventoryEntryRequest
import com.icecreampost.pos.data.remote.dto.PushInventoryEntryResponse
import com.icecreampost.pos.data.remote.dto.PushDailyClosingRequest
import com.icecreampost.pos.data.remote.dto.PushDailyClosingResponse
import com.icecreampost.pos.data.remote.dto.PushRevenueDeductionRequest
import com.icecreampost.pos.data.remote.dto.PushRevenueDeductionResponse
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface SupabaseApi {
    @POST("rest/v1/rpc/login_pos_with_password")
    suspend fun login(@Body request: LoginRequest): List<LoginResponse>

    @POST("rest/v1/rpc/activate_pos_device")
    suspend fun activateDevice(@Body request: ActivateDeviceRequest): ActivateDeviceResponse

    @POST("rest/v1/rpc/prepare_pos_replacement")
    suspend fun prepareReplacement(@Body request: PrepareReplacementRequest): PrepareReplacementResponse

    @POST("rest/v1/rpc/get_recovered_pos_day")
    suspend fun getRecoveredDay(@Body request: RecoveredDayRequest): RecoveredBusinessDay?

    @POST("rest/v1/rpc/push_pos_transaction")
    suspend fun pushTransaction(@Body request: PushTransactionRpcRequest): PushTransactionResponse

    @POST("rest/v1/rpc/push_business_day")
    suspend fun pushBusinessDay(@Body request: PushBusinessDayRequest): PushBusinessDayResponse

    @POST("rest/v1/rpc/get_pos_products")
    suspend fun getProducts(@Body request: ProductPullRequest): List<ProductDto>

    @POST("rest/v1/rpc/get_pos_recipes")
    suspend fun getRecipes(@Body request: ProductPullRequest = ProductPullRequest(null)): List<ProductRecipeDto>

    @POST("rest/v1/rpc/push_pos_inventory_entry")
    suspend fun pushInventoryEntry(@Body request: PushInventoryEntryRequest): PushInventoryEntryResponse

    @POST("rest/v1/rpc/push_daily_store_closing")
    suspend fun pushDailyClosing(@Body request: PushDailyClosingRequest): PushDailyClosingResponse

    @POST("rest/v1/rpc/push_revenue_deduction")
    suspend fun pushRevenueDeduction(@Body request: PushRevenueDeductionRequest): PushRevenueDeductionResponse

    @GET("rest/v1/inventory_ledger")
    suspend fun getInventoryLedger(
        @Query("select") select: String = "id,stall_id,product_id,quantity_delta,movement_type,reason,reference_id,occurred_at,updated_at,deleted_at",
        @Query("updated_at") updatedAtFilter: String? = null,
    ): List<InventoryLedgerDto>
}
