package com.icecreampost.pos.data.remote

import com.icecreampost.pos.data.local.dao.BusinessDayDao
import com.icecreampost.pos.data.local.dao.SessionDao
import com.icecreampost.pos.data.local.dao.SyncStateDao
import com.icecreampost.pos.data.remote.dto.RecoveredDayRequest
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.data.repository.SessionRepository
import com.icecreampost.pos.di.NetworkModule
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Uses the production Retrofit converter, without contacting a live backend. */
class SessionHttpContractTest {
    private val requests = mutableListOf<String>()
    private fun api(recoveryBody: String = "null", loginStatus: Int = 200, activated: Boolean = true): SupabaseApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val endpoint = request.url.pathSegments.last()
            requests.add(endpoint)
            val body = when (endpoint) {
                "login_pos_with_password" -> if (loginStatus == 200) """[{
                    "session_token":"test-token","user_id":"cashier","stall_id":"stall",
                    "display_name":"Test Cashier","role":"cashier","expires_at":"2099-01-01T00:00:00Z",
                    "device_id":${if (activated) "\"device\"" else "null"},"is_activated":$activated
                }]""" else """{"code":"28000","message":"INVALID_CREDENTIALS"}"""
                "get_resumable_pos_day" -> recoveryBody
                else -> error("Unexpected test endpoint: $endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (endpoint == "login_pos_with_password") loginStatus else 200)
                .message("Test response").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://contract-test.invalid/").client(client)
            .addConverterFactory(NetworkModule.provideJson().asConverterFactory("application/json".toMediaType()))
            .build().create(SupabaseApi::class.java)
    }

    private val sessions = mockk<SessionDao>(relaxed = true)
    private val days = mockk<BusinessDayDao>(relaxed = true)
    private val states = mockk<SyncStateDao>(relaxed = true)
    private val tokens = SessionTokenStore()
    private fun repository(api: SupabaseApi): SessionRepository {
        coEvery { sessions.getCurrent() } returns null
        coEvery { states.find("device-stall") } returns null
        coEvery { states.findPersistedStallIds() } returns emptyList()
        return SessionRepository(sessions, api, tokens, mockk<DeviceIdentity>(), days, states)
    }

    @Test fun `registered cashier signs in when resumable day endpoint returns JSON null`() = runTest {
        val session = repository(api()).signIn("MAIN-001", "RBB567", "test-password")
        assertTrue(session.isActivated)
        assertEquals("device", session.deviceId)
        coVerify(exactly = 1) { sessions.save(session) }
        coVerify(exactly = 0) { days.upsert(any()) }
        assertEquals(listOf("login_pos_with_password", "get_resumable_pos_day"), requests)
    }

    @Test fun `resumable day endpoint preserves server-known operating day totals`() = runTest {
        val recovery = api("""{
            "id":"day","business_date":"2026-10-12","opened_at":"2026-10-12T00:00:00Z",
            "cashier_id":"cashier","known_sales":90,"known_orders":2,"known_deductions":10,
            "known_profit_deductions":3,"known_cogs":15,"known_waste":2
        }""").getResumableDay(RecoveredDayRequest("device")).day
        assertEquals("day", recovery?.id)
        assertEquals(2, recovery?.knownOrders)
        assertEquals(90.0, recovery?.knownSales)
    }

    @Test fun `a cashier without activation does not request recovery`() = runTest {
        val session = repository(api(activated = false)).signIn("MAIN-001", "RBB567", "test-password")
        assertFalse(session.isActivated)
        assertEquals(listOf("login_pos_with_password"), requests)
    }

    @Test fun `incorrect credentials produce an actionable sign in error`() = runTest {
        val failure = runCatching { repository(api(loginStatus = 401)).signIn("MAIN-001", "RBB567", "wrong") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("employee ID"))
        coVerify(exactly = 0) { sessions.save(any()) }
        assertNull(tokens.token)
    }

    @Test fun `invalid recovery JSON fails without saving a session or retaining the new token`() = runTest {
        tokens.token = "previous-token"
        val failure = runCatching { repository(api("{}")).signIn("MAIN-001", "RBB567", "test-password") }.exceptionOrNull()
        assertNotNull(failure)
        coVerify(exactly = 0) { sessions.save(any()) }
        coVerify(exactly = 0) { states.upsert(any()) }
        assertEquals("previous-token", tokens.token)
    }
}
