package com.example.shortdramarecorder.state

/** 跳广告 OCR 和无障碍识别共用可编辑配置。 */
object ContinuePromptDetector {
    fun matches(raw: String): Boolean =
        RecognitionRules.current().containsContinuePrompt(raw)
}
