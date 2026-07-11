package com.vayunmathur.photos.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.vayunmathur.photos.data.PanoData
import com.vayunmathur.photos.data.Photo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive 360 viewer: renders an equirectangular photo onto the inside of a
 * UV sphere (OpenGL ES 2.0). The user looks around by dragging and pinches to
 * zoom — no device motion. Partial panoramas map only their covered band using
 * [PanoData]; the rest of the sphere stays black. The initial view is centered
 * on the covered band so the photo is in front on open.
 */
private class PanoramaSphereGLView(
    context: Context,
    uri: Uri,
    panoData: PanoData,
) : GLSurfaceView(context) {

    private val renderer = SphereRenderer(context, uri, panoData)
    private val scaleDetector: ScaleGestureDetector

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Pinch out (scaleFactor > 1) zooms in → narrower FOV.
                renderer.fov = (renderer.fov / detector.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
                requestRender()
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    // Drag "grabs" the scene: dragging right/down brings the
                    // content that was to the left/above into view. Scale by FOV
                    // so the feel is consistent across zoom levels.
                    val speed = DRAG_SPEED * (renderer.fov / DEFAULT_FOV)
                    renderer.addYaw(dx * speed)
                    renderer.addPitch(dy * speed)
                    lastX = event.x; lastY = event.y
                    requestRender()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    companion object {
        // Radians of rotation per pixel of drag at the default FOV.
        private const val DRAG_SPEED = 0.0015f
        private const val DEFAULT_FOV = 75f
        private const val MIN_FOV = 30f
        private const val MAX_FOV = 100f
    }
}

private class SphereRenderer(
    private val context: Context,
    private val uri: Uri,
    private val pano: PanoData,
) : GLSurfaceView.Renderer {

    @Volatile var fov = 75f

    // Look direction, radians. Initialized to the center of the covered band.
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f

    fun addYaw(delta: Float) { yaw += delta }
    fun addPitch(delta: Float) { pitch = (pitch + delta).coerceIn(-MAX_PITCH, MAX_PITCH) }

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer
    private lateinit var indexBuffer: ShortBuffer
    private var indexCount = 0

    private var program = 0
    private var textureId = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTex = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private var aspect = 1f

    init {
        val fullW = pano.fullWidth.toFloat().coerceAtLeast(1f)
        val fullH = pano.fullHeight.toFloat().coerceAtLeast(1f)
        val centerU = (pano.croppedLeft + pano.croppedWidth / 2f) / fullW
        val centerV = (pano.croppedTop + pano.croppedHeight / 2f) / fullH
        // Longitude of the band center; matches the mesh's theta = u * 2π.
        yaw = (centerU * 2.0 * Math.PI).toFloat()
        // Latitude of the band center; matches the mesh's phi = π/2 - v * π.
        pitch = ((Math.PI / 2.0 - centerV * Math.PI).toFloat()).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE) // viewed from inside the sphere
        buildMesh()
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        textureId = loadTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.perspectiveM(projection, 0, fov, aspect, 0.1f, 10f)

        val cp = cos(pitch)
        val dirX = cp * sin(yaw)
        val dirY = sin(pitch)
        val dirZ = cp * cos(yaw)
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, dirX, dirY, dirZ, 0f, 1f, 0f)

        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun buildMesh() {
        val positions = ArrayList<Float>()
        val texCoords = ArrayList<Float>()
        val indices = ArrayList<Short>()

        val fullW = pano.fullWidth.toFloat().coerceAtLeast(1f)
        val fullH = pano.fullHeight.toFloat().coerceAtLeast(1f)
        val cropW = pano.croppedWidth.toFloat().coerceAtLeast(1f)
        val cropH = pano.croppedHeight.toFloat().coerceAtLeast(1f)

        for (stack in 0..STACKS) {
            val vFull = stack.toFloat() / STACKS // 0 at top (+90 lat)
            val phi = Math.PI / 2.0 - vFull * Math.PI
            val cosPhi = cos(phi)
            val sinPhi = sin(phi)
            for (slice in 0..SLICES) {
                val uFull = slice.toFloat() / SLICES
                val theta = uFull * 2.0 * Math.PI
                val x = (cosPhi * sin(theta)).toFloat()
                val y = sinPhi.toFloat()
                val z = (cosPhi * cos(theta)).toFloat()
                positions.add(x); positions.add(y); positions.add(z)

                // Map full-sphere UV into the stored (cropped) texture. Values
                // outside [0,1] fall outside the covered band → black in shader.
                val texU = (uFull * fullW - pano.croppedLeft) / cropW
                val texV = (vFull * fullH - pano.croppedTop) / cropH
                texCoords.add(texU); texCoords.add(texV)
            }
        }

        val cols = SLICES + 1
        for (stack in 0 until STACKS) {
            for (slice in 0 until SLICES) {
                val a = (stack * cols + slice).toShort()
                val b = (stack * cols + slice + 1).toShort()
                val c = ((stack + 1) * cols + slice).toShort()
                val d = ((stack + 1) * cols + slice + 1).toShort()
                indices.add(a); indices.add(c); indices.add(b)
                indices.add(b); indices.add(c); indices.add(d)
            }
        }

        vertexBuffer = positions.toFloatBuffer()
        texBuffer = texCoords.toFloatBuffer()
        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                indices.forEach { put(it) }
                position(0)
            }
        indexCount = indices.size
    }

    private fun loadTexture(): Int {
        val maxTex = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        val cap = maxTex[0].coerceAtLeast(2048)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > cap || bounds.outHeight / sample > cap) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return 0

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return id
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun List<Float>.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            forEach { put(it) }
            position(0)
        }

    companion object {
        private const val STACKS = 64
        private const val SLICES = 64
        // Just under ±90° to avoid a degenerate look-at (dir parallel to up).
        private const val MAX_PITCH = 1.5f

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPos;
                vTex = aTex;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vTex;
            void main() {
                if (vTex.x < 0.0 || vTex.x > 1.0 || vTex.y < 0.0 || vTex.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTex, vTex);
                }
            }
        """
    }
}

/**
 * Compose wrapper around the 360 sphere [GLSurfaceView]. Forwards resume/pause
 * to the GL surface via the composition lifecycle.
 */
@Composable
fun PanoramaSphereView(
    photo: Photo,
    modifier: Modifier = Modifier,
) {
    val pano = photo.panoData ?: return
    val uri = photo.uri.toUri()
    val context = LocalContext.current

    val glView = remember(uri) { PanoramaSphereGLView(context, uri, pano) }

    androidx.compose.runtime.DisposableEffect(glView) {
        glView.onResume()
        onDispose { glView.onPause() }
    }

    AndroidView(modifier = modifier, factory = { glView })
}
