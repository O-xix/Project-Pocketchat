package com.pocketchat.app

import android.app.Application

/**
 * FR-021: installs a crash handler as early as possible (a plain
 * `ComponentActivity` can't catch a crash that happens before/outside any
 * activity) so [DebugLog] actually has something to export. Chains to the
 * platform's own default handler afterward — this only observes, it
 * doesn't suppress the crash or its normal system dialog/process death.
 */
class PocketChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLog.logCrash(this, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
