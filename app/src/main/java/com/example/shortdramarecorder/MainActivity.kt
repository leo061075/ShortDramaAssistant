package com.example.shortdramarecorder

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.shortdramarecorder.access.DramaAccessibilityService
import com.example.shortdramarecorder.databinding.ActivityMainBinding
import com.example.shortdramarecorder.overlay.FloatingControlService
import com.example.shortdramarecorder.state.PlaybackSkipSession
import com.example.shortdramarecorder.state.SkipRuntimeState
import com.example.shortdramarecorder.state.SwipeDiagnostics

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 700L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupActions()
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        DramaAccessibilityService.setOwnAppVisible(true)
        updateStatus()
    }

    override fun onPause() {
        DramaAccessibilityService.setOwnAppVisible(false)
        super.onPause()
    }

    private fun setupActions() {
        binding.btnBackSkip.setOnClickListener {
            finish()
        }

        binding.btnSkipSettings.setOnClickListener {
            showAboutDialog()
        }

        binding.btnSkipOverlay.setOnClickListener {
            openOverlayPermission()
        }

        binding.btnSkipAccessibility.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }

        binding.btnStartSkip.setOnClickListener {
            startSkipMode()
        }
    }

    private fun openOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else if (PlaybackSkipSession.isActive) {
            startService(
                Intent(this, FloatingControlService::class.java)
            )
        }
    }

    private fun startSkipMode() {
        if (PlaybackSkipSession.isActive) return

        if (!Settings.canDrawOverlays(this)) {
            binding.tvSkipStatus.text = "请先开启悬浮窗权限"
            return
        }

        if (!DramaAccessibilityService.isConnected()) {
            binding.tvSkipStatus.text = "请先开启自动识别权限"
            return
        }

        val started =
            DramaAccessibilityService.startPlaybackSkipMode()

        if (!started) {
            binding.tvSkipStatus.text =
                "启动失败，请重新开启自动识别权限"
            return
        }

        startService(
            Intent(this, FloatingControlService::class.java)
        )

        binding.tvSkipStatus.text = "后台运行中"
        updateStatus()
    }

    private fun updateStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = DramaAccessibilityService.isConnected()
        val active = PlaybackSkipSession.isActive

        updatePermissionState(binding.tvSkipOverlayState, overlay)
        updatePermissionState(binding.tvSkipAccessibilityState, accessibility)

        binding.tvSkipStatus.text = when {
            !PlaybackSkipSession.lastError.isNullOrBlank() ->
                "运行异常"

            active && SkipRuntimeState.isAd ->
                "广告识别中"

            active ->
                "后台运行中"

            overlay && accessibility ->
                "准备就绪"

            else ->
                "等待授权"
        }

        binding.tvSkipEpisode.text =
            "当前集\n${
                SkipRuntimeState.currentEpisode
                    ?.let { "第${it}集" }
                    ?: "--"
            }"

        binding.tvSkipAdState.text =
            "页面状态\n${
                when {
                    SkipRuntimeState.isAd -> "广告中"
                    active -> "短剧播放"
                    else -> "待机"
                }
            }"

        binding.tvSkipRunMode.text =
            "运行方式\n${if (active) "后台监测" else "未启动"}"

        binding.btnStartSkip.isEnabled =
            !active && overlay && accessibility

        binding.btnStartSkip.alpha =
            if (binding.btnStartSkip.isEnabled) 1f else 0.58f

        binding.btnStartSkip.text =
            when {
                active ->
                    "跳广告运行中 · 请使用悬浮窗停止"

                !overlay || !accessibility ->
                    "完成授权后开始"

                else ->
                    "开始跳广告"
            }

        val diagnostics =
            if (active) SwipeDiagnostics.message else "等待启动"

        binding.tvSkipBackgroundHint.text =
            "1.0 · 识别诊断：$diagnostics · ${SkipRuntimeState.ocrStatus}"
    }

    private fun updatePermissionState(
        view: TextView,
        enabled: Boolean
    ) {
        view.text =
            if (enabled) "✓ 已开启" else "去开启"

        view.setTextColor(
            getColor(
                if (enabled) R.color.proto_green
                else R.color.proto_muted
            )
        )
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("短剧助手 1.0")
            .setMessage(
                """
                当前版本仅提供跳广告功能。

                工作流程：
                1. 识别广告页面
                2. 等待广告播放结束
                3. 识别“上滑继续观看短剧”
                4. 自动上滑进入下一集

                必需权限：
                • 悬浮窗
                • 无障碍 / 自动识别
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }
}
