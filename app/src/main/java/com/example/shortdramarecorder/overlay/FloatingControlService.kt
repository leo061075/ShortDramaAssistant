package com.example.shortdramarecorder.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import com.example.shortdramarecorder.R
import com.example.shortdramarecorder.access.DramaAccessibilityService
import com.example.shortdramarecorder.state.SkipRuntimeState

class FloatingControlService : Service() {

    private lateinit var wm: WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())

    private val statusUpdater = object : Runnable {
        override fun run() {
            view?.findViewById<TextView>(
                R.id.tvFloatingStatus
            )?.text =
                SkipRuntimeState.displayStatus()

            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val floatingView =
            LayoutInflater.from(this)
                .inflate(R.layout.view_floating_controls, null)

        view = floatingView

        val layoutParams =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 220
            }

        params = layoutParams
        wm.addView(floatingView, layoutParams)

        floatingView
            .findViewById<ImageButton>(R.id.btnFloatingStop)
            .setOnClickListener {
                DramaAccessibilityService.stopPlaybackSkipMode()
                stopSelf()
            }

        floatingView
            .findViewById<View>(R.id.dragHandle)
            .setOnTouchListener(
                object : View.OnTouchListener {
                    var downX = 0f
                    var downY = 0f
                    var startX = 0
                    var startY = 0

                    override fun onTouch(
                        touchedView: View,
                        event: MotionEvent
                    ): Boolean {
                        val p = params ?: return false

                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.rawX
                                downY = event.rawY
                                startX = p.x
                                startY = p.y
                            }

                            MotionEvent.ACTION_MOVE -> {
                                p.x =
                                    startX +
                                        (event.rawX - downX).toInt()

                                p.y =
                                    startY +
                                        (event.rawY - downY).toInt()

                                wm.updateViewLayout(
                                    floatingView,
                                    p
                                )
                            }
                        }

                        return true
                    }
                }
            )

        handler.post(statusUpdater)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusUpdater)

        view?.let {
            runCatching { wm.removeView(it) }
        }

        view = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
