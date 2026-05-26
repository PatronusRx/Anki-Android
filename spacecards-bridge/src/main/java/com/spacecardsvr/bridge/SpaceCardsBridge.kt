// SPDX-License-Identifier: GPL-3.0-or-later
package com.spacecardsvr.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import anki.scheduler.CardAnswer.Rating
import anki.sync.SyncAuth
import anki.sync.syncAuth
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.CollectionFiles
import com.ichi2.anki.libanki.DB
import com.ichi2.anki.libanki.Storage
import com.ichi2.anki.libanki.fullUploadOrDownload
import com.ichi2.anki.libanki.sched.CurrentQueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.ankiweb.rsdroid.database.AnkiSupportSQLiteDatabase
import timber.log.Timber
import java.io.File

/**
 * Unity-facing facade. Every method is `@JvmStatic` so Unity can call it via
 * `AndroidJavaClass("com.spacecardsvr.bridge.SpaceCardsBridge").CallStatic(...)`
 * without instantiating the object on the C# side.
 *
 * Threading: collection and sync methods dispatch onto Dispatchers.IO internally.
 * WebView surface methods MUST be called on the main thread (Unity main thread
 * is acceptable; the bridge posts to Android's main Looper as needed).
 */
object SpaceCardsBridge {
    private val lock = Any()

    @Volatile private var appContext: Context? = null

    @Volatile private var collectionFiles: CollectionFiles? = null

    @Volatile private var collection: Collection? = null

    @Volatile private var currentQueueState: CurrentQueueState? = null

    @Volatile private var surfaceManager: WebViewSurfaceManager? = null

    @Volatile private var touchInjector: TouchInjector? = null

    @Volatile private var cachedAuth: SyncAuth? = null

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val PREFS_NAME = "spacecards-bridge"
    private const val PREF_HKEY = "ankiweb_hkey"
    private const val PREF_ENDPOINT = "ankiweb_endpoint"
    private const val PREF_USERNAME = "ankiweb_username"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DeckListEntry(
        val id: Long,
        val name: String,
    )

    @Serializable
    private data class CountsPayload(
        val new: Int,
        val lrn: Int,
        val rev: Int,
    )

    @Serializable
    private data class CardFrontPayload(
        val cardId: Long,
        val questionHtml: String,
        val mediaBase: String,
        val counts: CountsPayload,
    )

    @Serializable
    private data class CardBackPayload(
        val answerHtml: String,
    )

    @Serializable
    private data class LoginSuccessPayload(
        val username: String,
        val endpoint: String,
    )

    @Serializable
    private data class SyncSuccessPayload(
        val direction: String,
    )

    @JvmStatic
    @AnyThread
    fun init(
        context: Context,
        collectionFolder: String,
    ): Boolean =
        synchronized(lock) {
            try {
                appContext = context.applicationContext
                val folder = File(collectionFolder).apply { mkdirs() }
                collectionFiles = CollectionFiles.FolderBasedCollection(folder)
                Timber.i("SpaceCardsBridge initialised at %s", folder.absolutePath)
                true
            } catch (t: Throwable) {
                Timber.e(t, "SpaceCardsBridge.init failed")
                false
            }
        }

    @JvmStatic
    @AnyThread
    fun openCollection() {
        synchronized(lock) {
            if (collection != null) return
            val files = collectionFiles ?: error("init() not called")
            collection =
                Storage.collection(
                    collectionFiles = files,
                    databaseBuilder = { backend -> DB(AnkiSupportSQLiteDatabase.withRustBackend(backend)) },
                )
            Timber.i("Collection opened")
        }
    }

    @JvmStatic
    @AnyThread
    fun closeCollection() {
        synchronized(lock) {
            collection?.close()
            collection = null
            Timber.i("Collection closed")
        }
    }

    @JvmStatic
    @AnyThread
    fun listDecks(): String {
        val col = requireCollection()
        val entries =
            col.decks.allNamesAndIds().map { DeckListEntry(id = it.id, name = it.name) }
        return json.encodeToString(entries)
    }

    @JvmStatic
    @AnyThread
    fun selectDeck(deckId: Long) {
        synchronized(lock) {
            val col = requireCollection()
            col.decks.select(deckId)
            currentQueueState = null
        }
    }

    @JvmStatic
    @AnyThread
    fun deckCounts(deckId: Long): String =
        synchronized(lock) {
            val col = requireCollection()
            col.decks.select(deckId)
            currentQueueState = null
            val counts = col.sched.counts()
            json.encodeToString(CountsPayload(counts.new, counts.lrn, counts.rev))
        }

    @JvmStatic
    @AnyThread
    fun nextCardJson(): String? =
        synchronized(lock) {
            val col = requireCollection()
            val state = col.sched.currentQueueState()
            if (state == null) {
                currentQueueState = null
                return@synchronized null
            }
            currentQueueState = state
            val card = state.topCard
            val questionHtml = card.question(col)
            val mediaBase = (collectionFiles as? CollectionFiles.FolderBasedCollection)?.mediaFolder?.absolutePath.orEmpty()
            val counts = CountsPayload(state.counts.new, state.counts.lrn, state.counts.rev)
            json.encodeToString(
                CardFrontPayload(
                    cardId = card.id,
                    questionHtml = questionHtml,
                    mediaBase = mediaBase,
                    counts = counts,
                ),
            )
        }

