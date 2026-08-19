package com.icecreampost.pos.di

import com.icecreampost.pos.BuildConfig
import com.icecreampost.pos.data.remote.SupabaseApi
import com.icecreampost.pos.data.remote.interceptor.SupabaseHeadersInterceptor
import com.icecreampost.pos.data.remote.interceptor.SessionTokenStore
import com.icecreampost.pos.data.remote.interceptor.DeviceIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Provides
    @Singleton
    fun provideOkHttp(sessionTokenStore: SessionTokenStore, deviceIdentity: DeviceIdentity): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(SupabaseHeadersInterceptor(sessionTokenStore, deviceIdentity))
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    @Provides
    @Singleton
    fun provideSupabaseApi(json: Json, client: OkHttpClient): SupabaseApi {
        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val normalizedUrl = if (baseUrl.isBlank()) "https://invalid.local/" else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SupabaseApi::class.java)
    }
}
