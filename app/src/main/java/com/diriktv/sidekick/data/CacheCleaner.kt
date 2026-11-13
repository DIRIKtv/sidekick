package com.diriktv.sidekick.data

import android.content.Context
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Settings → App → Clear cache: what the app keeps that it can fetch again. */
object CacheCleaner {
    /** Bytes in the app's cache folder (pictures, the saved Home, a downloaded update). */
    suspend fun size(context: Context): Long = withContext(Dispatchers.IO) {
        context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Empties the image caches and the cache folder, and forgets channel lookups. Settings,
     * follows, history and resume points live elsewhere and stay.
     */
    suspend fun clear(context: Context) {
        val images = SingletonImageLoader.get(context)
        images.memoryCache?.clear()
        withContext(Dispatchers.IO) {
            images.diskCache?.clear()
            context.cacheDir.listFiles()?.forEach { f -> runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() } }
        }
        KickApi.clearCaches()
        SevenTv.clear()
    }
}
