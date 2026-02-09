package dev.nikitachicherin.videopooling.ui.videoplayer

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.nikitachicherin.videopooling.ui.videoplayer.Layer.TEXT
import dev.nikitachicherin.videopooling.ui.videoplayer.Layer.VIDEO
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Which UI layer should be visible in the tile.
 *
 * - [TEXT]  -> placeholder text is shown
 * - [VIDEO] -> PlayerSurface is shown (only after we got a first frame)
 */
internal enum class Layer { TEXT, VIDEO }

private const val TAG = "VideoController"
private const val PLAY_MS = 10_000L
private const val PAUSE_MS = 3_000L
internal const val ANIM_MS = 500L
private const val FIRST_FRAME_TIMEOUT_MS = 8_000L

/**
 * State machine that runs inside a single tile and “drives” preview playback.
 *
 * Core responsibilities:
 * 1) Borrow an [ExoPlayer] from [ExoPlayerPool] when the tile becomes active.
 * 2) Hold the borrowed player in Compose state so [androidx.media3.ui.compose.PlayerSurface] can render it
 * 3) For each slot:
 *    - set media item
 *    - prepare
 *    - start playback and wait for `onRenderedFirstFrame`
 *    - show video for a short time
 *    - stop/clear and loop to the next URL
 * 4) Enforce a hard guarantee: **no playback continues when the app is backgrounded**.
 *
 * Why waiting for first frame:
 * - media preparation can succeed but rendering can still be delayed
 * - the UI cross-fade should happen only when we *know* the video is actually visible
 *
 * Why stop + clear after every slot:
 * - demo simplicity
 * - returning to a consistent baseline avoids subtle state leaks between tiles
 */
