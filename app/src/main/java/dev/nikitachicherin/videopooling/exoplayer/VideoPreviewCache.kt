package dev.nikitachicherin.videopooling.exoplayer

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "VideoPreviewCache"

/**
 * Disk cache used by the *preview* players in this demo.
 *
 * What this cache does:
 * - stores downloaded media segments on disk (Media3 [SimpleCache])
 * - makes “scroll away → scroll back” feel instant because the next player hit can read from disk
 *
 * Why this object exists (and why it's a singleton):
 * - [SimpleCache] requires **exclusive** access to its directory.
 * - Creating multiple [SimpleCache] instances pointing at the same folder is a very common way
 *   to crash with a `CacheException`.
 *
 * Why a dedicated directory:
 * - [Context.getCacheDir] is shared by the whole app (image loaders, SDK caches, etc.)
 * - [SimpleCache] assumes it owns its directory and writes its own index files
 * - so we reserve a subfolder just for video previews
 *
 */
@UnstableApi
internal object VideoPreviewCache {

    /**
     * Max cache size
     */
    private const val CACHE_MAX_BYTES: Long = 200L * 1024L * 1024L // 200MB

    /** Folder name under [Context.getCacheDir] reserved for preview cache. */
    private const val CACHE_DIR_NAME = "video_preview_cache"

    private var cache: SimpleCache? = null

    /**
     * Returns the process-wide [SimpleCache] instance, creating it on first use.
     *
     * Threading:
     * - synchronized to avoid double initialization
     *
     * Context:
     * - always uses [Context.getApplicationContext] to avoid leaking Activities
     */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun get(context: Context): SimpleCache {
        cache?.let {
            Log.d(TAG, "get(): reuse existing cache dir=${it.cacheSpace}B")
            return it
        }

        val appContext = context.applicationContext
        val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)

        val evictor = LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES)
        val dbProvider: DatabaseProvider = StandaloneDatabaseProvider(appContext)

        Log.d(
            TAG,
            "get(): creating SimpleCache dir=${cacheDir.absolutePath} maxBytes=$CACHE_MAX_BYTES"
        )

        return SimpleCache(cacheDir, evictor, dbProvider).also { cache = it }
    }

    /**
     * Pre-initializes the cache on a background dispatcher to avoid paying the setup cost
     * in a first-visible playback path.
     */
    suspend fun warmUp(context: Context) {
        withContext(Dispatchers.IO) {
            get(context.applicationContext)
        }
    }
}
