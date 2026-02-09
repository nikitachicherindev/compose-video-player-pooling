package dev.nikitachicherin.videopooling.ui.videoplayer

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.nikitachicherin.videopooling.exoplayer.VideoPreviewMedia3Factories
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ExoPlayerPool"

/**
 * A tiny "borrow/return" pool for [ExoPlayer] instances.
 *
 * Why pool players at all?
 * - Creating an [ExoPlayer] is not free: it allocates renderers, creates internal threads, warms up
 *   codecs, etc.
 * - In a Compose list, items enter/leave composition constantly while you scroll.
 * - If each item creates/destroys its own player, you get jank, long start times, and a lot of churn.
 *
 * Pool contract:
 * - [acquire]/[acquireOrWait] hands out a player that is *owned by the pool*
 * - caller must call [release] when it is done (usually in a `finally` block)
 * - the pool enforces "no playback leakage": every player is stopped/cleared before returning
 *
 * Threading:
 * - all methods are main-thread only (ExoPlayer has strict threading expectations)
 *
 * This is intentionally demo-simple:
 * - it grows up to [maxSize]
 * - it does not shrink while the screen is alive
 * - players are destroyed only when [releaseAll] is called on screen dispose
 */
internal class ExoPlayerPool(
    private val appContext: Context,
    private val maxSize: Int,
    private val createPlayer: (Context) -> ExoPlayer
) {
    private val available = ArrayDeque<ExoPlayer>()
    private val inUse = LinkedHashSet<ExoPlayer>()
    private var totalCreated: Int = 0

    /**
     * Notifies waiting callers that something changed (a player was released or the pool was disposed).
     * Conflated is enough: we only need “wake up and re-check”.
     */
    private val signal = Channel<Unit>(capacity = Channel.CONFLATED)

    private var disposed: Boolean = false

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ExoPlayerPool must be used on the main thread"
        }
    }

    /**
     * Attempts to get a player immediately.
     *
     * Returns:
     * - a player (reused or newly created), or
     * - null if pool is exhausted or disposed
     */
    fun acquire(): ExoPlayer? {
        checkMainThread()

        if (disposed) {
            Log.w(TAG, "acquire(): ignored (disposed=true)")
            return null
        }

        available.removeFirstOrNull()?.let { reused ->
            inUse += reused
            Log.d(
                TAG,
                "acquire(): reused=true player=${reused.hashCode()} available=${available.size} inUse=${inUse.size} total=$totalCreated"
            )
            return reused
        }

        if (totalCreated >= maxSize) {
            Log.w(TAG, "acquire(): exhausted (maxSize=$maxSize). available=0 inUse=${inUse.size}")
            return null
        }

        val created = createPlayer(appContext)
        totalCreated++
        inUse += created
        Log.d(
            TAG,
            "acquire(): reused=false player=${created.hashCode()} available=${available.size} inUse=${inUse.size} total=$totalCreated"
        )
        return created
    }

    /**
     * Suspends until a player is available, then returns it.
     *
     * This is what allows list items to say:
     * - “if I'm active, I *must* get a player eventually”
     * while still respecting the pool size limit.
     *
     * Disposal:
     * - if the pool is disposed while waiting, we throw [CancellationException]
     */
    suspend fun acquireOrWait(): ExoPlayer {
        checkMainThread()

        acquire()?.let { return it }

        Log.d(TAG, "acquireOrWait(): waiting... maxSize=$maxSize inUse=${inUse.size}")

        while (true) {
            if (disposed) throw CancellationException("ExoPlayerPool disposed")

            val received = signal.receiveCatching()
            if (!received.isSuccess) throw CancellationException("ExoPlayerPool disposed")

            acquire()?.let {
                Log.d(TAG, "acquireOrWait(): woke up -> got player=${it.hashCode()}")
                return it
            }
        }
    }

    /**
     * Returns a player to the pool.
     *
     * Important cleanup:
     * - stop playback
     * - clear media items
     * - clear the video surface
     *
     * That last part is critical in a list:
     * - views can be recycled
     * - we don't want a player to keep rendering into an old surface
     */
    fun release(player: ExoPlayer) {
        checkMainThread()

        if (player !in inUse) {
            Log.w(TAG, "release(): ignored (player not in use) player=${player.hashCode()}")
            return
        }
        inUse -= player

        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.clearVideoSurface()

        if (disposed) {
            player.release()
            totalCreated = (totalCreated - 1).coerceAtLeast(0)
            Log.d(
                TAG,
                "release(): disposed=true destroyed player=${player.hashCode()} inUse=${inUse.size} total=$totalCreated"
            )
            return
        }

        available.addLast(player)
        Log.d(
            TAG,
            "release(): pooled=true player=${player.hashCode()} available=${available.size} inUse=${inUse.size} total=$totalCreated"
        )

        signal.trySend(Unit)
    }

    /**
     * Disposes the pool.
     *
     * Important subtlety:
     * - We *only* release players that are currently idle in [available].
     * - Players that are still "in-use" are still owned by controllers that may be in the middle of
     *   a `finally { ... }` block. Releasing them here would risk touching a released player.
     * - Instead, once [disposed] is true, [release] will destroy any returned player immediately.
     *
     * Called by [rememberExoPlayerPool] when the screen leaves composition.
     */
    fun releaseAll() {
        checkMainThread()

        if (disposed) return
        disposed = true

        Log.i(
            TAG,
            "releaseAll(): disposing pool. available=${available.size} inUse=${inUse.size} total=$totalCreated"
        )

        // Unblock any coroutine waiting in acquireOrWait() so it can see disposed and throw.
        signal.close()

        // Safe: only release idle players.
        // In-use players will be released when returned via release(player) because disposed == true.
        available.forEach { it.release() }
        available.clear()

        Log.i(TAG, "releaseAll(): disposed=true. Waiting for inUse=${inUse.size} to be returned.")
        // Do NOT clear inUse here; it’s still owned by controllers that may be mid-cleanup.
    }
}

