package com.example.shortdramarecorder.state

object PlaybackSkipSession {
    @Volatile var isActive: Boolean = false
    @Volatile var lastError: String? = null

    @Synchronized
    fun start() {
        isActive = true
        lastError = null
        SkipRuntimeState.resetForSkipMode()
    }

    @Synchronized
    fun stop() {
        isActive = false
        SkipRuntimeState.isAd = false
        SkipRuntimeState.clearOcrBoost()
        AdStateCoordinator.reset()
    }

    fun displayStatus(): String {
        val episode =
            SkipRuntimeState.currentEpisode
                ?.let { "第${it}集" }
                ?: "未识别集数"

        return when {
            !lastError.isNullOrBlank() ->
                "运行异常 · ${lastError!!.take(18)}"

            !isActive ->
                "未启动"

            SkipRuntimeState.isAd ->
                "广告中 · $episode"

            else ->
                "后台监测 · $episode"
        }
    }
}
