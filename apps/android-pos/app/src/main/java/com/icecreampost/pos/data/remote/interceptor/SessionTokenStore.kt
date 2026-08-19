package com.icecreampost.pos.data.remote.interceptor

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionTokenStore @Inject constructor() {
    @Volatile
    var token: String? = null
}