internal class VideoController(
    private val playerPool: ExoPlayerPool
) {
    var layer by mutableStateOf(TEXT)
        private set

    var preparingVideo by mutableStateOf(false)
        private set

    var videoHasFirstFrame by mutableStateOf(false)
        private set

    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    /** Set to true when the hosting lifecycle goes to background. */
    private val pausedByLifecycle = MutableStateFlow(false)

    /** Remember whether we should resume playback after a lifecycle resume. */
    private var shouldResumeOnResume = false

    fun onPause() {
        pausedByLifecycle.value = true

        val p = player ?: run {
            Log.d(TAG, "onPause(): no player")
            return
        }

        shouldResumeOnResume = preparingVideo || (layer == VIDEO && videoHasFirstFrame)

        p.playWhenReady = false
        p.pause()

        Log.d(
            TAG,
            "onPause(): paused player=${p.hashCode()} shouldResumeOnResume=$shouldResumeOnResume"
        )
    }

    fun onResume() {
        pausedByLifecycle.value = false

        val p = player ?: run {
            Log.d(TAG, "onResume(): no player")
            return
        }

        if (shouldResumeOnResume) {
            p.playWhenReady = true
            p.play()
            Log.d(TAG, "onResume(): resumed player=${p.hashCode()}")
        } else {
            Log.d(TAG, "onResume(): nothing to resume player=${p.hashCode()}")
        }
    }

    /**
     * Main loop for a tile.
     *
     * Called from a Compose coroutine ([androidx.compose.runtime.LaunchedEffect] in [VideoContainer]).
     * Cancelling this coroutine is the primary way items stop doing work when they leave composition.
     */
    suspend fun run(isActive: Boolean, videoUrls: ImmutableList<String>) {
        if (!isActive || videoUrls.isEmpty()) {
            resetUiToPlaceholder()
            return
        }

        val p = try {
            playerPool.acquireOrWait()
        } catch (e: CancellationException) {
            // IMPORTANT:
            // Don't swallow CancellationException. We can do a tiny bit of local cleanup,
            // but must rethrow so structured concurrency works correctly (parent scopes
            // observe cancellation).
            resetUiToPlaceholder()
            throw e
        }

        player = p
        Log.d(TAG, "run(): acquired player=${p.hashCode()} urls=${videoUrls.size}")

        try {
            var index = 0L
            while (currentCoroutineContext().isActive) {
                awaitForeground()

                val url = videoUrls[(index % videoUrls.size).toInt()]
                index++

                Log.d(TAG, "slot(): start player=${p.hashCode()} url=$url")
                val ok = playVideoSlot(p, url)
                Log.d(TAG, "slot(): end player=${p.hashCode()} ok=$ok")

                if (ok) longPause() else delayWhileForeground(200)
            }
        } finally {
            p.playWhenReady = false
            p.pause()

            player = null
            resetUiToPlaceholder()

            playerPool.release(p)
            Log.d(TAG, "run(): released player=${p.hashCode()}")
        }
    }

    /**
     * Suspends while the app is backgrounded, but keeps responding to coroutine cancellation.
     */
    private suspend fun awaitForeground() {
        if (!pausedByLifecycle.value) return
        pausedByLifecycle
            .filter { paused -> !paused }
            .first()
    }

    /**
     * Delay helper used by the demo "slot timer".
     *
     * We first suspend while the app is backgrounded ([awaitForeground]), then delay.
     *
     * Note: this is intentionally demo-simple. If the app goes to background *during* the delay,
     * this delay will keep running (because it's just a coroutine delay). If you need “background
     * time doesn’t count” semantics in a production feed, implement this as a lifecycle-aware
     * timer (e.g., step the delay in small chunks and re-check foreground, or drive time from a
     * ticker that's paused on background).
     */
    /**
     * Delay that only counts time while the app is in foreground.
     *
     * Steps in small chunks and re-checks [pausedByLifecycle] after each chunk.
     * If the app goes to background during the delay, we suspend on [awaitForeground()]
     * and do not count that time toward the total.
     */
    private suspend fun delayWhileForeground(totalMs: Long) {
        val total = totalMs.coerceAtLeast(0L)
        if (total == 0L) return

        val chunkMs = 50L
        var remaining = total

        while (remaining > 0 && currentCoroutineContext().isActive) {
            awaitForeground()
            val step = minOf(chunkMs, remaining)
            delay(step)
            remaining -= step
        }
    }

    /**
     * Starts playback and waits until the first video frame is rendered.
     *
     * The listener is attached *before* starting playback to avoid missing the callback.
     */
    private suspend fun playAndAwaitFirstFrame(player: ExoPlayer): Boolean {
        return withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                lateinit var listener: Player.Listener

                fun finish(ok: Boolean) {
                    player.removeListener(listener)
                    if (!cont.isCompleted) cont.resume(ok)
                }

                listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() = finish(true)
                    override fun onPlayerError(error: PlaybackException) {
                        Log.d(
                            TAG,
                            "firstFrame(): error player=${player.hashCode()} msg=${error.message}"
                        )
                        finish(false)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) finish(false)
                    }
                }

                player.addListener(listener)
                cont.invokeOnCancellation { player.removeListener(listener) }

                player.playWhenReady = true
                player.play()
            }
        } == true
    }

    /**
     * Resets both UI state and player state after a failed attempt.
     */
    private fun cleanupAfterFailure(player: ExoPlayer) {
        resetUiToPlaceholder()

        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    /**
     * Plays one URL for a short slot and then resets the player for the next URL.
     */
    private suspend fun playVideoSlot(player: ExoPlayer, url: String): Boolean {
        awaitForeground()

        layer = TEXT
        preparingVideo = true
        videoHasFirstFrame = false

        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()

        // If we got paused in the middle of preparing, do not re-enable playback.
        if (pausedByLifecycle.value || !currentCoroutineContext().isActive) {
            player.playWhenReady = false
            player.pause()
            preparingVideo = false
            Log.d(
                TAG,
                "slot(): aborted during prepare pausedByLifecycle=${pausedByLifecycle.value}"
            )
            return false
        }

        val hasFirstFrame = playAndAwaitFirstFrame(player)
        if (!hasFirstFrame || !currentCoroutineContext().isActive || pausedByLifecycle.value) {
            Log.d(
                TAG,
                "slot(): no first frame or cancelled. hasFirstFrame=$hasFirstFrame pausedByLifecycle=${pausedByLifecycle.value}"
            )
            cleanupAfterFailure(player)
            return false
        }

        preparingVideo = false
        videoHasFirstFrame = true
        layer = VIDEO
        Log.d(TAG, "slot(): first frame rendered -> showing video")

        delayWhileForeground((PLAY_MS - ANIM_MS).coerceAtLeast(0L))

        layer = TEXT
        delayWhileForeground(ANIM_MS)

        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()

        preparingVideo = false
        videoHasFirstFrame = false
        return true
    }

    /**
     * Long pause between slots to make the demo obviously "looping".
     */
    private suspend fun longPause() {
        resetUiToPlaceholder()
        delayWhileForeground(PAUSE_MS)
    }

    private fun resetUiToPlaceholder() {
        layer = TEXT
        preparingVideo = false
        videoHasFirstFrame = false
    }
}
