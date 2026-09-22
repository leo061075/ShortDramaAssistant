package com.example.shortdramarecorder.state

import android.os.SystemClock

/** Visible on the skip-mode page for debugging recognition versus gesture delivery. */
object SwipeDiagnostics {
    @Volatile var message = "等待识别底部提示"
    @Volatile var lastEventAt = 0L

    fun report(value: String) {
        message = value
        lastEventAt = SystemClock.elapsedRealtime()
    }
}
