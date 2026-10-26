package com.nic.lgpower

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Turns touches on a surface into pointer moves, taps and two-finger scrolls. The full-screen
 * overlay and the pad that replaces the d-pad share this, so both feel the same.
 */
internal class TouchpadGesture(
    private val client: WebOsClient,
    density: Float,
    private val hapticMovePx: Float,
) {
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var moveAccumulator = 0f
    private var dragging = false
    private var totalDist = 0f
    private var dxCarry = 0f
    private var dyCarry = 0f
    private var scrolling = false
    private var lastScrollY = 0f
    private var scrollCarry = 0f
    private val tapThresholdPx = 12 * density
    private val scrollSensitivity = 18f  // px per scroll unit

    fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                moveAccumulator = 0f
                totalDist = 0f
                dragging = false
                dxCarry = 0f
                dyCarry = 0f
                scrolling = false
                scrollCarry = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down: switch to scroll mode
                if (event.pointerCount == 2) {
                    scrolling = true
                    dragging = false
                    lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                    scrollCarry = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount == 2) {
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val rawDy = midY - lastScrollY
                    lastScrollY = midY
                    val total = rawDy + scrollCarry
                    val units = (total / scrollSensitivity).toInt()
                    scrollCarry = total - units * scrollSensitivity
                    if (units != 0) client.pointerScroll(0, -units)
                } else if (!scrolling) {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    if (!dragging) {
                        totalDist += dist
                        if (totalDist >= tapThresholdPx) {
                            dragging = true
                            dxCarry = 0f
                            dyCarry = 0f
                        }
                    } else {
                        val totalDx = dx + dxCarry
                        val totalDy = dy + dyCarry
                        val sendDx = totalDx.toInt()
                        val sendDy = totalDy.toInt()
                        dxCarry = totalDx - sendDx
                        dyCarry = totalDy - sendDy
                        moveAccumulator += dist
                        if (moveAccumulator >= hapticMovePx) {
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            moveAccumulator = 0f
                        }
                        if (sendDx != 0 || sendDy != 0) client.pointerMove(sendDx, sendDy)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Back to single finger: stop scrolling
                scrolling = false
                dragging = false
                totalDist = 0f
                val remaining = if (event.actionIndex == 0) 1 else 0
                // rawX/rawY follow pointer 0, so its offset to view coordinates places the other
                // finger in the same space even when the surface is not at the screen origin
                lastTouchX = event.getX(remaining) + (event.rawX - event.getX(0))
                lastTouchY = event.getY(remaining) + (event.rawY - event.getY(0))
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !scrolling) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    client.pointerClick()
                }
                scrolling = false
            }
        }
        return true
    }
}
