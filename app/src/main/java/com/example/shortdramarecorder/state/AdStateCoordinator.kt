package com.example.shortdramarecorder.state

import android.os.SystemClock

object AdStateCoordinator {

    private const val NORMAL_CONFIRM_HITS = 2
    private const val NORMAL_HIT_WINDOW_MS = 2500L
    private const val CONTINUE_SWIPE_REARM_MS = 2800L
    private const val PROMPT_ABSENCE_CONFIRM_MS = 1000L

    private var episodeAtAdStart: Int? = null
    private var normalHits = 0
    private var lastNormalHitAt = 0L
    private var continueSwipeLatched = false
    private var swipeRetryPending = false
    private var lastContinueSwipeAt = 0L
    private var gestureCompletedAt = 0L
    private var retryCount = 0
    private var promptHitsAfterCompletion = 0
    private var firstPostCompletionPromptAt = 0L
    private var footerAbsentAfterCompletion = false
    private var postGestureAbsentHits = 0
    private var postGestureAbsentSince = 0L
    private var normalPageAfterSwipe = false
    private var promptAbsentSince = 0L
    private var lastFooterObservedAt = 0L
    private var lastPromptObservedAt = 0L
    private var absentFooterHits = 0

    private fun resetAdTracking() {
        episodeAtAdStart = null
        normalHits = 0
        lastNormalHitAt = 0L
    }

    @Synchronized
    fun reset() {
        resetAdTracking()
        continueSwipeLatched = false
        swipeRetryPending = false
        gestureCompletedAt = 0L
        retryCount = 0
        promptHitsAfterCompletion = 0
        firstPostCompletionPromptAt = 0L
        footerAbsentAfterCompletion = false
        postGestureAbsentHits = 0
        postGestureAbsentSince = 0L
        lastContinueSwipeAt = 0L
        normalPageAfterSwipe = false
        promptAbsentSince = 0L
        lastFooterObservedAt = 0L
        lastPromptObservedAt = 0L
        absentFooterHits = 0
    }

    @Synchronized
    fun reportAd(strongEvidence: Boolean = false) {
        if (!SkipRuntimeState.isRecognitionActive()) return

        if (!SkipRuntimeState.isAd) {
            episodeAtAdStart =
                SkipRuntimeState.currentEpisode

            SkipRuntimeState.beginAd()
        }

        SkipRuntimeState.boostOcr(3500L)

        normalHits = 0
        lastNormalHitAt = 0L
    }

    @Synchronized
    fun reportNormal(
        episode: Int?,
        definiteDramaPage: Boolean
    ) {
        if (!SkipRuntimeState.isRecognitionActive()) return

        val now =
            SystemClock.elapsedRealtime()

        if (
            lastNormalHitAt == 0L ||
            now - lastNormalHitAt >
                NORMAL_HIT_WINDOW_MS
        ) {
            normalHits = 1
        } else {
            normalHits++
        }

        lastNormalHitAt = now

        if (!SkipRuntimeState.isAd) {
            if (definiteDramaPage && continueSwipeLatched) {
                normalPageAfterSwipe = true
            }
            return
        }

        if (definiteDramaPage) {
            if (continueSwipeLatched) normalPageAfterSwipe = true
            SkipRuntimeState.endAd()
            SkipRuntimeState.boostOcr(2500L)
            resetAdTracking()
            return
        }

        val startEpisode =
            episodeAtAdStart

        if (
            episode != null &&
            startEpisode != null &&
            episode > startEpisode
        ) {
            SkipRuntimeState.endAd()
            SkipRuntimeState.boostOcr(2500L)
            resetAdTracking()
            return
        }

        if (normalHits >= NORMAL_CONFIRM_HITS) {
            SkipRuntimeState.endAd()
            SkipRuntimeState.boostOcr(2000L)
            resetAdTracking()
        }
    }

    private fun rearmIfOldPromptGone(now: Long) {
        if (!PlaybackSkipSession.isActive || !continueSwipeLatched ||
            swipeRetryPending || now - lastContinueSwipeAt < 3500L
        ) return
        if (absentFooterHits < 3 || promptAbsentSince <= lastContinueSwipeAt ||
            now - promptAbsentSince < 1800L ||
            now - lastFooterObservedAt > 2500L
        ) return
        continueSwipeLatched = false
        gestureCompletedAt = 0L
        retryCount = 0
        promptHitsAfterCompletion = 0
        firstPostCompletionPromptAt = 0L
        footerAbsentAfterCompletion = false
        postGestureAbsentHits = 0
        postGestureAbsentSince = 0L
        swipeRetryPending = false
        promptAbsentSince = 0L
        absentFooterHits = 0
        SwipeDiagnostics.report("旧继续提示已连续消失：下一广告已解锁上滑")
    }

