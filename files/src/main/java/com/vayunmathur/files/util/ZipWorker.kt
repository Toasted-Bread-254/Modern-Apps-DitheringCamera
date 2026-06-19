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
import okio.Path
import okio.Path.Companion.toPath
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.vayunmathur.files.R

class ZipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val channelId = "zip_progress_channel"
    private val notificationId = 1

    override suspend fun doWork(): Result {
        val sourcePaths = inputData.getStringArray("source_paths") ?: return Result.failure()
        val destPathString = inputData.getString("dest_path") ?: return Result.failure()
        val destPath = destPathString.toPath()

        createNotificationChannel()
        setForeground(createForegroundInfo(0))

        return try {
            val fileSystem = FileSystem.SYSTEM
            
            var totalSize = 0L
            sourcePaths.forEach { totalSize += calculateTotalSize(fileSystem, it.toPath()) }
            
            var bytesZipped = 0L

            fileSystem.write(destPath) {
                ZipOutputStream(outputStream()).use { zipOutputStream ->
                    sourcePaths.forEach { pathString ->
                        val path = pathString.toPath()
                        addToZip(fileSystem, path, "", zipOutputStream) { bytes ->
                            bytesZipped += bytes
                            if (totalSize > 0) {
                                val progress = (bytesZipped * 100 / totalSize).toInt()
                                updateNotification(progress)
                            }
                        }
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

    private fun calculateTotalSize(fileSystem: FileSystem, path: Path): Long {
        val metadata = fileSystem.metadataOrNull(path) ?: return 0L
        return if (metadata.isDirectory) fileSystem.list(path).sumOf { calculateTotalSize(fileSystem, it) }
        else metadata.size ?: 0L
    }

    private fun addToZip(
        fileSystem: FileSystem,
        path: Path,
        base: String,
        zipOutputStream: ZipOutputStream,
        onProgress: (Long) -> Unit
    ) {
        val entryName = if (base.isEmpty()) path.name else "$base/${path.name}"
        val metadata = fileSystem.metadataOrNull(path) ?: return

        if (metadata.isDirectory) {
            val children = fileSystem.list(path)
            if (children.isEmpty()) {
                zipOutputStream.putNextEntry(ZipEntry("$entryName/"))
                zipOutputStream.closeEntry()
            } else {
                children.forEach { child ->
                    addToZip(fileSystem, child, entryName, zipOutputStream, onProgress)
                }
            }
        } else {
            zipOutputStream.putNextEntry(ZipEntry(entryName))
            fileSystem.read(path) {
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream().read(buffer).also { bytesRead = it } != -1) {
                    zipOutputStream.write(buffer, 0, bytesRead)
                    onProgress(bytesRead.toLong())
                }
            }
            zipOutputStream.closeEntry()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            applicationContext.getString(R.string.zip_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(progress: Int) =
        NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.archiving))
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
