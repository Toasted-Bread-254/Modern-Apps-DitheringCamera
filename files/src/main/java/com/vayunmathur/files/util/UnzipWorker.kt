package com.vayunmathur.files.util
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import okio.FileSystem
import okio.Path.Companion.toPath
import java.util.zip.ZipInputStream
import com.vayunmathur.files.R

class UnzipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val channelId = "unzip_progress_channel"
    private val notificationId = 2

    override suspend fun doWork(): Result {
        val zipPathString = inputData.getString("zip_path") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()

        val zipPath = zipPathString.toPath()
        val destPath = destPathString.toPath()

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        return try {
            val fileSystem = FileSystem.SYSTEM
            val zipFileSize = fileSystem.metadataOrNull(zipPath)?.size ?: 0L
            var totalBytesRead = 0L

            fileSystem.read(zipPath) {
                val countingInputStream = object : java.io.FilterInputStream(inputStream()) {
                    override fun read(): Int {
                        val b = super.read()
                        if (b != -1) {
                            totalBytesRead++
                            updateProgress(totalBytesRead, zipFileSize)
                        }
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val count = super.read(b, off, len)
                        if (count != -1) {
                            totalBytesRead += count
                            updateProgress(totalBytesRead, zipFileSize)
                        }
                        return count
                    }
                }

                ZipInputStream(countingInputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        val entryFile = java.io.File(destPath.toString(), entry.name).canonicalFile
                        val destFile = java.io.File(destPath.toString()).canonicalFile
                        if (!entryFile.path.startsWith(destFile.path)) {
                            entry = zipInputStream.nextEntry
                            continue
                        }
                        val entryPath = entryFile.path.toPath()
                        if (entry.isDirectory) {
                            fileSystem.createDirectories(entryPath)
                        } else {
                            fileSystem.createDirectories(entryPath.parent!!)
                            fileSystem.write(entryPath) {
                                val buffer = ByteArray(8192)
                                var bytes: Int
                                while (zipInputStream.read(buffer).also { bytes = it } != -1) {
                                    write(buffer, 0, bytes)
                                }
                            }
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        } finally {
            notificationManager.cancel(notificationId)
        }
    }

    private fun updateProgress(totalBytesRead: Long, zipFileSize: Long) {
        if (zipFileSize > 0) {
            val progress = (totalBytesRead * 100 / zipFileSize).toInt()
            updateNotification(progress)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            applicationContext.getString(R.string.unzip_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(progress: Int) =
        NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.unzipping))
            .setSmallIcon(R.drawable.folder_24px)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

    private fun createForegroundInfo(progress: Int) =
        ForegroundInfo(notificationId, buildNotification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun updateNotification(progress: Int) {
        notificationManager.notify(notificationId, buildNotification(progress))
    }
}
