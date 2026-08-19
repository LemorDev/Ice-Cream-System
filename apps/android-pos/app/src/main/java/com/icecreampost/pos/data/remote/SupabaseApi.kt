package com.icecreampost.pos.data.remote

import com.icecreampost.pos.data.remote.dto.ProductDto
import com.icecreampost.pos.data.remote.dto.LoginRequest
import com.icecreampost.pos.data.remote.dto.LoginResponse
import com.icecreampost.pos.data.remote.dto.InventoryLedgerDto
import com.icecreampost.pos.data.remote.dto.PushTransactionPayload
import com.icecreampost.pos.data.remote.dto.PushTransactionResponse
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface SupabaseApi {
    @POST("rest/v1/rpc/login_with_password")
    suspend fun login(@Body request: LoginRequest): List<LoginResponse>

    @POST("rest/v1/rpc/push_pos_transaction")
    suspend fun pushTransaction(@Body payload: PushTransactionPayload): PushTransactionResponse

    @GET("rest/v1/products")
    suspend fun getProducts(
        @Query("select") select: String = "id,stall_id,category_id,sku,name,unit,sale_price,cost_price,low_stock_threshold,pack_size,conversion_rate,is_sellable,updated_at,deleted_at",
        @Query("updated_at") updatedAtFilter: String? = null,
    ): List<ProductDto>

    @GET("rest/v1/inventory_ledger")
    suspend fun getInventoryLedger(
        @Query("select") select: String = "id,stall_id,product_id,quantity_delta,movement_type,reason,reference_id,occurred_at,updated_at,deleted_at",
        @Query("updated_at") updatedAtFilter: String? = null,
    ): List<InventoryLedgerDto>
}
