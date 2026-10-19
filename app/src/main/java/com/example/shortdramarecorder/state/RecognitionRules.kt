package com.example.shortdramarecorder.state

import android.content.Context
import java.util.regex.Pattern

/**
 * 可编辑的短剧页面识别规则。配置是不可变快照，OCR 与无障碍共用同一份。
 * 普通用户配置的是文字/占位符，不是正则表达式，避免无效表达式导致识别崩溃。
 */
data class RecognitionRuleSet(
    val episodeFormat: String = "第{n}集",
    val totalFormat: String = "{n}集全",
    val dramaLabels: List<String> = listOf("短剧"),
    val primaryAdKeywords: List<String> = listOf("秒后可解锁付费集"),
    val auxiliaryAdKeywords: List<String> =
        listOf("退出短剧", "免费广告", "点击进入直播间"),
    val continuePrompts: List<String> = listOf(
        "上滑继续观看短剧",
        "上滑继续观看",
        "继续观看短剧",
        "上滑继续看短剧",
        "上滑继续看"
    )
) {
    val episodePattern: Pattern = compileEpisodeFormat(episodeFormat)
    val totalPattern: Pattern = compileEpisodeFormat(totalFormat)

    private fun numberFrom(pattern: Pattern, text: String): Int? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }

    fun episodeNumber(text: String): Int? = numberFrom(episodePattern, text)
    fun totalNumber(text: String): Int? = numberFrom(totalPattern, text)
    fun containsDramaLabel(text: String): Boolean =
        dramaLabels.any { text.contains(it) }

    fun containsPrimaryAd(text: String): Boolean =
        primaryAdKeywords.any { text.contains(it) }

    /** 与旧版相同：至少两条独立辅助关键词命中，防止误把短剧页识别成广告。 */
    fun containsAuxiliaryAd(text: String): Boolean =
        auxiliaryAdKeywords.count { text.contains(it) } >= 2

    fun containsContinuePrompt(raw: String): Boolean {
        val text = compact(raw)
        if (continuePrompts.any { text.contains(compact(it)) }) return true
        // 保留原版对缺字/换行的兜底，但仅在用户保留默认提示词时启用。
        return continuePrompts.contains("上滑继续观看短剧") &&
            text.contains("上滑") && text.contains("继续") &&
            (text.contains("观看") || text.contains("短剧"))
    }

    companion object {
        private val normalizeRegex = Regex("""[\s\p{P}\p{S}]+""")
        private const val NUMBER_TOKEN = "{n}"

        fun compact(value: String): String =
            value.replace(normalizeRegex, "")

        fun compileEpisodeFormat(format: String): Pattern {
            require(format.split(NUMBER_TOKEN).size == 2) {
                "集数格式必须包含且仅包含一个 {n}"
            }
            val parts = format.split(NUMBER_TOKEN)
            val expression = Pattern.quote(parts[0]) +
                "\\s*(\\d{1,4})\\s*" + Pattern.quote(parts[1])
            return Pattern.compile(expression)
        }
    }
}

object RecognitionRules {
    private const val PREFS = "recognition_fields_v1"
    private const val KEY_EPISODE = "episode_format"
    private const val KEY_TOTAL = "total_format"
    private const val KEY_DRAMA = "drama_labels"
    private const val KEY_PRIMARY = "primary_ad"
    private const val KEY_AUX = "auxiliary_ad"
    private const val KEY_CONTINUE = "continue_prompt"

    @Volatile private var active = RecognitionRuleSet()
    @Volatile private var initialized = false

    fun current(): RecognitionRuleSet = active

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = RecognitionRuleSet()
        val candidate = runCatching {
            RecognitionRuleSet(
                episodeFormat = prefs.getString(KEY_EPISODE, defaults.episodeFormat) ?: defaults.episodeFormat,
                totalFormat = prefs.getString(KEY_TOTAL, defaults.totalFormat) ?: defaults.totalFormat,
                dramaLabels = decode(prefs.getString(KEY_DRAMA, null), defaults.dramaLabels),
                primaryAdKeywords = decode(prefs.getString(KEY_PRIMARY, null), defaults.primaryAdKeywords),
                auxiliaryAdKeywords = decode(prefs.getString(KEY_AUX, null), defaults.auxiliaryAdKeywords),
                continuePrompts = decode(prefs.getString(KEY_CONTINUE, null), defaults.continuePrompts)
            )
        }.getOrDefault(defaults)
        active = if (validate(candidate) == null) candidate else defaults
        initialized = true
    }

    private fun decode(saved: String?, defaults: List<String>): List<String> =
        saved?.split('\n')?.map(String::trim)?.filter(String::isNotEmpty)
            ?.distinct() ?: defaults

    /** 返回错误提示或 null，防止空规则 / 过长规则误触发。 */
    fun validate(config: RecognitionRuleSet): String? {
        if (config.episodeFormat.length > 48 ||
            config.episodeFormat.split("{n}").size != 2
        ) return "当前集数格式需要一个 {n}，最长48字"
        if (config.totalFormat.length > 48 ||
            config.totalFormat.split("{n}").size != 2
        ) return "总集数格式需要一个 {n}，最长48字"

        val groups = listOf(
            "短剧标识词" to config.dramaLabels,
            "主要广告词" to config.primaryAdKeywords,
            "辅助广告词" to config.auxiliaryAdKeywords,
            "继续观看提示词" to config.continuePrompts
        )
        for ((title, terms) in groups) {
            if (terms.size > 20 || terms.any { it.isBlank() || it.length > 48 }) {
                return "$title 最多20条，每条1～48字"
            }
            if (title != "辅助广告词" && terms.isEmpty()) {
                return "$title 至少保留一条"
            }
        }
        return null
    }

    @Synchronized
    fun save(context: Context, config: RecognitionRuleSet): String? {
        val error = validate(config)
        if (error != null) return error
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.edit()
            .putString(KEY_EPISODE, config.episodeFormat)
            .putString(KEY_TOTAL, config.totalFormat)
            .putString(KEY_DRAMA, config.dramaLabels.joinToString("\n"))
            .putString(KEY_PRIMARY, config.primaryAdKeywords.joinToString("\n"))
            .putString(KEY_AUX, config.auxiliaryAdKeywords.joinToString("\n"))
            .putString(KEY_CONTINUE, config.continuePrompts.joinToString("\n"))
            .commit()
        if (!saved) return "本机保存失败，请重试"
        active = config
        initialized = true
        return null
    }

    @Synchronized
    fun restoreDefaults(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.edit().clear().commit()) return false
        active = RecognitionRuleSet()
        initialized = true
        return true
    }
}