/**
 * Creates an [ExoPlayer] configured specifically for fast-starting silent previews.
 *
 * The defaults in Media3 are great for a full player, but for a feed preview we usually want:
 * - smaller video constraints (less bandwidth + cheaper decode)
 * - audio disabled (previews are silent)
 * - shorter buffers (start quickly; accept occasional rebuffering in a demo)
 * - a stable MediaSource/DataSource stack (see [VideoPreviewMedia3Factories])
 */
@OptIn(UnstableApi::class)
fun buildPreviewPlayer(context: Context): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setMaxVideoSize(848, 480)
                .setExceedVideoConstraintsIfNecessary(false)
                .setForceLowestBitrate(false)
                .setMaxVideoBitrate(1_200_000) // ~1.2 Mbps cap
                .setRendererDisabled(C.TRACK_TYPE_AUDIO, true)
        )
    }

    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 1_500,
            /* maxBufferMs = */ 6_000,
            /* bufferForPlaybackMs = */ 300,
            /* bufferForPlaybackAfterRebufferMs = */ 600
        )
        .build()

    val mediaSourceFactory = VideoPreviewMedia3Factories.buildMediaSourceFactory(context)

    val player = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply { repeatMode = Player.REPEAT_MODE_OFF }

    Log.d(TAG, "buildPreviewPlayer(): created player=${player.hashCode()}")
    return player
}

/**
 * Creates (and remembers) a single [ExoPlayerPool] for the current Compose screen.
 *
 * Why this wrapper exists:
 * - pooling only works when *multiple list items share the same pool*
 * - it is easy to accidentally create one pool per item if you `remember` inside each tile
 *
 * Lifecycle:
 * - the pool is tied to composition
 * - when the composable leaves composition, [ExoPlayerPool.releaseAll] runs
 */
@Composable
internal fun rememberExoPlayerPool(maxSize: Int): ExoPlayerPool {
    val appContext = LocalContext.current.applicationContext
    val pool = remember(maxSize) {
        ExoPlayerPool(appContext, maxSize) { ctx -> buildPreviewPlayer(ctx) }
    }
    DisposableEffect(pool) { onDispose { pool.releaseAll() } }
    return pool
}
