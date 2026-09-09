package com.icecreampost.pos.data.remote

import com.icecreampost.pos.data.remote.dto.ProductDto
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.LoginResponse
import com.icecreampost.pos.data.remote.dto.ActivateDeviceRequest
import com.icecreampost.pos.data.remote.dto.ActivateDeviceResponse
import com.icecreampost.pos.data.remote.dto.InventoryLedgerDto
import com.icecreampost.pos.data.remote.dto.PushTransactionPayload
import com.icecreampost.pos.data.remote.dto.PushTransactionResponse
import com.icecreampost.pos.data.remote.dto.PushTransactionRpcRequest
import com.icecreampost.pos.data.remote.dto.ProductPullRequest
import com.icecreampost.pos.data.remote.dto.PushBusinessDayRequest
import com.icecreampost.pos.data.remote.dto.PushBusinessDayResponse
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface SupabaseApi {
    @POST("rest/v1/rpc/login_pos_with_password")
    suspend fun login(@Body request: LoginRequest): List<LoginResponse>

    @POST("rest/v1/rpc/activate_pos_device")
    suspend fun activateDevice(@Body request: ActivateDeviceRequest): ActivateDeviceResponse

    @POST("rest/v1/rpc/push_pos_transaction")
    suspend fun pushTransaction(@Body request: PushTransactionRpcRequest): PushTransactionResponse

    @POST("rest/v1/rpc/push_business_day")
    suspend fun pushBusinessDay(@Body request: PushBusinessDayRequest): PushBusinessDayResponse

    @POST("rest/v1/rpc/get_pos_products")
    suspend fun getProducts(@Body request: ProductPullRequest): List<ProductDto>

    @GET("rest/v1/inventory_ledger")
    suspend fun getInventoryLedger(
        @Query("select") select: String = "id,stall_id,product_id,quantity_delta,movement_type,reason,reference_id,occurred_at,updated_at,deleted_at",
        @Query("updated_at") updatedAtFilter: String? = null,
    ): List<InventoryLedgerDto>
}
