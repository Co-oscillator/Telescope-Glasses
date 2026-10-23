package com.astro.telescopeglasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.android.Utils
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Array as ReflectArray
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val incomingSharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(OpenCVLoader.initLocal()) { "OpenCV could not be initialized" }
        incomingSharedUris.value = extractSharedUris(intent)
        setContent {
            MaterialTheme {
                TelescopeGlassesApp(
                    incomingUris = incomingSharedUris.value,
                    onIncomingUrisHandled = { incomingSharedUris.value = emptyList() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingSharedUris.value = extractSharedUris(intent)
    }
}

private data class LoadedImage(val pixels: Mat, val metadata: SeestarMetadata, val histogram: FloatArray)
private const val MAX_IMPORT_DIMENSION = 2048

@Composable
private fun TelescopeGlassesApp(
    incomingUris: List<Uri>,
    onIncomingUrisHandled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadedImage by remember { mutableStateOf<LoadedImage?>(null) }
    var editState by remember { mutableStateOf(AstroEditState()) }
    var metadata by remember { mutableStateOf(SeestarMetadata()) }
    var preview by remember { mutableStateOf(createWelcomeBitmap()) }
    var imageOnlyPreview by remember { mutableStateOf(createWelcomeBitmap()) }
    var showFramePreview by remember { mutableStateOf(true) }
    var exportFormat by remember { mutableStateOf(AstroExportFormat.JPEG) }
    var exportQuality by remember { mutableStateOf(95f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var showSmbDialog by remember { mutableStateOf(false) }
    var seestarIp by remember { mutableStateOf("10.0.0.1") }
    var targetFolder by remember { mutableStateOf("M 42 Orion Nebula") }

    fun importUri(uri: Uri) {
        scope.launch {
            isBusy = true
            errorMessage = null
            try {
                val file = copyUriToCache(context, uri)
                val image = withContext(Dispatchers.IO) { loadImage(file) }
                loadedImage = image
                metadata = image.metadata
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message ?: "Could not open this image."
            } finally {
                isBusy = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(::importUri)
    }

    LaunchedEffect(incomingUris) {
        incomingUris.firstOrNull()?.let { uri ->
            onIncomingUrisHandled()
            importUri(uri)
        }
    }

    LaunchedEffect(loadedImage, editState, metadata) {
        val image = loadedImage ?: return@LaunchedEffect
        delay(120)
        try {
            val rendered = withContext(Dispatchers.Default) {
                val edited = AstroImageProcessor.processAstroMat(image.pixels, editState)
                try {
                    SeestarFrameRenderer.appendMetadataBorder(edited, metadata, editState) to edited
                } catch (error: Exception) {
                    edited.recycle()
                    throw error
                }
            }
            preview = rendered.first
            imageOnlyPreview = rendered.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage = error.message ?: "Image processing failed."
        }
    }

    fun loadFile(file: File) {
        scope.launch {
            isBusy = true
            errorMessage = null
            try {
                val image = withContext(Dispatchers.IO) { loadImage(file) }
                loadedImage = image
                metadata = image.metadata
            } catch (error: Exception) {
                errorMessage = error.message ?: "Could not open this image."
            } finally {
                isBusy = false
            }
        }
    }

    TelescopeGlassesEditorScreen(
        previewBitmap = if (showFramePreview) preview else imageOnlyPreview,
        hasLoadedImage = loadedImage != null,
        showFramePreview = showFramePreview,
        histogram = loadedImage?.histogram,
        editState = editState,
        metadata = metadata,
        onEditStateChange = { editState = it },
        onShowFramePreviewChange = { showFramePreview = it },
        onMetadataChange = { metadata = it },
        exportFormat = exportFormat,
        exportQuality = exportQuality,
        onExportFormatChange = { exportFormat = it },
        onExportQualityChange = { exportQuality = it },
        onLoadFitsOrJpgClick = { filePicker.launch(arrayOf("*/*")) },
        onPullFromSeestarWifiClick = { showSmbDialog = true },
        onSaveAndShareClick = {
            scope.launch {
                val uri = withContext(Dispatchers.IO) {
                    AstroExportHelper.saveToSeestarGalleryFolder(
                        context = context,
                        bitmap = preview,
                        objectName = metadata.objectName,
                        format = exportFormat,
                        quality = exportQuality.roundToInt()
                    )
                }
                if (uri != null) AstroExportHelper.launchSystemShareSheet(context, uri, exportFormat.mimeType)
                else errorMessage = "Could not save the image to Pictures/Seestar."
            }
        }
    )

    if (showSmbDialog) {
        AlertDialog(
            onDismissRequest = { showSmbDialog = false },
            title = { Text("Connect to Seestar") },
            text = {
                androidx.compose.foundation.layout.Column {
                    OutlinedTextField(value = seestarIp, onValueChange = { seestarIp = it }, label = { Text("Seestar IP address") }, singleLine = true)
                    OutlinedTextField(value = targetFolder, onValueChange = { targetFolder = it }, label = { Text("MyWorks subfolder") }, singleLine = true)
                    Text("Uses anonymous SMB access on your local Wi-Fi network.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSmbDialog = false
                    scope.launch {
                        isBusy = true
                        try {
                            val file = File(context.cacheDir, "seestar_${System.currentTimeMillis()}.fit")
                            withContext(Dispatchers.IO) {
                                SeestarFitsRepository.fetchLatestStackedFitsOverSmb(seestarIp, targetFolder, file)
                            }
                            loadFile(file)
                        } catch (error: Exception) {
                            errorMessage = error.message ?: "Could not connect to the Seestar."
                        } finally {
                            isBusy = false
                        }
                    }
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { showSmbDialog = false }) { Text("Cancel") } }
        )
    }

    if (errorMessage != null || isBusy) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) errorMessage = null },
            title = { Text(if (isBusy) "Working…" else "Telescope Glasses") },
            text = { Text(if (isBusy) "Loading or processing image." else errorMessage.orEmpty()) },
            confirmButton = {
                if (!isBusy) TextButton(onClick = { errorMessage = null }) { Text("OK") }
            }
        )
    }
}

private suspend fun copyUriToCache(context: android.content.Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    val mimeExtension = when (context.contentResolver.getType(uri)?.lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "application/fits", "application/x-fits" -> ".fits"
        else -> ""
    }
    val name = displayName
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
        ?: "imported_image$mimeExtension"
    val file = File(context.cacheDir, "${System.currentTimeMillis()}_$name")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(file).use { output -> input.copyTo(output) }
    } ?: error("Android could not open the selected file.")
    file
}

private fun extractSharedUris(intent: Intent?): List<Uri> {
    if (intent?.action != Intent.ACTION_SEND) return emptyList()
    val streamUri = if (android.os.Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
    }
    val uri = streamUri ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    return listOfNotNull(uri ?: intent.data)
}

private fun loadImage(file: File): LoadedImage {
    val isFits = file.extension.lowercase() in setOf("fit", "fits", "fts") || hasFitsSignature(file)
    return if (isFits) loadFitsImage(file) else loadBitmapImage(file)
}

private fun hasFitsSignature(file: File): Boolean = runCatching {
    file.inputStream().use { input ->
        val header = ByteArray(9)
        val count = input.read(header)
        count == header.size && String(header, Charsets.US_ASCII) == "SIMPLE  ="
    }
}.getOrDefault(false)

private fun loadBitmapImage(file: File): LoadedImage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or corrupt image file." }
    val sample = ceil(max(bounds.outWidth, bounds.outHeight) / MAX_IMPORT_DIMENSION.toDouble()).toInt().coerceAtLeast(1)
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Could not decode the selected image.")
    val rgba = Mat()
    val rgb = Mat()
    Utils.bitmapToMat(bitmap, rgba)
    bitmap.recycle()
    Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
    rgba.release()
    val floatRgb = Mat()
    rgb.convertTo(floatRgb, CvType.CV_32FC3, 1.0 / 255.0)
    rgb.release()
    return LoadedImage(
        floatRgb,
        SeestarMetadata(objectName = file.nameWithoutExtension.ifBlank { "Imported Image" }),
        buildHistogram(floatRgb)
    )
}

private fun loadFitsImage(file: File): LoadedImage {
    nom.tam.fits.Fits(file).use { fits ->
        val hdu = fits.getHDU(0) ?: error("FITS file contains no primary image.")
        val header = hdu.header
        val width = header.getIntValue("NAXIS1", 0)
        val height = header.getIntValue("NAXIS2", 0)
        require(width > 0 && height > 0) { "FITS primary HDU is not a 2D image." }
        val channels = header.getIntValue("NAXIS3", 1).coerceIn(1, 3)
        val kernel = hdu.kernel
        val step = ceil(max(width, height) / MAX_IMPORT_DIMENSION.toDouble()).toInt().coerceAtLeast(1)
        val outWidth = ceil(width.toDouble() / step).toInt()
        val outHeight = ceil(height.toDouble() / step).toInt()
        val scale = header.getDoubleValue("BSCALE", 1.0)
        val zero = header.getDoubleValue("BZERO", 0.0)
        val channelCount = if (channels >= 3) 3 else 1
        val sampled = FloatArray(outWidth * outHeight * 3)
        var minValue = Double.POSITIVE_INFINITY
        var maxValue = Double.NEGATIVE_INFINITY

        fun valueAt(channel: Int, y: Int, x: Int): Double {
            var array: Any = kernel ?: error("FITS primary HDU contains no image data.")
            if (channels >= 3) array = ReflectArray.get(array, channel)
                ?: error("Malformed FITS channel data.")
            array = ReflectArray.get(array, y) ?: error("Malformed FITS row data.")
            val value = ReflectArray.get(array, x) ?: error("Malformed FITS pixel data.")
            val raw = if (value is Byte) (value.toInt() and 0xff).toDouble() else (value as Number).toDouble()
            return raw * scale + zero
        }

        for (y in 0 until outHeight) for (x in 0 until outWidth) {
            val sourceX = (x * step).coerceAtMost(width - 1)
            val sourceY = (y * step).coerceAtMost(height - 1)
            val outIndex = (y * outWidth + x) * 3
            for (c in 0 until channelCount) {
                val value = valueAt(c, sourceY, sourceX)
                if (value.isFinite()) {
                    sampled[outIndex + c] = value.toFloat()
                    minValue = minOf(minValue, value)
                    maxValue = maxOf(maxValue, value)
                } else {
                    sampled[outIndex + c] = Float.NaN
                }
            }
        }
        require(minValue.isFinite() && maxValue.isFinite()) { "FITS image contains no finite pixel values." }
        val sourceRange = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val histogramBins = 4096
        val counts = IntArray(histogramBins)
        var count = 0
        for (i in sampled.indices step 3) {
            for (c in 0 until channelCount) {
                val value = sampled[i + c]
                if (value.isFinite()) {
                    val bin = (((value - minValue) / sourceRange) * (histogramBins - 1))
                        .toInt().coerceIn(0, histogramBins - 1)
                    counts[bin]++
                    count++
                }
            }
        }
        fun percentile(fraction: Double): Double {
            val target = (count * fraction).toInt().coerceAtLeast(1)
            var cumulative = 0
            for (bin in counts.indices) {
                cumulative += counts[bin]
                if (cumulative >= target) return minValue + sourceRange * bin / (histogramBins - 1)
            }
            return maxValue
        }
        var blackPoint = percentile(0.001)
        var whitePoint = percentile(0.9995)
        if (whitePoint <= blackPoint) {
            blackPoint = minValue
            whitePoint = maxValue
        }
        val range = (whitePoint - blackPoint).takeIf { it > 0.0 } ?: 1.0
        for (i in sampled.indices step 3) {
            if (channelCount == 1) {
                val gray = ((sampled[i].takeIf { it.isFinite() } ?: blackPoint.toFloat()) - blackPoint)
                    .div(range).toFloat().coerceIn(0f, 1f)
                sampled[i] = gray
                sampled[i + 1] = gray
                sampled[i + 2] = gray
            } else {
                for (c in 0..2) {
                    val value = sampled[i + c].takeIf { it.isFinite() } ?: blackPoint.toFloat()
                    sampled[i + c] = ((value - blackPoint) / range).toFloat().coerceIn(0f, 1f)
                }
            }
        }
        val mat = Mat(outHeight, outWidth, CvType.CV_32FC3)
        mat.put(0, 0, sampled)
        return LoadedImage(mat, SeestarFitsRepository.readFitsMetadata(file), buildHistogram(mat))
    }
}

private fun buildHistogram(rgb: Mat): FloatArray {
    val bins = FloatArray(128)
    val samples = FloatArray((rgb.total() * rgb.channels()).toInt())
    rgb.get(0, 0, samples)
    var pixel = 0
    while (pixel + 2 < samples.size) {
        val luminance = 0.2126f * samples[pixel] + 0.7152f * samples[pixel + 1] + 0.0722f * samples[pixel + 2]
        if (luminance.isFinite()) {
            val bin = (luminance.coerceIn(0f, 1f) * (bins.lastIndex)).toInt()
            bins[bin] += 1f
        }
        pixel += 12 // Sample every fourth pixel; this is a visual guide, not photometry.
    }
    val peak = bins.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    for (i in bins.indices) bins[i] /= peak
    return bins
}

private fun createWelcomeBitmap(): Bitmap = Bitmap.createBitmap(900, 520, Bitmap.Config.ARGB_8888).apply {
    val canvas = Canvas(this)
    canvas.drawColor(Color.rgb(7, 9, 14))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        textAlign = Paint.Align.CENTER
        textSize = 30f
    }
    canvas.drawText("Load a FITS or JPG image to begin", width / 2f, height / 2f, paint)
}
