package com.icecreampost.pos.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncTrigger @Inject constructor(@ApplicationContext private val context: Context) {
    fun triggerNow() = SyncScheduler.enqueueNow(context)
}
