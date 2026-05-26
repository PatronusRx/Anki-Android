// SPDX-License-Identifier: GPL-3.0-or-later
package com.spacecardsvr.bridge

/**
 * Async result contract for long-running bridge operations (login, sync).
 *
 * Implemented on the Unity side via `AndroidJavaProxy`. Methods are dispatched
 * on the Kotlin coroutine that owns the operation and may arrive on any thread;
 * Unity code should marshal back to the main thread before touching scene state.
 */
interface BridgeCallback {
    fun onProgress(
        percent: Int,
        message: String,
    )

    fun onSuccess(resultJson: String)

    fun onError(
        code: Int,
        message: String,
    )
}
