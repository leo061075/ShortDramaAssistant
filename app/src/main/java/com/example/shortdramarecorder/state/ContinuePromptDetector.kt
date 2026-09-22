package com.example.shortdramarecorder.state

/**
 * OCR and Accessibility nodes may split the bottom caption, add spaces,
 * punctuation, or occasionally lose one character. Normalize first, then
 * accept a conservative fuzzy combination for the fixed bottom banner.
 */
object ContinuePromptDetector {
    fun matches(raw: String): Boolean {
        val text = raw.replace(Regex("""[\s\p{P}\p{S}]+"""), "")

        if (text.contains("上滑继续观看短剧")) return true
        if (text.contains("上滑继续观看")) return true
        if (text.contains("继续观看短剧")) return true
        if (text.contains("上滑继续看短剧")) return true
        if (text.contains("上滑继续看")) return true

        return text.contains("上滑") &&
            text.contains("继续") &&
            (text.contains("观看") || text.contains("短剧"))
    }
}
