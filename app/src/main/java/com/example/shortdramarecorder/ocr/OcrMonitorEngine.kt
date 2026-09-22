package com.example.shortdramarecorder.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.example.shortdramarecorder.access.DramaAccessibilityService
import com.example.shortdramarecorder.state.AdStateCoordinator
import com.example.shortdramarecorder.state.ContinuePromptDetector
import com.example.shortdramarecorder.state.SwipeDiagnostics
import com.example.shortdramarecorder.state.PlaybackSkipSession
import com.example.shortdramarecorder.state.SkipRuntimeState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.regex.Pattern

class OcrMonitorEngine(
    private val context: Context
) {
    private val recognizer =
        TextRecognition.getClient(
            ChineseTextRecognizerOptions
                .Builder()
                .build()
        )

    private var handlerThread:
        HandlerThread? = null

    private var handler:
        Handler? = null

    @Volatile
    private var running = false

    @Volatile
    private var analyzing = false

    private var lastOcrAt = 0L
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

    private val scanRunnable =
        object : Runnable {
            override fun run() {
                if (!running) return

                if (
                    SkipRuntimeState
                        .isRecognitionActive() &&
                    !analyzing &&
                    shouldRunOcr()
                ) {
                    requestFrame()
                }

                handler?.postDelayed(
                    this,
                    200L
                )
            }
        }

    fun start() {
        if (running) return

        running = true

        handlerThread =
            HandlerThread(
                "ocr-monitor"
            ).also {
                it.start()
            }

        handler =
            Handler(
                handlerThread!!.looper
            )

        SkipRuntimeState.ocrStatus =
            if (
                DramaAccessibilityService
                    .isConnected()
            ) {
                "OCR低频兜底"
            } else {
                "OCR等待无障碍"
            }

        handler?.post(
            scanRunnable
        )
    }

    private fun shouldRunOcr():
        Boolean {
        if (
            PlaybackSkipSession.isActive &&
            DramaAccessibilityService.isOwnAppForeground()
        ) {
            return false
        }

        val now =
            SystemClock.elapsedRealtime()

        val interval =
            when {
                SkipRuntimeState.isOcrBoostActive() ->
                    300L

                SkipRuntimeState.isAd ->
                    450L

                else ->
                    900L
            }

        return (
            now - lastOcrAt >= interval
        )
    }

    private fun requestFrame() {
        if (
            PlaybackSkipSession.isActive &&
            DramaAccessibilityService.isOwnAppForeground()
        ) {
            return
        }

        analyzing = true
        lastOcrAt =
            SystemClock.elapsedRealtime()

        DramaAccessibilityService
            .requestScreenshotForOcr {
                    bitmap,
                    error ->

                if (!running) {
                    bitmap?.recycle()
                    analyzing = false
                    return@requestScreenshotForOcr
                }

                if (
                    PlaybackSkipSession.isActive &&
                    DramaAccessibilityService.isOwnAppForeground()
                ) {
                    bitmap?.recycle()
                    analyzing = false
                    return@requestScreenshotForOcr
                }

                if (bitmap == null) {
                    val status = error ?: "OCR截图失败"
                    SkipRuntimeState.ocrStatus = status

                    val transient =
                        status == "OCR截图稍候" ||
                            status == "OCR截图处理中"

                    if (
                        PlaybackSkipSession.isActive &&
                        !transient &&
                        SystemClock.elapsedRealtime() - SwipeDiagnostics.lastEventAt > 2500L
                    ) {
                        SwipeDiagnostics.report("截图未完成：$status")
                    }

                    analyzing = false
                    return@requestScreenshotForOcr
                }

                SkipRuntimeState.ocrStatus =
                    "OCR识别中"

                analyzeBitmap(bitmap)
            }
    }

    private fun buildCompactOcrBitmap(
        fullBitmap: Bitmap
    ): Bitmap {
        val targetWidth =
            if (PlaybackSkipSession.isActive) {
                540
            } else {
                360
            }

        val scale =
            if (
                fullBitmap.width >
                targetWidth
            ) {
                targetWidth.toFloat() /
                    fullBitmap.width
                        .toFloat()
            } else {
                1f
            }

        val width =
            (
                fullBitmap.width *
                    scale
                )
                .toInt()
                .coerceAtLeast(1)

        val height =
            (
                fullBitmap.height *
                    scale
                )
                .toInt()
                .coerceAtLeast(1)

        val scaled =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    fullBitmap,
                    width,
                    height,
                    true
                )
            } else {
                fullBitmap
            }

        if (
            scaled !== fullBitmap
        ) {
            fullBitmap.recycle()
        }

        val topHeight =
            (scaled.height * 0.18f)
                .toInt()
                .coerceAtLeast(1)

        val bottomStart =
            (scaled.height * 0.42f)
                .toInt()
                .coerceIn(
                    0,
                    scaled.height - 1
                )

        val bottomHeight =
            scaled.height -
                bottomStart

        val output =
            Bitmap.createBitmap(
                scaled.width,
                topHeight +
                    bottomHeight,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(output)

        canvas.drawBitmap(
            scaled,
            Rect(
                0,
                0,
                scaled.width,
                topHeight
            ),
            Rect(
                0,
                0,
                scaled.width,
                topHeight
            ),
            null
        )

        canvas.drawBitmap(
            scaled,
            Rect(
                0,
                bottomStart,
                scaled.width,
                scaled.height
            ),
            Rect(
                0,
                topHeight,
                scaled.width,
                topHeight +
                    bottomHeight
            ),
            null
        )

        scaled.recycle()

        return output
    }

    private fun buildBottomPromptBitmap(fullBitmap: Bitmap): Bitmap {
        val startY = (fullBitmap.height * 0.88f).toInt()
            .coerceIn(0, fullBitmap.height - 1)
        val bottom = Bitmap.createBitmap(
            fullBitmap, 0, startY,
            fullBitmap.width, fullBitmap.height - startY
        )
        if (bottom.width >= 1080) return bottom
        val ratio = 1080f / bottom.width
        val enlarged = Bitmap.createScaledBitmap(
            bottom, 1080,
            (bottom.height * ratio).toInt().coerceAtLeast(1), true
        )
        bottom.recycle()
        return enlarged
    }

    private fun analyzeBitmap(fullBitmap: Bitmap) {
        val footer = if (PlaybackSkipSession.isActive) {
            runCatching { buildBottomPromptBitmap(fullBitmap) }.getOrNull()
        } else null
        val compact = try {
            buildCompactOcrBitmap(fullBitmap)
        } catch (error: Throwable) {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
            footer?.recycle()
            SkipRuntimeState.ocrStatus = "OCR裁剪失败"
            analyzing = false
            return
        }

        fun complete() {
            if (!compact.isRecycled) compact.recycle()
            if (footer != null && !footer.isRecycled) footer.recycle()
            analyzing = false
        }

        fun shouldDiscard(): Boolean =
            !running ||
                (PlaybackSkipSession.isActive &&
                    DramaAccessibilityService.isOwnAppForeground())

        fun reportFooterMiss(text: String) {
            if (shouldDiscard() || !PlaybackSkipSession.isActive) return
            if (SystemClock.elapsedRealtime() -
                SwipeDiagnostics.lastEventAt <= 15000L) return
            val visible = text.replace(Regex("""\s+"""), " ").trim()
            SwipeDiagnostics.report(
                if (visible.isBlank()) "底部专项OCR未识别到文字"
                else "底部12%未识别到继续提示：${visible.takeLast(45)}"
            )
        }

        try {
            recognizer.process(InputImage.fromBitmap(compact, 0))
                .addOnSuccessListener { result ->
                    if (shouldDiscard()) return@addOnSuccessListener
                    val fullText = result.text.orEmpty()
                    if (fullText.isNotBlank()) {
                        try {
                            handleRecognizedText(fullText)
                            SkipRuntimeState.ocrStatus = "OCR正常"
                        } catch (error: Throwable) {
                            SwipeDiagnostics.report(
                                "OCR处理异常：${error.javaClass.simpleName}"
                            )
                        }
                    } else SkipRuntimeState.ocrStatus = "OCR未识别文字"
                }
                .addOnFailureListener { error ->
                    if (!shouldDiscard()) SkipRuntimeState.ocrStatus =
                        "OCR异常：${(error.message ?: "未知错误").take(24)}"
                }
                .addOnCompleteListener {
                    if (footer == null || shouldDiscard()) {
                        complete()
                        return@addOnCompleteListener
                    }
                    try {
                        recognizer.process(InputImage.fromBitmap(footer, 0))
                            .addOnSuccessListener { result ->
                                if (shouldDiscard()) return@addOnSuccessListener
                                val bottomText = result.text.orEmpty()
                                val footerHasPrompt =
                                    ContinuePromptDetector.matches(bottomText)
                                AdStateCoordinator.observeFooterPrompt(footerHasPrompt)
                                if (footerHasPrompt) {
                                    SwipeDiagnostics.report(
                                        "底部12%命中继续观看，准备上滑"
                                    )
                                    handleRecognizedText(bottomText, fromFooterOcr = true)
                                } else reportFooterMiss(bottomText)
                            }
                            .addOnFailureListener { error ->
                                if (!shouldDiscard()) SwipeDiagnostics.report(
                                    "底部专项OCR失败：${error.javaClass.simpleName}"
                                )
                            }
                            .addOnCompleteListener { complete() }
                    } catch (error: Throwable) {
                        if (!shouldDiscard()) SwipeDiagnostics.report(
                            "底部专项OCR异常：${error.javaClass.simpleName}"
                        )
                        complete()
                    }
                }
        } catch (error: Throwable) {
            SkipRuntimeState.ocrStatus =
                "OCR请求异常：${error.javaClass.simpleName}"
            complete()
        }
    }

    private fun handleRecognizedText(
        text: String,
        fromFooterOcr: Boolean = false
    ) {
        if (
            text.isBlank() ||
            !SkipRuntimeState
                .isRecognitionActive()
        ) {
            return
        }

        val episodeMatcher =
            episodePattern.matcher(text)

        val episode =
            if (
                episodeMatcher.find()
            ) {
                episodeMatcher
                    .group(1)
                    ?.toIntOrNull()
            } else {
                null
            }

        val totalMatcher =
            totalPattern.matcher(text)

        val totalEpisodes =
            if (
                totalMatcher.find()
            ) {
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

        if (
            totalEpisodes != null
        ) {
            SkipRuntimeState.totalEpisodes =
                totalEpisodes
        }

        val hasContinuePrompt =
            hasContinuePrompt(text)

        val unlockAdDetected =
            unlockAdPattern.matcher(text).find() ||
                text.contains("秒后可解锁付费集")

        val assistAdDetected =
            assistAdKeywords.count {
                text.contains(it)
            } >= 2

        if (definiteDramaPage &&
            !(PlaybackSkipSession.isActive && hasContinuePrompt)
        ) {
            AdStateCoordinator.reportNormal(
                SkipRuntimeState.currentEpisode,
                true
            )
            updateTitle(text)
            return
        }

        if (hasContinuePrompt) {
            SkipRuntimeState.boostOcr(4500L)

            if (AdStateCoordinator.requestContinueSwipe(fromFooterOcr)) {
                SwipeDiagnostics.report(
                    "OCR命中继续观看，准备上滑 · ${DramaAccessibilityService.foregroundPackageName() ?: "未知应用"}"
                )
                DramaAccessibilityService.requestSwipeUpByOcr()
                SkipRuntimeState.boostOcr(4500L)
            }

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

            updateTitle(text)
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

    private fun updateTitle(
        text: String
    ) {
        val line =
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull {
                    it.contains("短剧") &&
                        totalPattern
                            .matcher(it)
                            .find()
                }
                ?: return

        val cleaned =
            line.replace(
                "短剧",
                ""
            )
                .replace(
                    "·",
                    " "
                )
                .replace(
                    "…",
                    " "
                )
                .replace(
                    Regex(
                        "\\d+\\s*集全"
                    ),
                    ""
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        if (cleaned.isNotBlank()) {
            SkipRuntimeState.title =
                cleaned.take(30)
        }
    }

    fun stop() {
        running = false
        analyzing = false

        handler?.removeCallbacks(
            scanRunnable
        )

        runCatching {
            recognizer.close()
        }

        handlerThread?.quitSafely()

        handler = null
        handlerThread = null

        SkipRuntimeState.ocrStatus =
            "OCR已停止"
    }
}
