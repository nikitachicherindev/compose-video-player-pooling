package dev.nikitachicherin.videopooling.ui.videoplayer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * Chooses which list items are "allowed" to play video right now.
 *
 * In a typical preview carousel you do **not** want every visible cell to spin up playback:
 * - decoding multiple videos at once is expensive
 * - multiple [androidx.media3.ui.compose.PlayerSurface] surfaces compete for GPU resources
 * - network usage explodes when the user scrolls quickly
 *
 * This helper returns a small [Set] of indices, usually "the item closest to the viewport center"
 * plus a couple of neighbors, limited by [maxActive].
 *
 * Compose note:
 * - This is pure derived state (no side effects). It can re-evaluate on every scroll frame.
 * - For tiny carousels you could just sort the visible list by distance and take K.
 *   But sorting allocates intermediate lists (`sortedBy/take/map/toSet`) every frame.
 * - Instead, we do a small "top-K by distance" selection in a single pass over visible items.
 *
 * Policy note:
 * - "closest to center" is just a policy. The pool doesn't care.
 *   You can replace this with "largest visible area", "fully visible only", "snap-based", etc.
 */
@Composable
fun rememberActiveIndicesForVideo(
    listState: LazyListState,
    maxActive: Int
): Set<Int> {
    return remember(listState, maxActive) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo

            // Nothing visible or we're configured to not play anything.
            if (visible.isEmpty() || maxActive <= 0) return@derivedStateOf emptySet()

            // We rank visible items by how close their center is to the viewport center.
            // Smaller distance => higher priority => more likely to "deserve" a player.
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2

            // "best" holds at most K pairs of (distance, index).
            //
            // Once it is full, we maintain an important invariant:
            //   best[0] is the *worst* item among the current best K
            // (i.e. the largest distance). That makes replacement easy:
            //   if a new item is closer than best[0], it belongs in the top K.
            val best = ArrayList<Pair<Int, Int>>(maxActive) // (distance, index)

            for (item in visible) {
                val itemCenter = item.offset + item.size / 2
                val dist = abs(itemCenter - viewportCenter)

                if (best.size < maxActive) {
                    // Still filling the initial K slots: just append.
                    best.add(dist to item.index)

                    // Once we reach K items, sort descending by distance so that:
                    //   best[0] is the current "worst of the best" (largest distance).
                    if (best.size == maxActive) {
                        best.sortByDescending { it.first } // largest distance first
                    }
                } else if (dist < best[0].first) {
                    // We already have K items, and this one is better (closer)
                    // than the current worst-of-best -> replace the head.
                    best[0] = dist to item.index

                    // Re-sort to restore the invariant that best[0] stays the worst.
                    // K is tiny (2 in this demo), so this is cheap and allocation-free.
                    best.sortByDescending { it.first }
                }
                // Else: this item is farther than our current top-K -> ignore.
            }

            // Return the chosen indices.
            // Note: Set order doesn't matter; the caller treats this as membership ("active or not").
            best.map { it.second }.toSet()
        }
    }.value
}