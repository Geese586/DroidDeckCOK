package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch as a mouse, the way a remote desktop does it: where you touch is where the pointer goes.
 *
 *  - tap ............................ left click
 *  - drag ........................... moves the pointer, no button (hover)
 *  - hold, then lift ................ right click
 *  - hold, then drag ................ left-button drag (a window by its title bar, a selection)
 *  - two fingers up / down .......... scroll wheel
 *
 * The listener gets pointer positions in the view's pixels; the activity maps them onto the
 * compositor. Mouse events are not handled here - a mouse has real buttons.
 */
class PointerGestures(
    private val slopPx: Float,
    private val listener: Listener,
) {
    interface Listener {
        fun onMove(x: Float, y: Float)
        /** [button] is an evdev code: BTN_LEFT, BTN_RIGHT. */
        fun onButton(button: Int, pressed: Boolean, x: Float, y: Float)
        fun onWheel(steps: Int)
        fun onLongPress()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var held = false          // the long press fired
    private var dragging = false      // left button down after a hold
    private var scrolling = false
    private var scrollAnchorY = 0f
    private val longPress = Runnable {
        held = true
        listener.onLongPress()
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                moved = false; held = false; dragging = false; scrolling = false
                listener.onMove(event.x, event.y)
                handler.postDelayed(longPress, HOLD_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger: scrolling, and whatever the first one was doing is off.
                handler.removeCallbacks(longPress)
                if (dragging) { listener.onButton(BTN_LEFT, false, event.x, event.y); dragging = false }
                scrolling = true
                scrollAnchorY = event.getY(0)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val dy = event.getY(0) - scrollAnchorY
                    val steps = (dy / SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        // Finger down = content up = wheel "up", which the compositor takes as negative.
                        listener.onWheel(-steps)
                        scrollAnchorY += steps * SCROLL_STEP_PX
                    }
                    return true
                }
                if (!moved && (abs(event.x - downX) > slopPx || abs(event.y - downY) > slopPx)) {
                    moved = true
                    handler.removeCallbacks(longPress)
                    if (held) {
                        // Held, then moved: a drag with the left button, from where the hold was.
                        dragging = true
                        listener.onButton(BTN_LEFT, true, downX, downY)
                    }
                }
                if (moved) listener.onMove(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when {
                    scrolling -> {}
                    dragging -> listener.onButton(BTN_LEFT, false, event.x, event.y)
                    held && !moved && !cancelled -> {
                        listener.onButton(BTN_RIGHT, true, downX, downY)
                        listener.onButton(BTN_RIGHT, false, downX, downY)
                    }
                    !moved && !cancelled && event.eventTime - downAt < TAP_MS -> {
                        listener.onButton(BTN_LEFT, true, downX, downY)
                        listener.onButton(BTN_LEFT, false, downX, downY)
                    }
                }
                scrolling = false; dragging = false; held = false
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> return true
        }
        return false
    }

    companion object {
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
        private const val HOLD_MS = 450L
        private const val TAP_MS = 350L
        private const val SCROLL_STEP_PX = 40f

        fun slop(context: android.content.Context) = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
}
