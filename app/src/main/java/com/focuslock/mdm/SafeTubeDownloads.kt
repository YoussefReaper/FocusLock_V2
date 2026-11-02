package com.focuslock.mdm

import android.content.Context

/**
 * Saving checked videos, with the Keeper's cuts removed, to the folder picked in
 * Keeper settings. Only the personal build carries this: downloading breaks
 * YouTube's terms and needs GPL code, so the build that could be sold leaves it out
 * and plays through the official player instead.
 */
object SafeTubeDownloads {
    val available: Boolean get() = BuildConfig.PERSONAL_DOWNLOADS && FlavorStreams.isPresent

    fun start(context: Context, videoId: String, title: String, cuts: List<Pair<Int, Int>>) {
        if (!available) return
        SafeTubeDownloader.enqueue(context, videoId, title, cuts)
    }
}
