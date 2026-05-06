package com.vayunmathur.pdf

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.util.forEach
import androidx.pdf.EditablePdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.view.Highlight
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.pdf.util.PdfStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

fun isAdvancedPdfSupported(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // Android 15 (API 35) supports this natively
        true
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Check if the "R" extension (Android 11+) is at least Level 13
        // This is where the PDF backport lives
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 13
    } else {
        // Device is older than Android 11
        false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val intent: Intent = intent
        val data: Uri? = intent.data

        val pdfLoader = SandboxedPdfLoader(application)

        if(!isAdvancedPdfSupported()) {
            setContent {
                DynamicTheme {
                    Scaffold() { paddingValues ->
                        Box(Modifier.padding(paddingValues).fillMaxSize()) {
                            Text(stringResource(R.string.unsupported_version), Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
            return
        }

        setContent {
            var data by rememberSaveable { mutableStateOf(data) }
            var password: String? by rememberSaveable { mutableStateOf(null) }
            var pdfDocument by remember { mutableStateOf<EditablePdfDocument?>(null) }
            var showPasswordDialog by remember { mutableStateOf(false) }
            var passwordError by remember { mutableStateOf<String?>(null) }
            var isCapturing by rememberSaveable { mutableStateOf(false) }

            val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    data = it
                }
            }

            LaunchedEffect(data, password) {
                if(data != null) {
                    delay(1000)
                    try {
                        pdfDocument = pdfLoader.openDocument(data!!, password) as EditablePdfDocument
                    } catch(_: PdfPasswordException) {
                        if(password != null) {
                            passwordError = getString(R.string.incorrect_password)
                        }
                        showPasswordDialog = true
                    }
                }
            }

            DynamicTheme {
                if (data == null && !isCapturing) {
                    InitialScreen(
                        onOpenPdf = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                        onCapturePdf = { isCapturing = true }
                    )
                } else if (isCapturing) {
                    CapturePdfScreen(
                        onBack = { isCapturing = false },
                        onPdfCreated = { uri ->
                            data = uri
                            isCapturing = false
                        }
                    )
                } else {
                    pdfDocument?.let {
                        PdfViewerScreen(it, data?.lastPathSegment ?: "pdf")
                    } ?: Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                }

                if (showPasswordDialog) {
                    PasswordDialog(
                        errorMessage = passwordError,
                        onPasswordEntered = {
                            password = it
                            showPasswordDialog = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun InitialScreen(onOpenPdf: () -> Unit, onCapturePdf: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onOpenPdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.open_pdf))
            }
            Button(onClick = onCapturePdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.capture_pdf))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapturePdfScreen(onBack: () -> Unit, onPdfCreated: (Uri) -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<Uri>() }
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { images.add(it) }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { targetUri ->
            coroutineScope.launch {
                val success = savePdfToUri(context, images, targetUri)
                if (success) {
                    Toast.makeText(context, "PDF saved successfully", Toast.LENGTH_SHORT).show()
                    onBack()
                } else {
                    Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler {
        if (selectedIndex != null) {
            selectedIndex = null
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.capture_pdf_title)) },
                navigationIcon = {
                    IconNavigation(onBack)
                },
                actions = {
                    IconButton(onClick = { galleryLauncher.launch("image/*") }) {
                        IconUpload()
                    }
                    if (images.isNotEmpty()) {
                        IconButton(onClick = {
                            createDocumentLauncher.launch("captured_${System.currentTimeMillis()}.pdf")
                        }) {
                            IconSave()
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                modifier = Modifier.height(100.dp)
            ) {
                val lazyListState = rememberLazyListState()
                val state = rememberReorderableLazyListState(lazyListState) { from, to ->
                    if (to.index >= images.size || from.index >= images.size) return@rememberReorderableLazyListState
                    images.apply {
                        add(to.index, removeAt(from.index))
                    }
                }
                LazyRow(
                    state = lazyListState,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(images, key = { _, uri -> uri.toString() }) { index, uri ->
                        ReorderableItem(
                            state = state,
                            key = uri.toString()
                        ) { isDragging ->
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = 2.dp,
                                        color = if (selectedIndex == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .longPressDraggableHandle()
                                    .clickable { selectedIndex = index }
                            )
                        }
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { selectedIndex = null },
                            contentAlignment = Alignment.Center
                        ) {
                            IconAdd()
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (selectedIndex != null) {
                AsyncImage(
                    model = images[selectedIndex!!],
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                if (hasCameraPermission) {
                    CameraPreview(
                        onImageCaptured = { uri ->
                            images.add(uri)
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.request_camera_permission))
                        }
                        Button(onClick = { galleryLauncher.launch("image/*") }, Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.upload_image))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(onImageCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    ) { view ->
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraPreview", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        FloatingActionButton(
            onClick = {
                val file = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
                imageCapture.targetRotation = previewView.display.rotation
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            onImageCaptured(Uri.fromFile(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("CameraPreview", "Image capture failed", exception)
                        }
                    }
                )
            },
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            IconCamera()
        }
    }
}

suspend fun savePdfToUri(context: Context, images: List<Uri>, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
    val pdfDocument = PdfDocument()
    try {
        images.forEachIndexed { index, uri ->
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                
                // Scale the image so its longest side matches the longest side of A4 (842 points).
                // This ensures landscape images are correctly sized and prevents pages from being 
                // so large that they hit the 0.5x minimum zoom limit.
                val a4LongSide = 842f
                val scale = if (bitmap.width > bitmap.height) {
                    a4LongSide / bitmap.width
                } else {
                    a4LongSide / bitmap.height
                }
                
                val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

                val pageInfo = PdfDocument.PageInfo.Builder(targetWidth, targetHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val matrix = Matrix()
                matrix.postScale(scale, scale)
                page.canvas.drawBitmap(bitmap, matrix, null)

                pdfDocument.finishPage(page)
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing image $uri", e)
            }
        }

        context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                pdfDocument.writeTo(fos)
            }
        }
        true
    } catch (e: Exception) {
        Log.e("MainActivity", "Error saving PDF to URI", e)
        false
    } finally {
        pdfDocument.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(pdfDocument: EditablePdfDocument, pdfName: String) {
    val pdfState = remember { PdfViewerState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val pdfSavedMessage = stringResource(R.string.pdf_saved)
    val pdfSaveErrorMessage = stringResource(R.string.pdf_save_error)
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    context.contentResolver.openFileDescriptor(it, "w")?.use { pfd ->
                        pdfDocument.createWriteHandle().writeTo(pfd)
                        Toast.makeText(context, pdfSavedMessage, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerScreen", "Error saving PDF", e)
                    Toast.makeText(context, pdfSaveErrorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var showSearchBar by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf(emptyList<PdfRect>()) }
    var searchIndex by remember(searchResults) { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }

    BackHandler(showSearchBar) {
        showSearchBar = false
        searchResults = emptyList()
    }


    LaunchedEffect(pdfDocument.uri) {
        coroutineScope.launch {
            delay(500)
            val restored = PdfStateStore.restore(context, pdfDocument.uri)
            if (restored != null) {
                restored(pdfState)
            }
        }
    }

    var center by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        while(true) {
            delay(2000)
            PdfStateStore.save(context, pdfDocument.uri, center, pdfState)
        }
    }

    fun search() {
        coroutineScope.launch {
            val results = pdfDocument.searchDocument(searchText, 0 until pdfDocument.pageCount)
            val resultsFinal = mutableListOf<PdfRect>()
            results.forEach { page, result ->
                resultsFinal.addAll(result.mapNotNull {
                    it.bounds.firstOrNull()?.let { rect ->
                        PdfRect(page, rect)
                    }
                })
            }
            searchResults = resultsFinal
        }
    }

    var changesMade by remember { mutableStateOf(false) }

    LaunchedEffect(searchResults, searchIndex) {
        pdfState.setHighlights(
            searchResults.mapIndexed { idx, it ->
                Highlight(it, if(idx == searchIndex) 0xFFFFA500.toInt() else Color.Yellow.toArgb())
            }
        )
        if (searchResults.isNotEmpty()) {
            pdfState.scrollToPosition(searchResults[searchIndex].let {
                PdfPoint(it.pageNum, it.left, it.top)
            })
        }
    }

    val focusRequestor = remember { FocusRequester() }
    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
            search()
        } else {
            searchResults = emptyList()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar({ Text(stringResource(R.string.pdf_viewer_title)) }, actions = {
                if (!showSearchBar) {
                    IconButton({ showSearchBar = true }) { IconSearch() }
                    IconButton({ downloadLauncher.launch(pdfName) }) { IconSave() }
                    IconButton({
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, pdfDocument.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
                    }) {
                        IconShare()
                    }
                } else {
                    if (searchResults.isNotEmpty()) {
                        Text(
                            stringResource(R.string.search_result_counter, searchIndex + 1, searchResults.size),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            })
        },
        bottomBar = {
            if (showSearchBar) {
                BottomAppBar {
                    OutlinedTextField(
                        searchText,
                        { searchText = it; search() },
                        Modifier.fillMaxWidth().focusRequester(focusRequestor),
                        label = { Text(stringResource(R.string.search_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    )
                }
            }
        },
        floatingActionButton = {
            Column {
                if (showSearchBar) {
                    Column {
                        SmallFloatingActionButton({
                            if (searchIndex > 0)
                                searchIndex--
                        }) {
                            Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null)
                        }
                        SmallFloatingActionButton({
                            if (searchIndex < searchResults.size - 1)
                                searchIndex++
                        }) {
                            Icon(painterResource(R.drawable.keyboard_arrow_down_24px), null)
                        }
                    }
                }
                if(changesMade) {
                    FloatingActionButton({
                        changesMade = false
                        coroutineScope.launch {
                            context.contentResolver.openFileDescriptor(pdfDocument.uri, "wt")?.use { pfd ->
                                pdfDocument.createWriteHandle().writeTo(pfd)
                            }
                        }
                    }) {
                        IconSave()
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Box(Modifier.fillMaxSize()) {
                PdfViewer(
                    pdfDocument = pdfDocument,
                    state = pdfState,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        center = coordinates.size.center.toOffset()
                    },
                    isFormFillingEnabled = true,
                    isImageSelectionEnabled = true,
                    onFormWidgetInfoUpdated = { editInfo ->
                        coroutineScope.launch {
                            pdfDocument.applyEdit(editInfo)
                            changesMade = true
                        }
                    },
                ) { uri ->
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                    true
                }
            }
        }
    }
}

@Composable
fun PasswordDialog(
    onPasswordEntered: (String) -> Unit,
    errorMessage: String? = null
) {
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* Prevent dismissing by clicking outside */ },
        title = { Text(stringResource(R.string.password_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.password_dialog_message))
                if (errorMessage != null) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    isError = errorMessage != null
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPasswordEntered(password) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = null
    )
}
