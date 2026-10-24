package com.icecreampost.pos.data.remote.interceptor

import com.icecreampost.pos.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class SupabaseHeadersInterceptor(
    private val sessionTokenStore: SessionTokenStore,
    private val deviceIdentity: DeviceIdentity,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = BuildConfig.SUPABASE_ANON_KEY
        val builder = chain.request().newBuilder()
            .addHeader("apikey", apiKey)
            .addHeader("Accept-Profile", "public")
            .addHeader("X-Device-Id", deviceIdentity.id)
        if (apiKey.startsWith("sb_publishable_")) {
            // Publishable API keys are not JWTs and must not be sent as Bearer tokens.
            builder.removeHeader("Authorization")
        } else {
            // Keep the legacy anon JWT behavior for the current production project.
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        sessionTokenStore.token?.takeIf { it.isNotBlank() }?.let {
            builder.addHeader("X-Session-Token", it)
        }
        return chain.proceed(builder.build())
    }
}