    @JvmStatic
    @AnyThread
    fun showAnswer(): String =
        synchronized(lock) {
            val col = requireCollection()
            val state = currentQueueState ?: error("no card showing; call nextCardJson() first")
            val answerHtml = state.topCard.answer(col)
            json.encodeToString(CardBackPayload(answerHtml = answerHtml))
        }

    @JvmStatic
    @AnyThread
    fun answerCard(ease: Int) {
        synchronized(lock) {
            val col = requireCollection()
            val state = currentQueueState ?: error("no card to answer; call nextCardJson() first")
            val rating =
                when (ease) {
                    1 -> Rating.AGAIN
                    2 -> Rating.HARD
                    3 -> Rating.GOOD
                    4 -> Rating.EASY
                    else -> error("ease must be 1..4 (got $ease)")
                }
            col.sched.answerCard(state, rating)
            currentQueueState = null
        }
    }

    @JvmStatic
    @AnyThread
    fun loginAnkiWeb(
        username: String,
        password: String,
        callback: BridgeCallback,
    ) {
        ioScope.launch {
            try {
                callback.onProgress(0, "contacting AnkiWeb")
                val col = requireCollection()
                val auth = col.syncLogin(username = username, password = password, endpoint = null)
                cachedAuth = auth
                persistAuth(username, auth)
                Timber.i("AnkiWeb login succeeded for %s", username)
                callback.onSuccess(json.encodeToString(LoginSuccessPayload(username, auth.endpoint)))
            } catch (t: Throwable) {
                Timber.e(t, "AnkiWeb login failed")
                callback.onError(code = -1, message = t.message ?: "login failed")
            }
        }
    }

    @JvmStatic
    @AnyThread
    fun fullSync(
        direction: String,
        callback: BridgeCallback,
    ) {
        val upload =
            when (direction) {
                "upload" -> true
                "download" -> false
                else -> {
                    callback.onError(code = -2, message = "direction must be \"upload\" or \"download\" (got \"$direction\")")
                    return
                }
            }
        ioScope.launch {
            try {
                callback.onProgress(0, "starting $direction")
                val col = requireCollection()
                val auth =
                    restoreAuth() ?: run {
                        callback.onError(code = -3, message = "not logged in; call loginAnkiWeb first")
                        return@launch
                    }
                col.fullUploadOrDownload(auth = auth, upload = upload, serverUsn = null)
                Timber.i("Full sync (%s) completed", direction)
                callback.onSuccess(json.encodeToString(SyncSuccessPayload(direction)))
            } catch (t: Throwable) {
                Timber.e(t, "Full sync (%s) failed", direction)
                callback.onError(code = -1, message = t.message ?: "sync failed")
            }
        }
    }

    private fun prefs(): SharedPreferences =
        (appContext ?: error("init() not called")).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistAuth(
        username: String,
        auth: SyncAuth,
    ) {
        prefs()
            .edit()
            .putString(PREF_HKEY, auth.hkey)
            .putString(PREF_ENDPOINT, auth.endpoint)
            .putString(PREF_USERNAME, username)
            .apply()
    }

    private fun restoreAuth(): SyncAuth? {
        cachedAuth?.let { return it }
        val p = prefs()
        val hkey = p.getString(PREF_HKEY, null) ?: return null
        val endpoint = p.getString(PREF_ENDPOINT, "") ?: ""
        val auth =
            syncAuth {
                this.hkey = hkey
                if (endpoint.isNotEmpty()) {
                    this.endpoint = endpoint
                }
            }
        cachedAuth = auth
        return auth
    }

    @JvmStatic
    @MainThread
    fun createCardSurface(
        width: Int,
        height: Int,
        glTextureId: Int,
    ): Long {
        val manager = requireSurfaceManager()
        return manager.create(width, height, glTextureId)
    }

    @JvmStatic
    @MainThread
    fun renderCard(
        surfaceHandle: Long,
        html: String,
        css: String,
    ) {
        requireSurfaceManager().render(surfaceHandle, html, css)
    }

    @JvmStatic
    @AnyThread
    fun injectTouch(
        surfaceHandle: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        requireTouchInjector().inject(surfaceHandle, action, x, y)
    }

    @JvmStatic
    @MainThread
    fun destroyCardSurface(surfaceHandle: Long) {
        surfaceManager?.destroy(surfaceHandle)
    }

    private fun requireSurfaceManager(): WebViewSurfaceManager =
        surfaceManager ?: synchronized(lock) {
            surfaceManager ?: run {
                val ctx = appContext ?: error("init() not called")
                WebViewSurfaceManager(ctx).also { surfaceManager = it }
            }
        }

    private fun requireTouchInjector(): TouchInjector =
        touchInjector ?: synchronized(lock) {
            touchInjector ?: TouchInjector(requireSurfaceManager()).also { touchInjector = it }
        }

    private fun requireCollection(): Collection =
        collection ?: synchronized(lock) {
            collection ?: error("collection is not open; call openCollection() first")
        }
}
