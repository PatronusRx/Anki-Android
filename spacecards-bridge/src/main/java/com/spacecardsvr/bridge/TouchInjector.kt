// SPDX-License-Identifier: GPL-3.0-or-later
package com.spacecardsvr.bridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent

/**
 * Synthesizes MotionEvent and dispatches it into the WebView bound to a
 * given surface handle. Unity supplies an Android-compatible `action`
 * constant (`MotionEvent.ACTION_DOWN` = 0, `ACTION_UP` = 1, `ACTION_MOVE` = 2)
 * and a UV-derived (x, y) in pixel coordinates of the WebView's logical size.
 */
internal class TouchInjector(
    private val surfaceManager: WebViewSurfaceManager,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun inject(
        handle: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val card = surfaceManager.lookup(handle) ?: return
        mainHandler.post {
            val now = SystemClock.uptimeMillis()
            val downTime = if (action == MotionEvent.ACTION_DOWN) now else now - 1
            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
            try {
                card.presentation.webView.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
