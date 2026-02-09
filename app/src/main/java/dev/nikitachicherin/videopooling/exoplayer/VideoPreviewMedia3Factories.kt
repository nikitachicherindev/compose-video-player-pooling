package dev.nikitachicherin.videopooling.exoplayer

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

private const val TAG = "VideoPreviewFactories"

/**
 * Media3 factory wiring for this demo.
 *
 * Pooling [androidx.media3.exoplayer.ExoPlayer] instances is the *main* trick, but preview feeds
 * are typically I/O heavy as well (lots of short requests, rapid switching between items).
 *
 * This file builds a consistent "plumbing stack" for every player:
 * - one disk cache instance for the entire process ([VideoPreviewCache])
 * - one upstream network factory ([DefaultDataSource.Factory])
 * - a [DefaultMediaSourceFactory] that uses the cached data source
 *
 * The goal is to make every pooled player behave identically and avoid accidentally creating
 * multiple caches / multiple networking stacks.
 */
internal object VideoPreviewMedia3Factories {

    /**
     * Creates a [DataSource.Factory] that reads from a disk cache first and falls back to network.
     *
     * Cache behavior:
     * - "cache → upstream" (typical for on-demand preview segments)
     * - if the cache is corrupted or throws, we ignore the cache and keep the feed alive
     *
     * This is intentionally simple:
     * - no custom HTTP client
     * - no request headers
     * - no analytics or anything else
     */

    @OptIn(UnstableApi::class)
    private fun buildCacheDataSourceFactory(context: Context): DataSource.Factory {
        val appContext = context.applicationContext
        val cache: Cache = VideoPreviewCache.get(appContext)

        // DefaultDataSource chooses between HTTP/file/content schemes automatically.
        val upstreamFactory = DefaultDataSource.Factory(appContext)

        Log.d(TAG, "buildCacheDataSourceFactory(): cache=${cache.hashCode()}")

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }


    /**
     * Reuses or creates a [DefaultMediaSourceFactory] using our cached data source.
     *
     * Why this matters:
     * - each [androidx.media3.exoplayer.ExoPlayer] needs a MediaSourceFactory
     * - if you build it ad-hoc per player without care, you can easily end up with mismatched stacks
     */
    @OptIn(UnstableApi::class)
    fun buildMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val appContext = context.applicationContext

        val dsFactory = buildCacheDataSourceFactory(appContext)

        return DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(dsFactory)
    }
}
