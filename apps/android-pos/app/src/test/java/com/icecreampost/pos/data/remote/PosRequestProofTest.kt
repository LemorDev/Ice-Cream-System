package com.icecreampost.pos.data.remote

import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.data.remote.interceptor.SupabaseHeadersInterceptor
import com.icecreampost.pos.security.DeviceProof
import com.icecreampost.pos.security.PosDeviceKey
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PosRequestProofTest {
    @Test
    fun `POS request proof binds body path nonce and bearer while logout uses only its explicit bearer`() {
        val hardware = "1234567890abcdef"
        val tokens = SessionTokenStore().apply { token = "active-test-token" }
        val identity = mockk<DeviceIdentity> { every { id } returns hardware }
        val messages = mutableListOf<String>()
        val key = mockk<PosDeviceKey> {
            every { sign(capture(messages)) } returns DeviceProof("public-key", "signature")
        }
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(SupabaseHeadersInterceptor(tokens, identity, key))
            .addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build()
        val payload = """{"p_transaction":{"total_amount":50}}"""
        fun send(name: String, explicitToken: String? = null) {
            val request = Request.Builder().url("https://fixture.invalid/rest/v1/rpc/$name")
                .post(payload.toRequestBody())
            explicitToken?.let { request.header("X-Session-Token", it) }
            client.newCall(request.build()).execute().close()
        }
        send("push_pos_transaction_v2")
        send("login_pos_with_password")
        send("revoke_app_session", "pending-logout-token")
        val proof = messages.first().lines()
        assertEquals(listOf("coolerz-pos-proof-v1", hardware, "POST", "/rpc/push_pos_transaction_v2"), proof.take(4))
        assertEquals(requests[0].header("X-Pos-Timestamp"), proof[4])
        assertEquals(requests[0].header("X-Pos-Nonce"), proof[5])
        assertEquals(hash("active-test-token"), proof[6])
        assertEquals(hash(payload), proof[7])
        assertEquals("/functions/v1/pos-gateway/rpc/push_pos_transaction_v2", requests[0].url.encodedPath)
        assertNotEquals(proof[5], requests[1].header("X-Pos-Nonce"))
        assertEquals("", messages[1].lines()[6])
        assertNull(requests[1].header("X-Session-Token"))
        assertEquals(2, messages.size)
        assertEquals("/rest/v1/rpc/revoke_app_session", requests[2].url.encodedPath)
        assertEquals("pending-logout-token", requests[2].header("X-Session-Token"))
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
