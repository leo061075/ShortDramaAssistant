package com.example.shortdramarecorder

import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.example.shortdramarecorder.access.DramaAccessibilityService
import com.example.shortdramarecorder.databinding.ActivitySettingsBinding
import com.example.shortdramarecorder.state.RecognitionRuleSet
import com.example.shortdramarecorder.state.RecognitionRules

/** 识别字段可编辑、可保存、可恢复默认值，保存后对识别线程即时生效。 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecognitionRules.initialize(applicationContext)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureInsets()
        fill(RecognitionRules.current())

        binding.btnSettingsBack.setOnClickListener { finish() }
        binding.btnSettingsSave.setOnClickListener { saveRules() }
        binding.btnResetRules.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢复默认识别字段？")
                .setMessage("将覆盖当前自定义字段，并立即应用默认规则。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    if (RecognitionRules.restoreDefaults(this)) {
                        fill(RecognitionRules.current())
                        toast("已恢复默认识别字段")
                    } else {
                        toast("恢复失败，请重试")
                    }
                }.show()
        }
    }

    override fun onResume() {
        super.onResume()
        DramaAccessibilityService.setOwnAppVisible(true)
    }

    override fun onPause() {
        DramaAccessibilityService.setOwnAppVisible(false)
        super.onPause()
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.proto_bg)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        val topPadding = binding.settingsHeader.paddingTop
        val bottomPadding = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val status = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val nav = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            binding.settingsHeader.updatePadding(top = topPadding + status.top)
            view.updatePadding(bottom = bottomPadding + nav.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun fill(rule: RecognitionRuleSet) {
        binding.etEpisodePattern.setText(rule.episodeFormat)
        binding.etTotalPattern.setText(rule.totalFormat)
        binding.etDramaLabels.setText(rule.dramaLabels.joinToString("\n"))
        binding.etPrimaryAd.setText(rule.primaryAdKeywords.joinToString("\n"))
        binding.etAuxiliaryAd.setText(rule.auxiliaryAdKeywords.joinToString("\n"))
        binding.etContinuePrompt.setText(rule.continuePrompts.joinToString("\n"))
    }

    private fun lines(field: EditText): List<String> =
        field.text.toString().lineSequence().map(String::trim)
            .filter(String::isNotEmpty).distinct().toList()

    private fun saveRules() {
        val rule = try {
            RecognitionRuleSet(
                episodeFormat = binding.etEpisodePattern.text.toString().trim(),
                totalFormat = binding.etTotalPattern.text.toString().trim(),
                dramaLabels = lines(binding.etDramaLabels),
                primaryAdKeywords = lines(binding.etPrimaryAd),
                auxiliaryAdKeywords = lines(binding.etAuxiliaryAd),
                continuePrompts = lines(binding.etContinuePrompt)
            )
        } catch (_: IllegalArgumentException) {
            toast("集数格式请使用 {n} 作为数字占位符")
            return
        }
        val error = RecognitionRules.save(this, rule)
        if (error != null) {
            toast(error)
        } else {
            toast("识别字段已保存，正在运行的跳广告模式也已生效")
            finish()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
