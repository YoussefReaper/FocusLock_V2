package com.focuslock.mdm

/**
 * The build that could be sold carries no stream extraction: videos play only
 * through YouTube's official player (see SafeTubePlayerActivity).
 */
object FlavorStreams {
    const val isPresent = false
    fun resolve(videoId: String): VideoStreams? = null
}
