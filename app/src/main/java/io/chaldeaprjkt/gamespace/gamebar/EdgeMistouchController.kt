/*
 * Copyright (C) 2025-2026 AxionOS & Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.chaldeaprjkt.gamespace.gamebar

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.chaldeaprjkt.gamespace.data.AppSettings
import java.io.File
import java.io.FileOutputStream

class EdgeMistouchController(
    private val context: Context,
    private val wm: WindowManager,
    private val appSettings: AppSettings
) {

    private val handler = Handler(Looper.getMainLooper())
    private var isStarted = false

    private var leftOverlay: EdgeBlockerView? = null
    private var rightOverlay: EdgeBlockerView? = null
    private var topOverlay: EdgeBlockerView? = null
    private var bottomOverlay: EdgeBlockerView? = null

    private var isLeftAdded = false
    private var isRightAdded = false
    private var isTopAdded = false
    private var isBottomAdded = false

    fun start() {
        if (isStarted) return
        isStarted = true
        applyHardwareTouchHooks(true)
        syncGestureLock()
        updateOverlays()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        applyHardwareTouchHooks(false)
        removeOverlays()
        handler.removeCallbacksAndMessages(null)
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        if (isStarted) {
            updateOverlays()
        }
    }

    fun updateState() {
        handler.post {
            if (isStarted) {
                syncGestureLock()
                updateOverlays()
            }
        }
    }

    private fun syncGestureLock() {
        try {
            val lockGestures = appSettings.lockGesture
            Settings.Secure.putIntForUser(
                context.contentResolver,
                KEY_GESTURE_LOCK,
                if (lockGestures) 1 else 0,
                UserHandle.USER_CURRENT
            )
            Settings.Secure.putIntForUser(
                context.contentResolver,
                KEY_MISTOUCH_ACTIVE,
                if (appSettings.edgeMistouchEnabled) 1 else 0,
                UserHandle.USER_CURRENT
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync gesture lock secure settings", e)
        }
    }

    private fun updateOverlays() {
        if (!appSettings.edgeMistouchEnabled) {
            removeOverlays()
            return
        }

        val deadzoneDp = appSettings.edgeMistouchSize
        if (deadzoneDp <= 0) {
            removeOverlays()
            return
        }

        val density = context.resources.displayMetrics.density
        val deadzonePx = (deadzoneDp * density).toInt().coerceAtLeast(8)
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Left Edge Overlay
        val leftParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.LEFT or Gravity.TOP
            width = deadzonePx
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        val rightParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.RIGHT or Gravity.TOP
            width = deadzonePx
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        if (leftOverlay == null) {
            leftOverlay = EdgeBlockerView(context, appSettings)
        }
        if (rightOverlay == null) {
            rightOverlay = EdgeBlockerView(context, appSettings)
        }

        try {
            if (!isLeftAdded) {
                wm.addView(leftOverlay, leftParams)
                isLeftAdded = true
            } else {
                wm.updateViewLayout(leftOverlay, leftParams)
            }

            if (!isRightAdded) {
                wm.addView(rightOverlay, rightParams)
                isRightAdded = true
            } else {
                wm.updateViewLayout(rightOverlay, rightParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add/update edge overlays", e)
        }

        // Corner / Top-Bottom protection if enabled
        if (appSettings.edgeMistouchCorners) {
            val cornerBreadthPx = (deadzoneDp * 1.5f * density).toInt()
            val topParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = (deadzonePx * 0.75f).toInt()
            }

            val bottomParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = (deadzonePx * 0.75f).toInt()
            }

            if (topOverlay == null) topOverlay = EdgeBlockerView(context, appSettings)
            if (bottomOverlay == null) bottomOverlay = EdgeBlockerView(context, appSettings)

            try {
                if (!isTopAdded) {
                    wm.addView(topOverlay, topParams)
                    isTopAdded = true
                } else {
                    wm.updateViewLayout(topOverlay, topParams)
                }

                if (!isBottomAdded) {
                    wm.addView(bottomOverlay, bottomParams)
                    isBottomAdded = true
                } else {
                    wm.updateViewLayout(bottomOverlay, bottomParams)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add corner/top/bottom overlays", e)
            }
        } else {
            removeTopBottomOverlays()
        }
    }

    private fun removeOverlays() {
        if (isLeftAdded && leftOverlay != null) {
            runCatching { wm.removeViewImmediate(leftOverlay) }
            isLeftAdded = false
        }
        if (isRightAdded && rightOverlay != null) {
            runCatching { wm.removeViewImmediate(rightOverlay) }
            isRightAdded = false
        }
        removeTopBottomOverlays()
        leftOverlay = null
        rightOverlay = null
    }

    private fun removeTopBottomOverlays() {
        if (isTopAdded && topOverlay != null) {
            runCatching { wm.removeViewImmediate(topOverlay) }
            isTopAdded = false
        }
        if (isBottomAdded && bottomOverlay != null) {
            runCatching { wm.removeViewImmediate(bottomOverlay) }
            isBottomAdded = false
        }
        topOverlay = null
        bottomOverlay = null
    }

    private fun applyHardwareTouchHooks(enable: Boolean) {
        val palmNodes = listOf(
            "/sys/devices/virtual/touch/touch_dev/palm_sensor",
            "/sys/class/touch/touch_dev/palm_sensor",
            "/proc/touchpanel/oppo_tp_limit_enable",
            "/sys/devices/platform/goodix_ts.0/edge_filter",
            "/sys/devices/virtual/touch/touch_dev/edge_filter"
        )
        for (path in palmNodes) {
            val file = File(path)
            if (file.exists() && file.canWrite()) {
                try {
                    FileOutputStream(file).use { fos ->
                        fos.write((if (enable) "1" else "0").toByteArray())
                    }
                    Log.d(TAG, "Hardware touch rejection written to $path: $enable")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write hardware touch node $path", e)
                }
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private class EdgeBlockerView(
        context: Context,
        private val appSettings: AppSettings
    ) : View(context) {

        private var isTouchRejected = false
        private var indicatorAlpha = 0f
        private val indicatorPaint = Paint().apply {
            color = Color.parseColor("#4433B5E5")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Accidental touch starts in deadzone: intercept and swallow it!
                    isTouchRejected = true
                    if (appSettings.edgeMistouchFeedback) {
                        triggerVisualFeedback()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTouchRejected) {
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isTouchRejected) {
                        isTouchRejected = false
                        return true
                    }
                }
            }
            return false
        }

        private fun triggerVisualFeedback() {
            indicatorAlpha = 1f
            invalidate()
            postDelayed(object : Runnable {
                override fun run() {
                    indicatorAlpha -= 0.15f
                    if (indicatorAlpha > 0f) {
                        invalidate()
                        postDelayed(this, 16)
                    } else {
                        indicatorAlpha = 0f
                        invalidate()
                    }
                }
            }, 16)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (indicatorAlpha > 0f) {
                indicatorPaint.alpha = (indicatorAlpha * 120).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), indicatorPaint)
            }
        }
    }

    companion object {
        private const val TAG = "EdgeMistouchController"
        const val KEY_GESTURE_LOCK = "ax_gaming_gesture_lock"
        const val KEY_MISTOUCH_ACTIVE = "gamespace_edge_mistouch_active"
    }
}
