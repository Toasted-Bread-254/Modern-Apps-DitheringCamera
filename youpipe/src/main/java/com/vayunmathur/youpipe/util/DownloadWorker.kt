package com.vayunmathur.youpipe.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.ui.VideoInfo
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

class DownloadWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val videoID = inputData.getLong("videoID", -1L)
        val videoUrl = inputData.getString("videoUrl") ?: return@coroutineScope Result.failure()
        val audioUrl = inputData.getString("audioUrl")

        val videoInfo =
                VideoInfo(
                        name = inputData.getString("name") ?: "",
                        videoID = videoID,
                        duration = inputData.getLong("duration", 0L),
                        views = inputData.getLong("views", 0L),
                        uploadDate = Instant.fromEpochSeconds(inputData.getLong("uploadDate", 0L)),
                        thumbnailURL = inputData.getString("thumbnailURL") ?: "",
                        author = inputData.getString("author") ?: ""
                )

        val db = applicationContext.buildDatabase<SubscriptionDatabase>()
        val dir = File(applicationContext.getExternalFilesDir(null), "downloads")
        if (!dir.exists()) dir.mkdirs()

        val videoFile = File(dir, "$videoID.mp4")
        val audioFile = if (audioUrl != null) File(dir, "$videoID.m4a") else null

        val createdFiles = mutableListOf<File>()
        createdFiles.add(videoFile)
        audioFile?.let { createdFiles.add(it) }

        try {
            val videoWeight = if (audioUrl != null) 0.5 else 1.0

            // Simplified progress reporting for parallel:
            var videoP = 0.0
            var audioP = 0.0

            val vJob =
                    async(Dispatchers.IO) {
                        downloadFileChunked(videoUrl, videoFile) { p ->
                            videoP = p
                            DownloadManager.updateProgress(
                                    videoID,
                                    (videoP * videoWeight) + (audioP * (1.0 - videoWeight))
                            )
                        }
                    }

            val aJob =
                    audioUrl?.let { url ->
                        async(Dispatchers.IO) {
                            downloadFileChunked(url, audioFile!!) { p ->
                                audioP = p
                                DownloadManager.updateProgress(
                                        videoID,
                                        (videoP * videoWeight) + (audioP * (1.0 - videoWeight))
                                )
                            }
                        }
                    }

            vJob.await()
            aJob?.await()

            val download =
                    DownloadedVideo(
                            id = videoID,
                            videoItem = videoInfo,
                            filePath = videoFile.absolutePath,
                            audioPath = audioFile?.absolutePath,
                            timestamp = Clock.System.now()
                    )

            db.downloadedVideoDao().upsert(download)
            Result.success()
        } catch (e: Exception) {
            // Cleanup on error or cancellation
            createdFiles.forEach { if (it.exists()) it.delete() }
            Result.failure()
        } finally {
            if (isStopped) {
                createdFiles.forEach { if (it.exists()) it.delete() }
            }
            DownloadManager.finishDownload(videoID)
        }
    }

    private suspend fun downloadFileChunked(url: String, file: File, onProgress: (Double) -> Unit) =
            coroutineScope {
                val request = Request.Builder().url(url).head().build()
                val totalBytes =
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                // If HEAD fails, try GET with a small range to get content length
                                val getRequest =
                                        Request.Builder()
                                                .url(url)
                                                .header("Range", "bytes=0-0")
                                                .build()
                                client.newCall(getRequest).execute().use { getResponse ->
                                    if (!getResponse.isSuccessful) return@coroutineScope
                                    val contentRange = getResponse.header("Content-Range")
                                    contentRange?.substringAfterLast("/")?.toLongOrNull()
                                            ?: getResponse.body.contentLength()
                                }
                            } else {
                                response.body.contentLength()
                            }
                        }

                if (totalBytes <= 0) {
                    // Fallback to simple download if size unknown
                    downloadFileSimple(url, file, onProgress)
                    return@coroutineScope
                }

                val numChunks = 4
                val chunkSize = totalBytes / numChunks
                val downloadedBytes = AtomicLong(0)

                // Pre-allocate file
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "rw").use { raf -> raf.setLength(totalBytes) }
                }

                val jobs =
                        (0 until numChunks).map { i ->
                            val start = i * chunkSize
                            val end =
                                    if (i == numChunks - 1) totalBytes - 1
                                    else (i + 1) * chunkSize - 1

                            async(Dispatchers.IO) {
                                val chunkRequest =
                                        Request.Builder()
                                                .url(url)
                                                .addHeader("Range", "bytes=$start-$end")
                                                .build()

                                client.newCall(chunkRequest).execute().use { response ->
                                    if (!response.isSuccessful) return@async

                                    val source = response.body.source()
                                    RandomAccessFile(file, "rw").use { raf ->
                                        raf.seek(start)
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (true) {
                                            if (isStopped) return@async
                                            bytesRead = source.read(buffer)
                                            if (bytesRead == -1) break
                                            raf.write(buffer, 0, bytesRead)
                                            val total =
                                                    downloadedBytes.addAndGet(bytesRead.toLong())
                                            onProgress(total.toDouble() / totalBytes.toDouble())
                                        }
                                    }
                                }
                            }
                        }

                jobs.forEach { it.await() }
            }

    private fun downloadFileSimple(url: String, file: File, onProgress: (Double) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val totalBytes = response.body.contentLength()
            var downloadedBytes = 0L

            val source = response.body.source()
            val sink = file.sink().buffer()

            val buffer = okio.Buffer()
            while (true) {
                if (isStopped) {
                    sink.close()
                    file.delete()
                    return
                }
                val read = source.read(buffer, 8192)
                if (read == -1L) break

                sink.write(buffer, read)
                downloadedBytes += read
                if (totalBytes > 0) {
                    onProgress(downloadedBytes.toDouble() / totalBytes.toDouble())
                }
            }
            sink.flush()
            sink.close()
        }
    }

    companion object {
        private val client =
                OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()

        fun enqueue(context: Context, videoInfo: VideoInfo, videoUrl: String, audioUrl: String?) {
            val data =
                    Data.Builder()
                            .putLong("videoID", videoInfo.videoID)
                            .putString("name", videoInfo.name)
                            .putLong("duration", videoInfo.duration)
                            .putLong("views", videoInfo.views)
                            .putLong("uploadDate", videoInfo.uploadDate.epochSeconds)
                            .putString("thumbnailURL", videoInfo.thumbnailURL)
                            .putString("author", videoInfo.author)
                            .putString("videoUrl", videoUrl)
                            .putString("audioUrl", audioUrl)
                            .build()

            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).build()

            WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                            "download_${videoInfo.videoID}",
                            ExistingWorkPolicy.KEEP,
                            request
                    )
        }
    }
}
