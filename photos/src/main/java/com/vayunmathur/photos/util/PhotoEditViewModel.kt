package com.vayunmathur.photos.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.SerializedStroke
import com.vayunmathur.library.util.deserialize
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.roundToInt

class PhotoEditViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _transformedBitmap = MutableStateFlow<Bitmap?>(null)
    val transformedBitmap: StateFlow<Bitmap?> = _transformedBitmap.asStateFlow()

    private val decodedCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size > 32) {
                val current = _originalBitmap.value
                if (eldest.value !== current) {
                    try { eldest.value.recycle() } catch (_: Exception) {}
                }
                return true
            }
            return false
        }
    }

    private var lastDecodedUri: String? = null

    fun decode(uri: Uri) {
        val uriStr = uri.toString()
        if (uriStr == lastDecodedUri && _originalBitmap.value != null) return
        val cached = synchronized(decodedCache) { decodedCache[uriStr] }
        if (cached != null && !cached.isRecycled) {
            lastDecodedUri = uriStr
            _originalBitmap.value = cached
            return
        }
        val ctx: Context = getApplication()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val source = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val target = 2048
                    if (w > target || h > target) {
                        val scale = target.toFloat() / maxOf(w, h)
                        decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                    }
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                synchronized(decodedCache) { decodedCache[uriStr] = bmp }
                lastDecodedUri = uriStr
                _originalBitmap.value = bmp
            } catch (e: Exception) {
                Log.e(TAG, "decode failed for $uri", e)
            }
        }
    }

    fun applyTransform(rotation: Float, cropRect: Rect, isCropping: Boolean) {
        val original = _originalBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val matrix = Matrix().apply { postRotate(rotation) }
            var result = Bitmap.createBitmap(
                original, 0, 0, original.width, original.height, matrix, true,
            )
            if (!isCropping) {
                val left = (cropRect.left * result.width).roundToInt().coerceIn(0, result.width - 1)
                val top = (cropRect.top * result.height).roundToInt().coerceIn(0, result.height - 1)
                val width = ((cropRect.right - cropRect.left) * result.width).roundToInt()
                    .coerceAtMost(result.width - left)
                val height = ((cropRect.bottom - cropRect.top) * result.height).roundToInt()
                    .coerceAtMost(result.height - top)
                if (width > 0 && height > 0) {
                    val cropped = Bitmap.createBitmap(result, left, top, width, height)
                    if (cropped !== result) {
                        try { if (result !== original) result.recycle() } catch (_: Exception) {}
                        result = cropped
                    }
                }
            }
            val previous = _transformedBitmap.value
            _transformedBitmap.value = result
            if (previous != null && previous !== original && previous !== result) {
                try { if (!previous.isRecycled) previous.recycle() } catch (_: Exception) {}
            }
        }
    }

    fun savePhoto(
        photo: Photo,
        rotation: Float,
        cropRect: Rect,
        strokes: List<SerializedStroke>,
        texts: List<TextElement>,
        viewportWidth: Float,
        asCopy: Boolean,
        onComplete: () -> Unit,
    ) {
        val ctx: Context = getApplication()
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                writeEdited(ctx, photo, rotation, cropRect, strokes, texts, viewportWidth, asCopy)
            }
            onComplete()
        }
    }

    override fun onCleared() {
        super.onCleared()
        val transformed = _transformedBitmap.value
        val original = _originalBitmap.value
        _transformedBitmap.value = null
        _originalBitmap.value = null
        if (transformed != null && transformed !== original) {
            try { if (!transformed.isRecycled) transformed.recycle() } catch (_: Exception) {}
        }
        synchronized(decodedCache) {
            decodedCache.values.forEach { bmp ->
                try { if (!bmp.isRecycled) bmp.recycle() } catch (_: Exception) {}
            }
            decodedCache.clear()
        }
    }

    companion object {
        private const val TAG = "PhotoEditViewModel"

        private fun writeEdited(
            context: Context,
            photo: Photo,
            rotation: Float,
            cropRect: Rect,
            strokes: List<SerializedStroke>,
            texts: List<TextElement>,
            viewportWidth: Float,
            asCopy: Boolean,
        ) {
            val photoUri = Uri.parse(photo.uri)
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, photoUri)
            var originalBitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }

            val matrix = Matrix().apply { postRotate(rotation) }
            var transformedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true,
            )
            val left = (cropRect.left * transformedBitmap.width).roundToInt()
                .coerceIn(0, transformedBitmap.width - 1)
            val top = (cropRect.top * transformedBitmap.height).roundToInt()
                .coerceIn(0, transformedBitmap.height - 1)
            val width = ((cropRect.right - cropRect.left) * transformedBitmap.width).roundToInt()
                .coerceAtMost(transformedBitmap.width - left)
            val height = ((cropRect.bottom - cropRect.top) * transformedBitmap.height).roundToInt()
                .coerceAtMost(transformedBitmap.height - top)
            if (width > 0 && height > 0) {
                transformedBitmap = Bitmap.createBitmap(transformedBitmap, left, top, width, height)
            }
            val resultBitmap = transformedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(resultBitmap)

            if (strokes.isNotEmpty()) {
                val renderer = androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create()
                val scaleMatrix = Matrix().apply {
                    setScale(
                        resultBitmap.width / viewportWidth,
                        resultBitmap.width / viewportWidth,
                    )
                }
                strokes.forEach { serialized ->
                    try {
                        val stroke = serialized.deserialize()
                        renderer.draw(canvas, stroke, scaleMatrix)
                    } catch (_: Exception) {}
                }
            }

            if (texts.isNotEmpty()) {
                val textPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.FILL
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                texts.forEach { textElement ->
                    textPaint.color = textElement.color
                    textPaint.textSize = textElement.fontSize * (resultBitmap.width / viewportWidth)
                    val fontMetrics = textPaint.fontMetrics
                    canvas.save()
                    canvas.translate(textElement.x * resultBitmap.width, textElement.y * resultBitmap.height)
                    canvas.rotate(textElement.rotation)
                    canvas.drawText(textElement.text, 0f, -fontMetrics.ascent, textPaint)
                    canvas.restore()
                }
            }
            val resolver = context.contentResolver
            val nowSeconds = System.currentTimeMillis() / 1000
            if (asCopy) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Edited_${photo.name}")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { out ->
                        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }
            } else {
                val uri = Uri.parse(photo.uri)
                try {
                    resolver.openOutputStream(uri, "rwt")?.use { out ->
                        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DATE_MODIFIED, nowSeconds)
                    }
                    resolver.update(uri, updateValues, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "save failed", e)
                }
            }
        }
    }
}

class PhotoEditViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PhotoEditViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return PhotoEditViewModel(application) as T
    }
}
