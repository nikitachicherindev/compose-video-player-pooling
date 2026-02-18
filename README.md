# Compose Video Preview Pooling Demo

## Intro

A small experimental Android app that demonstrates a technique for **reusing (“pooling”)
Media3 `ExoPlayer` instances**
in a Jetpack Compose list so that video previews start quickly and scrolling feels smooth.

The key idea is **pooling players** and **assigning them only to a small set of currently “active”
cells**
(e.g., the visible cells closest to the viewport center).

This is an experiment / educational demo — not production-ready. See **Known limitations** near the
end.

## Problem

A naïve implementation often does something like this per list item:

- create an `ExoPlayer`
- create a `PlayerSurface`
- prepare media
- dispose everything when the item leaves composition

In a scrolling list that means:

- constant player creation/destruction (expensive)
- codec churn and thread churn
- more GC pressure and jank
- worse “time-to-first-frame” for each item as you scroll back and forth

## Core idea

Treat `ExoPlayer` instances as **reusable resources**:

1. The list owns a small **player pool** (e.g. 2 players).
2. The list computes which item indices are currently “active” (e.g. closest-to-center).
3. Only active items may borrow a player.
4. When an item becomes inactive (or is disposed), it **returns** the player to the pool.
5. When a player is returned, the pool **hard-resets** it (stop, clear media, detach surface,
   meaning that no decoder/surface state leaks between cells) so the next borrower starts clean.

This way, scrolling mostly reuses already-initialized `ExoPlayer`s instead of re-creating them.

Additionally, this demo:

- pre-warms the shared Media3 disk cache off the UI thread
- pre-creates pooled players gradually on first composition
- creates `PlayerSurface` only for active (or currently attached) tiles
- avoids an intermediate allocation in active-index selection (`HashSet` fill vs `map(...).toSet()`)

## What to read in the code

Start here (in recommended order):

1. `ui/videoplayer/VideoRow.kt`  
   Owns the pool and decides which items are active.
2. `ui/videoplayer/ActiveIndices.kt`  
   Computes the active indices (closest-to-center + neighbors).
3. `ui/videoplayer/ExoPlayerPool.kt`  
   The pool implementation + the “preview player” configuration.
4. `ui/videoplayer/VideoContainer.kt`  
   A single tile: hosts a `PlayerSurface` and wires lifecycle events.
5. `ui/videoplayer/VideoController.kt`  
   The per-tile state machine: acquire → play slot → release.
6. `exoplayer/VideoPreviewMedia3Factories.kt` and `exoplayer/VideoPreviewCache.kt`  
   A simple disk cache + factory wiring so all players share the same I/O stack.

## How it works (high-level)

- `VideoRow` builds **one** `ExoPlayerPool(maxSize = MAX_PLAYERS)` for the whole list.
- `rememberActiveIndicesForVideo()` picks up to `MAX_PLAYERS` indices that are allowed to play. They
  are capped at MAX_PLAYERS, so at most that many tiles will ever try to play concurrently.
- Each `VideoContainer` creates one `VideoController` (cheap) and calls `controller.run(...)` in a
  coroutine.
- If the tile is active, the controller suspends until it can `acquireOrWait()` a pooled
  `ExoPlayer`.
- The controller provides the player to tile's `PlayerSurface` and starts preview playback.
- When the tile becomes inactive or leaves composition, the coroutine is cancelled and the
  controller returns the player to the pool.

## Algorithm

### 1) Selecting active items

`rememberActiveIndicesForVideo()`:

1. reads `LazyListState.layoutInfo.visibleItemsInfo`
2. computes the viewport center in pixels
3. computes each item’s distance to that center
4. selects the best `maxActive` items using a tiny “top-K” pass (no full sort / no big allocations)
5. returns their indices as `Set<Int>`

### 2) Borrow/return

`ExoPlayerPool`:

- `acquire()` returns a free player if available, otherwise creates a new one (until `maxSize`)
- `acquireOrWait()` suspends if pool is exhausted until someone returns a player
- `release()` stops playback, clears media items, detaches the surface, and returns the player to
  the queue
- `prewarm()` can gradually create up to `maxSize` players ahead of first demand

### 3) Per-tile playback loop

`VideoController.run()`:

1. if inactive → show placeholder and exit
2. else acquire a player (may suspend)
3. loop:
    - wait for foreground (demo guarantees no background playback)
    - set a URL, prepare, start playback
    - wait for `onRenderedFirstFrame` (with timeout)
    - show video briefly, then stop/clear and move to next URL
4. finally: pause, detach, reset UI, and release to pool

## Tuning knobs

These are the main “feel vs. resource usage” controls:

### Pool / activity budget

- `ui/videoplayer/VideoRow.kt`
    - `MAX_PLAYERS` (how many simultaneous decoders you allow)
- `ui/videoplayer/ActiveIndices.kt`
    - active index selection strategy (currently closest-to-center)

### Player configuration

- `ui/videoplayer/ExoPlayerPool.kt` → `buildPreviewPlayer()`
    - video constraints (`setMaxVideoSize`, `setMaxVideoBitrate`)
    - audio disabled (`setRendererDisabled(TRACK_TYPE_AUDIO, true)`)
    - buffer durations (`DefaultLoadControl.Builder().setBufferDurationsMs(...)`)