    @Synchronized
    fun requestContinueSwipe(fromFooterOcr: Boolean = false): Boolean {
        if (!SkipRuntimeState.isRecognitionActive()) return false

        rearmIfOldPromptGone(SystemClock.elapsedRealtime())
        if (continueSwipeLatched) {
            if (!fromFooterOcr) return false
            val now = SystemClock.elapsedRealtime()
            if (!PlaybackSkipSession.isActive || retryCount >= 1 ||
                now - lastContinueSwipeAt < 2200L
            ) return false

            val stillSamePrompt =
                !footerAbsentAfterCompletion &&
                    promptHitsAfterCompletion >= 1 &&
                    firstPostCompletionPromptAt > lastContinueSwipeAt &&
                    now - lastPromptObservedAt <= 1200L
            if (!stillSamePrompt) return false

            val successfulButUnchanged =
                gestureCompletedAt > 0L &&
                    now - gestureCompletedAt >= 1600L
            val dispatchFailed = swipeRetryPending &&
                gestureCompletedAt == 0L
            if (!successfulButUnchanged && !dispatchFailed) return false

            retryCount = 1
            swipeRetryPending = false
            gestureCompletedAt = 0L
            promptHitsAfterCompletion = 0
            firstPostCompletionPromptAt = 0L
            lastContinueSwipeAt = now
            SwipeDiagnostics.report("页面仍停留继续提示，正在进行唯一一次补滑")
            return true
        }

        if (!SkipRuntimeState.isAd) {
            episodeAtAdStart = SkipRuntimeState.currentEpisode
            SkipRuntimeState.beginAd()
        }

        if (!SkipRuntimeState.isAd) return false

        continueSwipeLatched = true
        swipeRetryPending = false
        retryCount = 0
        gestureCompletedAt = 0L
        promptHitsAfterCompletion = 0
        firstPostCompletionPromptAt = 0L
        footerAbsentAfterCompletion = false
        postGestureAbsentHits = 0
        postGestureAbsentSince = 0L
        lastContinueSwipeAt = SystemClock.elapsedRealtime()
        promptAbsentSince = 0L
        absentFooterHits = 0
        return true
    }

    @Synchronized
    fun observeFooterPrompt(present: Boolean) {
        if (!SkipRuntimeState.isRecognitionActive()) return

        val now = SystemClock.elapsedRealtime()
        lastFooterObservedAt = now

        if (present) {
            lastPromptObservedAt = now
            promptAbsentSince = 0L
            absentFooterHits = 0
            postGestureAbsentHits = 0
            postGestureAbsentSince = 0L

            if (
                continueSwipeLatched &&
                (gestureCompletedAt > 0L || swipeRetryPending) &&
                now > lastContinueSwipeAt &&
                !footerAbsentAfterCompletion
            ) {
                if (promptHitsAfterCompletion == 0) {
                    firstPostCompletionPromptAt = now
                }
                promptHitsAfterCompletion++
            }

            rearmIfOldPromptGone(now)
            return
        }

        if (
            continueSwipeLatched &&
            !swipeRetryPending &&
            now - lastContinueSwipeAt >= 900L
        ) {
            if (promptAbsentSince == 0L) {
                promptAbsentSince = now
            }
            absentFooterHits++

            if (gestureCompletedAt > 0L) {
                if (postGestureAbsentSince == 0L) {
                    postGestureAbsentSince = now
                }
                postGestureAbsentHits++

                if (
                    postGestureAbsentHits >= 3 &&
                    now - postGestureAbsentSince >= 1200L
                ) {
                    footerAbsentAfterCompletion = true
                    promptHitsAfterCompletion = 0
                    firstPostCompletionPromptAt = 0L
                    SwipeDiagnostics.report("继续提示已稳定消失，确认页面已切换")
                }
            }

            rearmIfOldPromptGone(now)
        }
    }

    @Synchronized
    fun onSwipeGestureCompleted() {
        if (continueSwipeLatched) {
            gestureCompletedAt = SystemClock.elapsedRealtime()
            promptHitsAfterCompletion = 0
            firstPostCompletionPromptAt = 0L
            footerAbsentAfterCompletion = false
            postGestureAbsentHits = 0
            postGestureAbsentSince = 0L
            SwipeDiagnostics.report(
                if (retryCount == 0) "手势完成，确认页面是否已切换"
                else "补滑完成，确认页面是否已切换（不再重试）"
            )
        }
    }

    @Synchronized
    fun onSwipeDispatchFailed() {
        if (continueSwipeLatched) {
            lastContinueSwipeAt = SystemClock.elapsedRealtime()
            gestureCompletedAt = 0L
            promptHitsAfterCompletion = 0
            firstPostCompletionPromptAt = 0L
            postGestureAbsentHits = 0
            postGestureAbsentSince = 0L
            swipeRetryPending = retryCount == 0
        }
    }

    @Synchronized
    fun forceExit() {
        SkipRuntimeState.forceExitAd()
        resetAdTracking()
    }
}
