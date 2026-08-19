package com.icecreampost.pos.data.remote.interceptor

import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceIdentity @Inject constructor(@ApplicationContext context: Context) {
    val id: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "unknown-android-device"
}