### Slot timing / UX

- `ui/videoplayer/VideoController.kt`
    - `PLAY_MS`, `PAUSE_MS` (how long each preview plays / pauses)
    - `FIRST_FRAME_TIMEOUT_MS` (how long we wait before declaring a slot “failed”)
    - `ANIM_MS` (cross-fade duration)

### Cache

- `exoplayer/VideoPreviewCache.kt`
    - `CACHE_MAX_BYTES` (preview cache size cap)
    - `warmUp(context)` initializes `SimpleCache` on `Dispatchers.IO`

### Warmup path

- `ui/videoplayer/ExoPlayerPool.kt` → `rememberExoPlayerPool()`
    - runs one warmup effect:
    - `VideoPreviewCache.warmUp(appContext)` (cache bootstrap off main thread)
    - `pool.prewarm(targetCount = maxSize)` (gradual player pre-creation)

## Logging

Useful tags:

- `VideoRow` — active index changes (who is allowed to play)
- `ExoPlayerPool` — acquire/release/wait behavior and pool size
- `VideoController` — slot lifecycle: acquire → prepare → first frame → release
- `VideoContainer` — PlayerSurface + lifecycle observer wiring
- `VideoPreviewCache` — cache creation/release
- `VideoPreviewFactories` — factory creation (and shared cache usage)

### Crossfading previews while using `SURFACE_TYPE_SURFACE_VIEW`

`PlayerSurface` uses a `SurfaceView` by default (`SURFACE_TYPE_SURFACE_VIEW`). A `SurfaceView` is
rendered in a separate surface layer, which means some Compose effects applied *to the video layer*
(e.g. `Modifier.alpha(...)`, shape clipping, certain transforms) may not behave like normal UI.

Instead of animating the video itself, this demo animates a normal Compose **placeholder overlay**
that sits *above* the video:

- `PlayerSurface(surfaceType = SURFACE_TYPE_SURFACE_VIEW)` is created only when
  `isActive || controller.player != null`
- a full-size placeholder `Box` (with a solid background + text) is drawn on top
- once `VideoController` reports `videoHasFirstFrame = true`, we animate the placeholder’s alpha
  from `1f → 0f`

This gives a reliable “crossfade” effect while still benefiting from `SurfaceView` performance
characteristics. Keep in mind that the overlay should be **opaque** (background + content) while
visible, so it fully
hides black frames or previous content before the first frame renders.

## Known limitations (intentionally left “demo-simple”)

This is a demo, so some production concerns are intentionally not addressed:

- No ViewModel / paging / lifecycle-aware UI state (kept local for clarity).
- Warmup is intentionally simple (single startup effect). It does not yet include scroll-aware
  predictive prefetching.
- No “snap-to-page” behavior; active item selection is closest-to-center and may flicker at
  boundaries.
- Error handling is minimal; a broken URL just results in a failed slot and retry on next loop.
- `PlayerSurface` only instantiated for active/attached tiles; this reduces inactive surface
  overhead but does not implement advanced surface reuse strategies.
- Audio renderer is disabled via track selector, but the audio renderer is still instantiated (for a
  truly minimal player, you’d customize the renderers factory).
- The pool does not shrink while the screen is alive (by design to avoid churn).

## License / Attribution

No license granted, this repository is intended as a minimal educational demo.

## Notes & Caveats

- **Demo-first, not production-ready.** The goal is a clear mental model (ownership + limited active
  set), not a drop-in library.
- **Active-set cap matches pool cap.** The activation policy returns at most `MAX_PLAYERS` indices,
  and the pool size is also `MAX_PLAYERS`. That contract means you don't end up with a "third"
  active tile waiting for a player.
- **Cancellation is propagated.** When the controller coroutine is cancelled (e.g., `LaunchedEffect`
  key changes), we may do tiny local cleanup, but we rethrow `CancellationException` so structured
  concurrency behaves correctly.
- **Lifecycle gating uses suspension, not polling.** The controller uses a `StateFlow`-backed flag
  to suspend while backgrounded instead of waking up every 100ms.
- **Pool disposal avoids releasing in-use players.** `releaseAll()` only releases idle players;
  in-use players are released when returned (once `disposed=true`) to avoid touching a released
  player from a controller `finally` block.
- **Compose identity matters.** Use stable `key = { item.id }` in `LazyRow` so "who owns a player"
  maps to stable item identity.
- **SurfaceView vs TextureView.** This demo uses `SurfaceView` and fades a Compose overlay instead
  of animating the video layer. `TextureView` behaves more like normal UI (alpha/clipping), but can
  cost more memory and isn’t always suitable for DRM/secure output.
- **Audio is actually disabled.** The preview player disables the audio renderer via the track
  selector (`setRendererDisabled(C.TRACK_TYPE_AUDIO, true)`), not just muting.
- **Cold start still exists.** Pooling helps during scroll, but the first time the pool is cold
  players must still be created. If you care about first impression, pre-warm 1–2 players off the
  scroll path.
- **Logging.** This demo logs heavily for learning/debugging. In real apps, guard logs (e.g.,
  `BuildConfig.DEBUG`) or use a logging framework.
