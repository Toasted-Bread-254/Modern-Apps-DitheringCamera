package com.vayunmathur.camera.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GuideDotState {
    PENDING,
    ALIGNING,
    CAPTURING,
    CAPTURED
}

data class GuideDot(
    val index: Int,
    val targetAngle: Float,
    val targetPitch: Float = 0f,
    val state: GuideDotState
)

class PanoramaEngine(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping = _isSweeping.asStateFlow()

    private val _isStitching = MutableStateFlow(false)
    val isStitching = _isStitching.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount = _frameCount.asStateFlow()

    private val _sweepAngle = MutableStateFlow(0f)
    val sweepAngle = _sweepAngle.asStateFlow()

    private val _guideDots = MutableStateFlow<List<GuideDot>>(emptyList())
    val guideDots: StateFlow<List<GuideDot>> = _guideDots.asStateFlow()

    private val _currentAngle = MutableStateFlow(0f)
    val currentAngle: StateFlow<Float> = _currentAngle.asStateFlow()

    private val _currentPitch = MutableStateFlow(0f)
    val currentPitch: StateFlow<Float> = _currentPitch.asStateFlow()

    private val _sweepDirection = MutableStateFlow(0)
    val sweepDirection: StateFlow<Int> = _sweepDirection.asStateFlow()

    private val frames = mutableListOf<Bitmap>()
    private var accumulatedAngle = 0f
    private var accumulatedPitch = 0f
    private var lastTimestamp = 0L
    private var lastCaptureAngle = 0f
    private var angularVelocity = 0f
    private var pitchVelocity = 0f

    private var sphereMode = false
    private val ALIGNMENT_THRESHOLD_DEGREES = 3f
    private val PITCH_THRESHOLD_DEGREES = 5f
    private val MAX_VELOCITY_DPS = 30f

    @Volatile
    var latestFrame: Bitmap? = null

    companion object {
        private const val FRAME_SCALE = 0.75f

        init {
            OpenCVLoader.initLocal()
        }
    }

    fun startSweep(fullSphere: Boolean = false) {
        sphereMode = fullSphere
        frames.clear()
        accumulatedAngle = 0f
        accumulatedPitch = 0f
        lastCaptureAngle = 0f
        lastTimestamp = 0L
        angularVelocity = 0f
        pitchVelocity = 0f
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _sweepDirection.value = 0
        _currentAngle.value = 0f
        _currentPitch.value = 0f

        val dots = if (fullSphere) {
            var idx = 0
            val result = mutableListOf<GuideDot>()
            for (pitch in listOf(0f, -30f, 30f)) {
                val yawStep = if (pitch == 0f) 30f else 60f
                val count = (360f / yawStep).toInt()
                for (i in 0 until count) {
                    result.add(GuideDot(idx, i * yawStep, pitch, if (idx == 0) GuideDotState.CAPTURING else GuideDotState.PENDING))
                    idx++
                }
            }
            result
        } else {
            (0 until 7).map { i ->
                GuideDot(i, i * 30f, 0f, if (i == 0) GuideDotState.CAPTURING else GuideDotState.PENDING)
            }
        }
        _guideDots.value = dots

        captureFrame()
        updateDotState(0, GuideDotState.CAPTURED)

        _isSweeping.value = true
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopSweep() {
        _isSweeping.value = false
        sensorManager.unregisterListener(this)
    }

    private fun captureFrame() {
        val frame = latestFrame ?: return
        val w = (frame.width * FRAME_SCALE).toInt()
        val h = (frame.height * FRAME_SCALE).toInt()
        val scaled = Bitmap.createScaledBitmap(frame, w, h, true)
        val rotMatrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, rotMatrix, true)
        scaled.recycle()
        frames.add(rotated)
        _frameCount.value = frames.size
        lastCaptureAngle = accumulatedAngle
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_isSweeping.value) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val timestamp = event.timestamp
        if (lastTimestamp != 0L) {
            val dt = (timestamp - lastTimestamp) / 1_000_000_000f
            val pitchRate = Math.toDegrees(event.values[0].toDouble()).toFloat()
            val yawRate = -Math.toDegrees(event.values[1].toDouble()).toFloat()
            pitchVelocity = pitchRate
            angularVelocity = yawRate
            accumulatedPitch += pitchRate * dt
            accumulatedAngle += yawRate * dt
            _currentPitch.value = accumulatedPitch
            _currentAngle.value = accumulatedAngle
            _sweepAngle.value = Math.abs(accumulatedAngle)

            if (_sweepDirection.value == 0 && Math.abs(accumulatedAngle) > 10f) {
                val dir = if (accumulatedAngle > 0) 1 else -1
                _sweepDirection.value = dir
                if (dir == -1 && !sphereMode) {
                    _guideDots.value = _guideDots.value.map { it.copy(targetAngle = -it.targetAngle) }
                }
            }

            checkDotAlignment()
        }
        lastTimestamp = timestamp

        val maxAngle = if (sphereMode) 360f else 200f
        if (_guideDots.value.all { it.state == GuideDotState.CAPTURED } || Math.abs(accumulatedAngle) > maxAngle) {
            stopSweep()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkDotAlignment() {
        val dots = _guideDots.value
        val currentAng = accumulatedAngle
        var captured = false

        _guideDots.value = dots.map { dot ->
            if (dot.state == GuideDotState.CAPTURED) return@map dot

            val angleDiff = Math.abs(currentAng - dot.targetAngle)
            val pitchDiff = Math.abs(accumulatedPitch - dot.targetPitch)
            val withinCapture = angleDiff < ALIGNMENT_THRESHOLD_DEGREES && pitchDiff < PITCH_THRESHOLD_DEGREES
            val withinAligning = angleDiff < ALIGNMENT_THRESHOLD_DEGREES * 2 && pitchDiff < PITCH_THRESHOLD_DEGREES * 2
            val steadyEnough = Math.abs(angularVelocity) < MAX_VELOCITY_DPS && Math.abs(pitchVelocity) < MAX_VELOCITY_DPS

            when {
                withinCapture && steadyEnough && !captured -> {
                    captureFrame()
                    captured = true
                    dot.copy(state = GuideDotState.CAPTURED)
                }
                withinCapture -> dot.copy(state = GuideDotState.CAPTURING)
                withinAligning -> dot.copy(state = GuideDotState.ALIGNING)
                dot.state != GuideDotState.PENDING -> dot.copy(state = GuideDotState.PENDING)
                else -> dot
            }
        }
    }

    private fun updateDotState(index: Int, state: GuideDotState) {
        _guideDots.value = _guideDots.value.map {
            if (it.index == index) it.copy(state = state) else it
        }
    }

    suspend fun stitch(): Bitmap? {
        if (frames.size < 2) return null
        _isStitching.value = true
        return withContext(Dispatchers.IO) {
            try {
                stitchManual()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                _isStitching.value = false
            }
        }
    }

    private fun stitchManual(): Bitmap? {
        return try {
            val mats = frames.map { bmp ->
                val mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                mat
            }

            var result = mats[0].clone()
            val orb = ORB.create(2000)
            val matcher = BFMatcher.create(Core.NORM_HAMMING, true)

            for (i in 1 until mats.size) {
                result = stitchPair(result, mats[i], orb, matcher) ?: run {
                    mats.forEach { it.release() }
                    return null
                }
            }

            mats.forEach { it.release() }

            val bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(result, bitmap)
            result.release()
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun stitchPair(base: Mat, next: Mat, orb: ORB, matcher: BFMatcher): Mat? {
        val kp1 = MatOfKeyPoint()
        val desc1 = Mat()
        val kp2 = MatOfKeyPoint()
        val desc2 = Mat()

        val gray1 = Mat()
        val gray2 = Mat()
        Imgproc.cvtColor(base, gray1, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(next, gray2, Imgproc.COLOR_RGBA2GRAY)

        orb.detectAndCompute(gray1, Mat(), kp1, desc1)
        orb.detectAndCompute(gray2, Mat(), kp2, desc2)
        gray1.release()
        gray2.release()

        if (desc1.empty() || desc2.empty() || desc1.rows() < 10 || desc2.rows() < 10) {
            return null
        }

        val matches = MatOfDMatch()
        matcher.match(desc2, desc1, matches)
        desc1.release()
        desc2.release()

        val matchList = matches.toList().sortedBy { it.distance }
        val goodMatches = matchList.take((matchList.size * 0.5).toInt().coerceAtLeast(10))
        if (goodMatches.size < 10) return null

        val kpList1 = kp1.toList()
        val kpList2 = kp2.toList()

        val srcPts = MatOfPoint2f(*goodMatches.map { kpList2[it.queryIdx].pt }.toTypedArray())
        val dstPts = MatOfPoint2f(*goodMatches.map { kpList1[it.trainIdx].pt }.toTypedArray())

        val mask = MatOfByte()
        val affine = Calib3d.estimateAffinePartial2D(srcPts, dstPts, mask, Calib3d.RANSAC, 3.0)
        srcPts.release()
        dstPts.release()
        kp1.release()
        kp2.release()

        if (affine.empty()) return null

        val corners = arrayOf(
            Point(0.0, 0.0),
            Point(next.cols().toDouble(), 0.0),
            Point(next.cols().toDouble(), next.rows().toDouble()),
            Point(0.0, next.rows().toDouble())
        )
        val warpedCorners = corners.map { pt ->
            val x = affine.get(0, 0)[0] * pt.x + affine.get(0, 1)[0] * pt.y + affine.get(0, 2)[0]
            val y = affine.get(1, 0)[0] * pt.x + affine.get(1, 1)[0] * pt.y + affine.get(1, 2)[0]
            Point(x, y)
        }

        val allPts = warpedCorners + listOf(
            Point(0.0, 0.0),
            Point(base.cols().toDouble(), 0.0),
            Point(base.cols().toDouble(), base.rows().toDouble()),
            Point(0.0, base.rows().toDouble())
        )

        val minX = allPts.minOf { it.x }
        val minY = allPts.minOf { it.y }
        val maxX = allPts.maxOf { it.x }
        val maxY = allPts.maxOf { it.y }

        val outW = (maxX - minX).toInt()
        val outH = (maxY - minY).toInt()
        if (outW <= 0 || outH <= 0 || outW > 10000 || outH > 10000) {
            affine.release()
            return null
        }

        val combinedAffine = affine.clone()
        combinedAffine.put(0, 2, affine.get(0, 2)[0] - minX)
        combinedAffine.put(1, 2, affine.get(1, 2)[0] - minY)

        val warpedNext = Mat()
        Imgproc.warpAffine(next, warpedNext, combinedAffine, Size(outW.toDouble(), outH.toDouble()))

        val output = warpedNext.clone()
        val roi = Rect((-minX).toInt(), (-minY).toInt(), base.cols(), base.rows())
        if (roi.x >= 0 && roi.y >= 0 && roi.x + roi.width <= output.cols() && roi.y + roi.height <= output.rows()) {
            val baseOnCanvas = Mat.zeros(output.size(), output.type())
            base.copyTo(Mat(baseOnCanvas, roi))

            val baseMask = Mat.zeros(output.size(), CvType.CV_8UC1)
            Mat(baseMask, roi).setTo(Scalar(255.0))

            val warpedGray = Mat()
            val warpedMask = Mat()
            Imgproc.cvtColor(output, warpedGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(warpedGray, warpedMask, 1.0, 255.0, Imgproc.THRESH_BINARY)
            warpedGray.release()

            val overlapMask = Mat()
            Core.bitwise_and(baseMask, warpedMask, overlapMask)

            val baseOnly = Mat()
            Core.subtract(baseMask, overlapMask, baseOnly)

            val warpedOnly = Mat()
            Core.subtract(warpedMask, overlapMask, warpedOnly)

            val result = Mat.zeros(output.size(), output.type())
            baseOnCanvas.copyTo(result, baseOnly)
            output.copyTo(result, warpedOnly)

            val blended = Mat()
            Core.addWeighted(baseOnCanvas, 0.5, output, 0.5, 0.0, blended)
            blended.copyTo(result, overlapMask)

            result.copyTo(output)

            baseOnCanvas.release()
            baseMask.release()
            warpedMask.release()
            overlapMask.release()
            baseOnly.release()
            warpedOnly.release()
            blended.release()
            result.release()
        }

        affine.release()
        combinedAffine.release()
        warpedNext.release()

        return output
    }

    fun saveToMediaStore(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PANO_$timestamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ) ?: return
        context.contentResolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
        }
    }

    fun reset() {
        frames.forEach { it.recycle() }
        frames.clear()
        _frameCount.value = 0
        _sweepAngle.value = 0f
        _isSweeping.value = false
        _isStitching.value = false
        _guideDots.value = emptyList()
        _currentAngle.value = 0f
        _currentPitch.value = 0f
        _sweepDirection.value = 0
        accumulatedPitch = 0f
        pitchVelocity = 0f
    }
}
