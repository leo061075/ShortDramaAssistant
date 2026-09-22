package com.example.shortdramarecorder.state

import android.os.SystemClock

/**
 * 跳广告模式运行状态。
 *
 * 保存广告识别、集数、OCR 和页面状态。
 */
object SkipRuntimeState {
    @Volatile var isAd = false
    @Volatile var title: String? = null
    @Volatile var currentEpisode: Int? = null
    @Volatile var totalEpisodes: Int? = null
    @Volatile var lastError: String? = null
    @Volatile var ocrStatus: String = "OCR待机"

    @Volatile
    private var ocrBoostUntilElapsed: Long = 0L

    fun isRecognitionActive(): Boolean =
        PlaybackSkipSession.isActive

    fun boostOcr(durationMs: Long = 4000L) {
        val until = SystemClock.elapsedRealtime() + durationMs
        if (until > ocrBoostUntilElapsed) {
            ocrBoostUntilElapsed = until
        }
    }

    fun isOcrBoostActive(): Boolean =
        SystemClock.elapsedRealtime() < ocrBoostUntilElapsed

    fun clearOcrBoost() {
        ocrBoostUntilElapsed = 0L
    }

    @Synchronized
    fun beginAd() {
        if (!isRecognitionActive() || isAd) return
        isAd = true
    }

    @Synchronized
    fun endAd() {
        isAd = false
    }

    @Synchronized
    fun forceExitAd() {
        isAd = false
    }

    @Synchronized
    fun updateEpisode(candidate: Int?) {
        val value = candidate ?: return
        if (value <= 0) return

        val current = currentEpisode
        if (current == null || value >= current) {
            currentEpisode = value
        }
    }

    @Synchronized
    fun resetForSkipMode() {
        isAd = false
        title = null
        currentEpisode = null
        totalEpisodes = null
        lastError = null
        ocrStatus = "OCR待机"
        clearOcrBoost()
        AdStateCoordinator.reset()
    }

    fun displayStatus(): String =
        PlaybackSkipSession.displayStatus()
}
