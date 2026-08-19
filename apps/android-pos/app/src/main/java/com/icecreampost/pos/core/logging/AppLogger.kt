package com.icecreampost.pos.core.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor() {
    fun info(message: String) = Log.i(TAG, message)
    fun error(message: String, throwable: Throwable? = null) = Log.e(TAG, message, throwable)

    private companion object {
        const val TAG = "CoolerzPOS"
    }
}
