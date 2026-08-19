package com.icecreampost.pos.data.remote.interceptor

import com.icecreampost.pos.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class SupabaseHeadersInterceptor(
    private val sessionTokenStore: SessionTokenStore,
    private val deviceIdentity: DeviceIdentity,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .addHeader("Accept-Profile", "public")
            .addHeader("X-Device-Id", deviceIdentity.id)
        sessionTokenStore.token?.takeIf { it.isNotBlank() }?.let {
            builder.addHeader("X-Session-Token", it)
        }
        return chain.proceed(builder.build())
    }
}
