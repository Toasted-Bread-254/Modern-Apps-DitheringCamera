package com.vayunmathur.education.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList

/**
 * Streams resolved for playback. If [audioUrl] is null, [videoUrl] is a muxed
 * (progressive) stream; otherwise the two are combined at playback time.
 */
data class ResolvedStreams(
    val title: String,
    val videoUrl: String,
    val audioUrl: String?,
)

/**
 * Resolves a YouTube video id to playable stream URLs via the NewPipe
 * extractor (FOSS, no Google APIs) — same mechanism :youpipe uses.
 */
object VideoExtractor {
    suspend fun resolve(youtubeId: String): ResolvedStreams = withContext(Dispatchers.IO) {
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$youtubeId")
        ex.fetchPage()

        // Prefer a single muxed progressive stream (simplest to play).
        val muxed = ex.videoStreams.filterNotNull().filter { it.content.isNotBlank() }
        if (muxed.isNotEmpty()) {
            val best = muxed.maxByOrNull { it.height }!!
            return@withContext ResolvedStreams(ex.name, best.content, null)
        }

        // Otherwise combine the best video-only + audio streams.
        val video = ex.videoOnlyStreams.filterNotNull().filter { it.content.isNotBlank() }
            .maxByOrNull { it.height } ?: error("No playable video stream")
        val audio = ex.audioStreams.filterNotNull().filter { it.content.isNotBlank() }
            .maxByOrNull { it.bitrate }
        ResolvedStreams(ex.name, video.content, audio?.content)
    }
}
