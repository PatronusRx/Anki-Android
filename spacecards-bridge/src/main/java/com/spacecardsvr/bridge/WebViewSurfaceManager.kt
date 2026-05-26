// SPDX-License-Identifier: GPL-3.0-or-later
package com.spacecardsvr.bridge

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the lifecycle of WebView -> Presentation -> VirtualDisplay -> Surface
 * pipelines, keyed by opaque `handle: Long` values returned to Unity.
 *
 * Each handle owns:
 *   - a SurfaceTexture wrapping the GL_TEXTURE_EXTERNAL_OES texture id provided by Unity
 *   - a Surface wrapping that SurfaceTexture
 *   - a VirtualDisplay whose presentation surface is the above
 *   - a Presentation whose content view is a WebView
 *
 * The Presentation drives hardware-accelerated composition; SurfaceTexture
 * wakes the Unity render thread per frame via setOnFrameAvailableListener.
 *
 * P3a (this file): Kotlin lifecycle scaffolding. Hands off OES texture allocation
 * and per-frame `updateTexImage` to the C++ native plugin built in P3b.
 */
internal class WebViewSurfaceManager(
    private val appContext: Context,
) {
    internal class CardSurface(
        val handle: Long,
        val width: Int,
        val height: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val virtualDisplay: VirtualDisplay,
        val presentation: CardPresentation,
    )

    private val nextHandle = AtomicLong(1)
    private val surfaces = HashMap<Long, CardSurface>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun create(
        width: Int,
        height: Int,
        glTextureId: Int,
    ): Long {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "WebViewSurfaceManager.create() must be called on the main thread"
        }
        val surfaceTexture = SurfaceTexture(glTextureId).apply { setDefaultBufferSize(width, height) }
        val surface = Surface(surfaceTexture)
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val virtualDisplay =
            dm.createVirtualDisplay(
                "spacecards-card-${nextHandle.get()}",
                width,
                height,
                DisplayMetrics.DENSITY_DEFAULT,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        val presentation = CardPresentation(appContext, virtualDisplay.display, width, height)
        presentation.show()

        val handle = nextHandle.getAndIncrement()
        val card =
            CardSurface(
                handle = handle,
                width = width,
                height = height,
                surfaceTexture = surfaceTexture,
                surface = surface,
                virtualDisplay = virtualDisplay,
                presentation = presentation,
            )
        synchronized(surfaces) { surfaces[handle] = card }
        Timber.i("Created card surface handle=%d size=%dx%d texId=%d", handle, width, height, glTextureId)
        return handle
    }

    fun render(
        handle: Long,
        html: String,
        @Suppress("UNUSED_PARAMETER") css: String,
    ) {
        val card = lookup(handle) ?: return
        val combined = if (css.isBlank()) html else "<style>$css</style>$html"
        runOnMain {
            card.presentation.webView.loadDataWithBaseURL(
                "file://${card.handle}/",
                combined,
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    fun destroy(handle: Long) {
        val card = synchronized(surfaces) { surfaces.remove(handle) } ?: return
        runOnMain {
            try {
                card.presentation.dismiss()
            } catch (t: Throwable) {
                Timber.w(t, "presentation.dismiss failed for handle=%d", handle)
            }
            card.virtualDisplay.release()
            card.surface.release()
            card.surfaceTexture.release()
            Timber.i("Destroyed card surface handle=%d", handle)
        }
    }

    internal fun lookup(handle: Long): CardSurface? = synchronized(surfaces) { surfaces[handle] }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    internal class CardPresentation(
        outerContext: Context,
        display: android.view.Display,
        private val webViewWidth: Int,
        private val webViewHeight: Int,
    ) : Presentation(outerContext, display) {
        lateinit var webView: WebView
            private set

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            webView =
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(webViewWidth, webViewHeight)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                }
            setContentView(webView)
        }
    }
}
