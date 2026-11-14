package com.icecreampost.pos.data.remote.interceptor

import com.icecreampost.pos.BuildConfig
import com.icecreampost.pos.security.PosDeviceKey
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class SupabaseHeadersInterceptor(
    private val sessionTokenStore: SessionTokenStore,
    private val deviceIdentity: DeviceIdentity,
    private val deviceKey: PosDeviceKey,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val apiKey = BuildConfig.SUPABASE_ANON_KEY
        val request = chain.request()
        val path = request.url.encodedPath.removePrefix("/rest/v1")
        val signedUrl = request.url.encodedQuery?.let { "$path?$it" } ?: path
        val token = if (path == "/rpc/login_pos_with_password" || path == "/rpc/login_pos_with_fingerprint") null else
            request.header("X-Session-Token") ?: sessionTokenStore.token
        val builder = request.newBuilder()
            .header("apikey", apiKey)
            .removeHeader("X-Session-Token")
        if (apiKey.startsWith("sb_publishable_")) {
            builder.removeHeader("Authorization")
        } else {
            builder.header("Authorization", "Bearer $apiKey")
        }
        token?.let { builder.header("X-Session-Token", it) }
        // Revocation can only disable the supplied bearer, including after key loss.
        if (path == "/rpc/revoke_app_session") return chain.proceed(builder.build())

        val body = Buffer().apply { request.body?.writeTo(this) }.readByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val proof = try {
            deviceKey.sign(listOf("coolerz-pos-proof-v1", deviceIdentity.id, request.method, signedUrl,
                timestamp, nonce, token?.let { digest(it.toByteArray(Charsets.UTF_8)) }.orEmpty(), digest(body))
                .joinToString("\n"))
        } catch (error: Exception) {
            throw IOException("Unable to use this POS device key. Try again after unlocking the phone.", error)
        }
        builder
            .url(request.url.newBuilder().encodedPath("/functions/v1/pos-gateway$path").build())
            .header("X-Device-Id", deviceIdentity.id)
            .header("X-Pos-Public-Key", proof.publicKey)
            .header("X-Pos-Signature", proof.signature)
            .header("X-Pos-Timestamp", timestamp)
            .header("X-Pos-Nonce", nonce)
        return chain.proceed(builder.build())
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}
