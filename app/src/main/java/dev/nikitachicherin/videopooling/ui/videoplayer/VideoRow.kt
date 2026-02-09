package dev.nikitachicherin.videopooling.ui.videoplayer

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private const val TAG = "VideoRow"

/**
 * How many ExoPlayers we allow to exist (and thus decode) at the same time.
 *
 * This number is the "hard budget" for the whole carousel:
 * - [rememberActiveIndicesForVideo] selects at most this many indices
 * - those indices are allowed to borrow a player from the pool
 * - all other items show placeholder UI only
 *
 * In this demo the tiles are ~75% of the screen width, so you typically see 2 at a time.
 */
internal const val MAX_PLAYERS = 2

/**
 * Minimal UI model for the demo list.
 */
internal data class VideoItem(
    val id: String,
    val text: String,
    val videos: ImmutableList<String>
)

/**
 * Demo content URLs.
 */
private val videos = persistentListOf(
    "https://maitv-vod.lab.eyevinn.technology/VINN.mp4/master.m3u8",
    "https://test-streams.mux.dev/test_001/stream.m3u8",
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/adv_dv_atmos/main.m3u8",
)
private val DEMO_ITEMS = listOf(
    videos,
    persistentListOf(videos[1], videos[0], videos[2]),
    persistentListOf(videos[0], videos[2], videos[1]),
    // Intentionally broken to exercise the error handling
    persistentListOf("https://definitelywrongurl.com/player.m3u8", videos[2], videos[0]),
    persistentListOf(videos[2], videos[0], videos[1]),
    persistentListOf(videos[1], videos[2], videos[0]),
    persistentListOf(videos[2], videos[1], videos[0]),
).mapIndexed { index, videoOrder ->
    VideoItem(
        id = (index + 1).toString(),
        text = "Placeholder item ${index + 1}",
        videos = videoOrder
    )
}

/**
 * Horizontal carousel that demonstrates the entire technique.
 *
 * Key idea:
 * - the *row* owns exactly one [ExoPlayerPool]
 * - each tile asks “am I active?” and only then borrows a player
 * - at most [MAX_PLAYERS] tiles are active at any time
 *
 * This keeps scroll smooth because:
 * - we avoid creating/destroying players as items enter/leave composition
 * - we keep codec/network state warm in the pool
 */
@Composable
internal fun VideoRow(
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    val playerPool = rememberExoPlayerPool(maxSize = MAX_PLAYERS)

    val activeIndices = rememberActiveIndicesForVideo(
        listState = listState,
        maxActive = MAX_PLAYERS
    )

    // Logging here (outside derivedStateOf) keeps log volume reasonable while scrolling.
    var previousActive by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(activeIndices) {
        if (activeIndices != previousActive) {
            Log.d(TAG, "activeIndices: $previousActive -> $activeIndices")
            previousActive = activeIndices
        }
    }

    LazyRow(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = DEMO_ITEMS,
            key = { _, item -> item.id },
            contentType = { _, _ -> "video_tile" },
        ) { index, item ->
            val isActive = index in activeIndices

            VideoContainer(
                modifier = Modifier
                    .fillParentMaxWidth(0.75f)
                    .aspectRatio(1.7777778f), // 16:9 (in real code prefer 16f/9f)
                text = item.text,
                videoUrls = item.videos,
                isActive = isActive,
                playerPool = playerPool,
            )
        }
    }
}

@Preview
@Composable
private fun VideoRowPreview() {
    VideoRow()
}
