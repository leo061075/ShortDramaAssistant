package com.example.shortdramarecorder.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.shortdramarecorder.ocr.OcrMonitorEngine
import com.example.shortdramarecorder.state.AdStateCoordinator
import com.example.shortdramarecorder.state.ContinuePromptDetector
import com.example.shortdramarecorder.state.SwipeDiagnostics
import com.example.shortdramarecorder.state.PlaybackSkipSession
import com.example.shortdramarecorder.state.SkipRuntimeState
import java.util.ArrayDeque
import java.util.regex.Pattern

class DramaAccessibilityService :
    AccessibilityService() {

    companion object {
        @Volatile
        private var serviceRef:
            DramaAccessibilityService? = null

        @Volatile
        private var lastUsefulSignalAt:
            Long = 0L

        @Volatile
        private var screenshotInFlight:
            Boolean = false

        @Volatile
        private var lastScreenshotAt:
            Long = 0L

        @Volatile
        private var screenshotInFlightSince:
            Long = 0L

        @Volatile
        private var screenshotRequestId:
            Long = 0L

        private const val SCREENSHOT_STUCK_TIMEOUT_MS = 2800L

        @Volatile
        private var lastForegroundPackage:
            String? = null

        @Volatile
        private var ownAppVisible:
            Boolean = false

        fun setOwnAppVisible(visible: Boolean) {
            ownAppVisible = visible
        }

        fun isOwnAppForeground(): Boolean {
            if (ownAppVisible) return true
            val service = serviceRef ?: return false
            return lastForegroundPackage == service.packageName
        }

        fun foregroundPackageName(): String? =
            lastForegroundPackage

        fun isConnected(): Boolean =
            serviceRef != null

        fun lastUsefulSignalElapsed(): Long =
            lastUsefulSignalAt

        fun startPlaybackSkipMode(): Boolean {
            val service =
                serviceRef ?: return false

            PlaybackSkipSession.start()
            service.startPlaybackOcr()
            return true
        }

        fun stopPlaybackSkipMode() {
            serviceRef?.stopPlaybackOcr()
            PlaybackSkipSession.stop()
        }

        fun requestSwipeUpByOcr(): Boolean {
            val service = serviceRef
            if (service == null) {
                SwipeDiagnostics.report("无障碍服务已断开，未发起上滑")
                AdStateCoordinator.onSwipeDispatchFailed()
                return false
            }
            service.swipeUp()
            return true
        }

        @Synchronized
        private fun beginScreenshotRequest(now: Long): Long? {
            if (screenshotInFlight) {
                return null
            }
            screenshotInFlight = true
            screenshotInFlightSince = now
            lastScreenshotAt = now
            screenshotRequestId++
            return screenshotRequestId
        }

        @Synchronized
        private fun finishScreenshotRequest(requestId: Long): Boolean {
            if (!screenshotInFlight || screenshotRequestId != requestId) {
                return false
            }
            screenshotInFlight = false
            screenshotInFlightSince = 0L
            return true
        }

        @Synchronized
        private fun isCurrentScreenshotRequest(requestId: Long): Boolean =
            screenshotInFlight && screenshotRequestId == requestId

        fun requestScreenshotForOcr(
            callback:
                (
                    bitmap: Bitmap?,
                    error: String?
                ) -> Unit
        ) {
            val service =
                serviceRef

            if (service == null) {
                callback(
                    null,
                    "无障碍服务未连接"
                )
                return
            }

            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.R
            ) {
                callback(
                    null,
                    "OCR截图需要 Android 11+"
                )
                return
            }

            val now =
                SystemClock.elapsedRealtime()

            if (now - lastScreenshotAt < 220L) {
                callback(null, "OCR截图稍候")
                return
            }

            val requestId = beginScreenshotRequest(now)
            if (requestId == null) {
                callback(null, "OCR截图处理中")
                return
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (isCurrentScreenshotRequest(requestId)) {
                    if (finishScreenshotRequest(requestId)) {
                        SwipeDiagnostics.report("OCR截图超时，已自动恢复")
                        callback(null, "OCR截图超时，已自动恢复")
                    }
                }
            }, SCREENSHOT_STUCK_TIMEOUT_MS)

            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object :
                        TakeScreenshotCallback {

                        override fun onSuccess(
                            screenshot:
                                ScreenshotResult
                        ) {
                            if (!finishScreenshotRequest(requestId)) {
                                runCatching { screenshot.hardwareBuffer.close() }
                                return
                            }

                            val hardwareBuffer =
                                screenshot
                                    .hardwareBuffer

                            try {
                                val hardwareBitmap =
                                    Bitmap.wrapHardwareBuffer(
                                        hardwareBuffer,
                                        screenshot.colorSpace
                                    )

                                val softwareBitmap =
                                    hardwareBitmap
                                        ?.copy(
                                            Bitmap.Config.ARGB_8888,
                                            false
                                        )

                                callback(
                                    softwareBitmap,
                                    if (
                                        softwareBitmap == null
                                    ) {
                                        "截图转换失败"
                                    } else {
                                        null
                                    }
                                )
                            } catch (
                                t: Throwable
                            ) {
                                callback(
                                    null,
                                    t.message
                                        ?: "截图转换异常"
                                )
                            } finally {
                                runCatching {
                                    hardwareBuffer.close()
                                }
                            }
                        }

                        override fun onFailure(
                            errorCode: Int
                        ) {
                            if (!finishScreenshotRequest(requestId)) {
                                return
                            }

                            callback(
                                null,
                                "截图失败($errorCode)"
                            )
                        }
                    }
                )
            } catch (
                t: Throwable
            ) {
                if (!finishScreenshotRequest(requestId)) {
                    return
                }

                callback(
                    null,
                    "截图请求异常：" +
                        (
                            t.message
                                ?: t.javaClass.simpleName
                            ).take(24)
                )
            }
        }
    }

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var playbackOcrEngine:
        OcrMonitorEngine? = null

    private val episodePattern =
        Pattern.compile(
            "第\\s*(\\d{1,4})\\s*集"
        )

    private val totalPattern =
        Pattern.compile(
            "(\\d{1,4})\\s*集全"
        )

    private val unlockAdPattern =
        Pattern.compile(
            """(?:\d{1,3}|[一二三四五六七八九十百]+)\s*秒后可解锁付费集"""
        )

    private val assistAdKeywords =
        listOf(
            "退出短剧",
            "免费广告",
            "点击进入直播间"
        )

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceRef = this

        if (
            PlaybackSkipSession.isActive
        ) {
            startPlaybackOcr()
        }
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        val eventPackage =
            event?.packageName?.toString()

        lastForegroundPackage =
            eventPackage

        serviceRef = this

        if (
            eventPackage == packageName ||
            ownAppVisible
        ) {
            return
        }

        if (
            !SkipRuntimeState
                .isRecognitionActive()
        ) {
            return
        }

        try {
            val root =
                rootInActiveWindow
                    ?: return

            val texts =
                collectTexts(root)

            if (texts.isEmpty()) return

            val joined =
                texts.joinToString(" | ")

            handleRecognizedText(joined)
        } catch (t: Throwable) {
            SkipRuntimeState.ocrStatus =
                "无障碍识别异常：" +
                    (
                        t.message
                            ?: t.javaClass.simpleName
                        ).take(20)
        }
    }

    private fun handleRecognizedText(
        text: String
    ) {
        val episodeMatcher =
            episodePattern.matcher(text)

        val episode =
            if (episodeMatcher.find()) {
                episodeMatcher
                    .group(1)
                    ?.toIntOrNull()
            } else {
                null
            }

        val totalMatcher =
            totalPattern.matcher(text)

        val totalEpisodes =
            if (totalMatcher.find()) {
                totalMatcher
                    .group(1)
                    ?.toIntOrNull()
            } else {
                null
            }

        val episodeVisible =
            episode != null

        val totalVisible =
            totalEpisodes != null

        val shortDramaVisible =
            text.contains("短剧")

        val definiteDramaPage =
            episodeVisible &&
                shortDramaVisible &&
                totalVisible

        if (episode != null) {
            SkipRuntimeState
                .updateEpisode(episode)
        }

        if (totalEpisodes != null) {
            SkipRuntimeState.totalEpisodes =
                totalEpisodes
        }

        updateTitle(text)

        val hasContinuePrompt =
            hasContinuePrompt(text)

        val unlockAdDetected =
            unlockAdPattern.matcher(text).find() ||
                text.contains("秒后可解锁付费集")

        val assistAdDetected =
            assistAdKeywords.count {
                text.contains(it)
            } >= 2

        lastUsefulSignalAt =
            SystemClock.elapsedRealtime()

        if (hasContinuePrompt &&
            (PlaybackSkipSession.isActive || !definiteDramaPage)
        ) {
            SkipRuntimeState.boostOcr(4500L)
            if (AdStateCoordinator.requestContinueSwipe(fromFooterOcr = false)) {
                SwipeDiagnostics.report("无障碍命中继续观看，准备上滑")
                swipeUp()
            }
            return
        }

        if (definiteDramaPage) {
            AdStateCoordinator.reportNormal(
                SkipRuntimeState.currentEpisode, true
            )
            return
        }

        if (
            episodeVisible ||
            (
                shortDramaVisible &&
                totalVisible
            )
        ) {
            AdStateCoordinator.reportNormal(
                SkipRuntimeState.currentEpisode,
                false
            )
            return
        }

        if (
            unlockAdDetected ||
            assistAdDetected
        ) {
            SkipRuntimeState.boostOcr(3500L)
            AdStateCoordinator.reportAd(unlockAdDetected)
        }
    }

    private fun hasContinuePrompt(text: String): Boolean =
        ContinuePromptDetector.matches(text)

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopPlaybackOcr()

        if (serviceRef === this) {
            serviceRef = null
        }

        super.onDestroy()
    }

    private fun startPlaybackOcr() {
        if (
            playbackOcrEngine != null
        ) {
            return
        }

        playbackOcrEngine =
            OcrMonitorEngine(
                applicationContext
            ).also {
                it.start()
            }
    }

    private fun stopPlaybackOcr() {
        runCatching {
            playbackOcrEngine?.stop()
        }

        playbackOcrEngine = null
    }

    private fun updateTitle(
        text: String
    ) {
        val marker = "短剧"
        val index =
            text.indexOf(marker)

        if (index < 0) return

        val snippet =
            text.substring(index)
                .take(60)

        val cleaned =
            snippet
                .replace("短剧", "")
                .replace("·", "")
                .substringBefore("集全")
                .trim(
                    ' ',
                    '|',
                    '·',
                    '-',
                    '…'
                )

        if (
            cleaned.length in 1..30
        ) {
            SkipRuntimeState.title =
                cleaned
        }
    }

    private fun collectTexts(
        root: AccessibilityNodeInfo
    ): List<String> {
        val result =
            ArrayList<String>()

        val queue =
            ArrayDeque<
                AccessibilityNodeInfo
            >()

        queue.add(root)

        var visited = 0

        while (
            queue.isNotEmpty() &&
            visited < 350
        ) {
            val node =
                queue.removeFirst()

            visited++

            node.text
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.let(result::add)

            node.contentDescription
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?.let(result::add)

            for (
                index
                in 0 until
                    node.childCount
            ) {
                node.getChild(index)
                    ?.let(queue::add)
            }
        }

        return result.distinct()
    }

    private fun swipeUp() {
        handler.post {
            if (!SkipRuntimeState.isRecognitionActive()) {
                SwipeDiagnostics.report("识别会话已结束，取消上滑")
                AdStateCoordinator.onSwipeDispatchFailed()
                return@post
            }
            val metrics = resources.displayMetrics
            val x = metrics.widthPixels * 0.5f
            val path = Path().apply {
                moveTo(x, metrics.heightPixels * 0.84f)
                lineTo(x, metrics.heightPixels * 0.23f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 380L))
                .build()
            try {
                val submitted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            AdStateCoordinator.onSwipeGestureCompleted()
                            AdStateCoordinator.forceExit()
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            SwipeDiagnostics.report("系统取消上滑手势，稍后重试")
                            AdStateCoordinator.onSwipeDispatchFailed()
                        }
                    },
                    handler
                )
                if (submitted) {
                    SwipeDiagnostics.report("已提交上滑手势，等待系统结果")
                } else {
                    SwipeDiagnostics.report("系统拒绝提交手势，稍后重试")
                    AdStateCoordinator.onSwipeDispatchFailed()
                }
            } catch (error: Throwable) {
                SwipeDiagnostics.report("上滑异常：${error.javaClass.simpleName}")
                AdStateCoordinator.onSwipeDispatchFailed()
            }
        }
    }
}
