package dev.nikitachicherin.videopooling.ui.videoplayer

import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import kotlinx.collections.immutable.ImmutableList

private const val TAG = "VideoContainer"

/**
 * One “tile” in the carousel.
 *
 * Responsibilities:
 * - owns a [VideoController] (the state machine for playing previews)
 * - hosts a Media3 Player with [PlayerSurface]
 * - connects Compose lifecycle events (pause/resume) to the controller
 *
 * The tile itself does *not* create an [androidx.media3.exoplayer.ExoPlayer].
 * It only borrows one from [ExoPlayerPool] when [isActive] is true.
 *
 * UI layering:
 * - while preparing or before first frame, we show the placeholder text
 * - after first frame, we cross-fade the PlayerSurface in
 */
@Composable
internal fun VideoContainer(
    modifier: Modifier = Modifier,
    text: String,
    videoUrls: ImmutableList<String>,
    isActive: Boolean,
    playerPool: ExoPlayerPool,
) {
    val controller = remember(playerPool) { VideoController(playerPool) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        Log.d(TAG, "lifecycle observer attached isActive=$isActive text=$text")
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> controller.onPause()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            Log.d(TAG, "lifecycle observer removed text=$text")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Demo note:
    // This restarts the controller coroutine whenever `isActive` changes.
    // It's fine for a small demo, but in a production feed you may prefer a single LaunchedEffect(controller)
    // with snapshotFlow { isActive } and a debounce policy to reduce churn.
    LaunchedEffect(controller, isActive, videoUrls) {
        Log.d(TAG, "run(): start isActive=$isActive urls=${videoUrls.size} text=$text")
        controller.run(isActive = isActive, videoUrls = videoUrls)
        Log.d(TAG, "run(): end isActive=$isActive text=$text")
    }

    val targetAlpha =
        if (controller.layer == Layer.VIDEO && controller.videoHasFirstFrame) 1f else 0f

    val videoAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = ANIM_MS.toInt(), easing = FastOutSlowInEasing),
        label = "videoAlpha"
    )

    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.outline, shape)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp),
                color = colors.onSurface,
            )
        }

        PlayerSurface(
            modifier = modifier
                .fillMaxSize()
                .alpha(videoAlpha),
            player = controller.player,
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW
        )
    }
}
