package com.astro.telescopeglasses

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nom.tam.fits.BasicHDU
import nom.tam.fits.Fits
import nom.tam.fits.Header
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.exp
import kotlin.math.sqrt

/*
 * ============================================================================
 * TELESCOPE GLASSES — SEESTAR S50 ANDROID KOTLIN STARTER FOR ANTIGRAVITY IDE
 * ============================================================================
 *
 * Includes:
 * 1. Pinned Top Image Preview with 90° Landscape Rotation Toggle (shrinks the
 *    photo pane height from 46% to 34% while rendering the image wider and less
 *    scaled, freeing 66% of the screen for the Curves & Sliders!)
 * 2. Swipeable Bottom HorizontalPager (3 Panels: Curves vs. Sliders vs. Files)
 * 3. 65,536-Entry Monotonic Cubic Spline 16-Bit LUT Curves (L, R, G, B)
 * 4. Multi-Scale Unsharp Masking (Small-Detail Sharpen vs. Large-Scale Structure)
 * 5. Seestar S50 FITS Header Reader (`nom-tam-fits`) + Station Mode Wi-Fi SMB Pull
 * 6. Seestar Information Border Renderer + MediaStore Gallery Saver
 */

// ============================================================================
// 1. DATA MODELS: SEESTAR FITS METADATA & PHOTO EDIT STATE
// ============================================================================

data class SeestarMetadata(
    val objectName: String = "M 42 Orion Nebula",
    val telescope: String = "ZWO Seestar S50",
    val stackCount: Int = 420,
    val subExposureSec: Double = 10.0,
    val totalExposureSec: Double = 4200.0,
    val filter: String = "LP Dual-Band",
    val gain: Int = 80,
    val ccdTempC: Double = 10.0,
    val raDegrees: Double = 83.82,
    val decDegrees: Double = -5.39,
    val dateObs: String = "2026-10-22",
    val bayerPattern: String = "GRBG"
) {
    fun formattedIntegration(): String {
        val totalSec = totalExposureSec.roundToInt()
        val mins = totalSec / 60
        val secs = totalSec % 60
        return "${stackCount} × ${subExposureSec.roundToInt()}s (${mins}m ${secs}s)"
    }
}

enum class AstroExportFormat(val label: String, val extension: String, val mimeType: String) {
    JPEG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp")
}

@Suppress("DEPRECATION")
private fun AstroExportFormat.bitmapCompressFormat(): Bitmap.CompressFormat = when (this) {
    AstroExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
    AstroExportFormat.PNG -> Bitmap.CompressFormat.PNG
    AstroExportFormat.WEBP -> Bitmap.CompressFormat.WEBP
}

data class AstroEditState(
    val isRotated90: Boolean = false,
    val asinhL: Float = 0.55f,
    val asinhR: Float = 0.0f,
    val asinhG: Float = 0.0f,
    val asinhB: Float = 0.0f,
    val curveL: List<PointF> = listOf(PointF(0f, 0f), PointF(1f, 1f)),
    val curveR: List<PointF> = listOf(PointF(0f, 0f), PointF(1f, 1f)),
    val curveG: List<PointF> = listOf(PointF(0f, 0f), PointF(1f, 1f)),
    val curveB: List<PointF> = listOf(PointF(0f, 0f), PointF(1f, 1f)),
    val noiseReduction: Float = 0.25f,           // 0.0f .. 1.0f (Edge-preserving bilateral)
    val saturation: Float = 0.20f,               // -1.0f .. +1.0f (HSV/Lab chroma scale)
    val tint: Float = 0.0f,                       // -1.0 green .. +1.0 purple
    val temperature: Float = 0.0f,                // -1.0 blue .. +1.0 orange
    val sharpeningSmall: Float = 0.15f,          // 0.0f .. 1.0f (Unsharp mask sigma = 1.2px)
    val structureLarge: Float = 0.30f            // 0.0f .. 1.0f (Unsharp mask sigma = 24.0px "Pop")
) {
    fun asinhFor(channel: String): Float = when (channel) {
        "R" -> asinhR
        "G" -> asinhG
        "B" -> asinhB
        else -> asinhL
    }

    fun withAsinh(channel: String, value: Float): AstroEditState = when (channel) {
        "R" -> copy(asinhR = value)
        "G" -> copy(asinhG = value)
        "B" -> copy(asinhB = value)
        else -> copy(asinhL = value)
    }

    fun buildActiveToolsSummary(): String {
        val usedTools = mutableListOf<String>()

        val asinhChannels = listOf("L" to asinhL, "R" to asinhR, "G" to asinhG, "B" to asinhB)
            .filter { it.second > 0.01f }
        if (asinhChannels.isNotEmpty()) {
            usedTools += "Asinh (${asinhChannels.joinToString(", ") { "${it.first} ${(it.second * 100).roundToInt()}%" }})"
        }

        val activeCurves = mutableListOf<String>()
        if (isCurveModified(curveL)) activeCurves += "L"
        if (isCurveModified(curveR)) activeCurves += "R"
        if (isCurveModified(curveG)) activeCurves += "G"
        if (isCurveModified(curveB)) activeCurves += "B"
        if (activeCurves.isNotEmpty()) {
            usedTools += "Curves (${activeCurves.joinToString(", ")})"
        }

        if (noiseReduction > 0.01f) {
            usedTools += "Denoise (${(noiseReduction * 100).roundToInt()}%)"
        }
        if (abs(saturation) > 0.01f) {
            val sign = if (saturation > 0) "+" else ""
            usedTools += "Saturation ($sign${(saturation * 100).roundToInt()}%)"
        }
        if (abs(tint) > 0.01f) usedTools += "Tint (${if (tint > 0) "+" else ""}${(tint * 100).roundToInt()}%)"
        if (abs(temperature) > 0.01f) usedTools += "Temp (${if (temperature > 0) "+" else ""}${(temperature * 100).roundToInt()}%)"
        if (sharpeningSmall > 0.01f) {
            usedTools += "Sharpen (+${(sharpeningSmall * 100).roundToInt()}%)"
        }
        if (structureLarge > 0.01f) {
            usedTools += "Structure (+${(structureLarge * 100).roundToInt()}%)"
        }

        return if (usedTools.isEmpty()) {
            "Telescope Glasses: Unmodified Base Stack"
        } else {
            "Telescope Glasses: ${usedTools.joinToString(" • ")}"
        }
    }

    private fun isCurveModified(pts: List<PointF>): Boolean {
        if (pts.size != 2) return true
        return abs(pts[0].x) > 0.01f ||
               abs(pts[0].y) > 0.01f ||
               abs(pts[1].x - 1f) > 0.01f ||
               abs(pts[1].y - 1f) > 0.01f
    }

    fun compactEditSummary(): String {
        val items = mutableListOf<String>()
        val asinhChannels = listOf("L" to asinhL, "R" to asinhR, "G" to asinhG, "B" to asinhB)
            .filter { it.second > 0.01f }
        if (asinhChannels.isNotEmpty()) {
            items += "ASINH ${asinhChannels.joinToString(" ") { "${it.first}${(it.second * 100).roundToInt()}" }}"
        }
        if (isCurveModified(curveL) || isCurveModified(curveR) || isCurveModified(curveG) || isCurveModified(curveB)) {
            items += "CURVES"
        }
        if (noiseReduction > 0.01f) items += "NR ${(noiseReduction * 100).roundToInt()}%"
        if (abs(saturation) > 0.01f) items += "SAT ${(saturation * 100).roundToInt()}%"
        if (abs(tint) > 0.01f) items += "TINT ${(tint * 100).roundToInt()}%"
        if (abs(temperature) > 0.01f) items += "TEMP ${(temperature * 100).roundToInt()}%"
        if (sharpeningSmall > 0.01f) items += "DETAIL ${(sharpeningSmall * 100).roundToInt()}%"
        if (structureLarge > 0.01f) items += "STRUCT ${(structureLarge * 100).roundToInt()}%"
        return if (items.isEmpty()) "TG  •  ORIGINAL" else "TG  •  ${items.joinToString("   ·   ")}"
    }
}

// ============================================================================
// 2. PINNED PREVIEW (WITH 90° ROTATE BUTTON) + SWIPEABLE 3-PANEL WORKSPACE
// ============================================================================

@Composable
fun TelescopeGlassesEditorScreen(
    previewBitmap: Bitmap,
    hasLoadedImage: Boolean,
    showFramePreview: Boolean,
    histogram: FloatArray?,
    editState: AstroEditState,
    metadata: SeestarMetadata,
    onEditStateChange: (AstroEditState) -> Unit,
    onShowFramePreviewChange: (Boolean) -> Unit,
    onMetadataChange: (SeestarMetadata) -> Unit,
    exportFormat: AstroExportFormat,
    exportQuality: Float,
    onExportFormatChange: (AstroExportFormat) -> Unit,
    onExportQualityChange: (Float) -> Unit,
    onLoadFitsOrJpgClick: () -> Unit,
    onPullFromSeestarWifiClick: () -> Unit,
    onSaveAndShareClick: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val panelTitles = listOf("1. Curves", "2. Sliders", "3. Files & Frame")

    val photoPaneWeight by animateFloatAsState(
        targetValue = if (editState.isRotated90) 0.36f else 0.30f,
        label = "photoPaneWeight"
    )
    val controlsPaneWeight = 1f - photoPaneWeight

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF07090E))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // TOP PANE: PINNED LIVE IMAGE PREVIEW + ROTATE 90° BUTTON
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(photoPaneWeight)
                .padding(8.dp)
                .background(
                    androidx.compose.ui.graphics.Color(0xFF0D111A),
                    RoundedCornerShape(16.dp)
                )
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = editState.buildActiveToolsSummary(),
                    color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilterChip(
                    selected = showFramePreview,
                    onClick = { onShowFramePreviewChange(!showFramePreview) },
                    label = { Text("Label", fontSize = 11.sp) },
                    modifier = Modifier.height(30.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                OutlinedButton(
                    onClick = {
                        onEditStateChange(editState.copy(isRotated90 = !editState.isRotated90))
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (editState.isRotated90) "Reset rotation" else "Rotate 90°",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = androidx.compose.ui.graphics.Color(0xFF7DD3FC)
                    )
                }
            }

            var zoom by remember { mutableFloatStateOf(1f) }
            var zoomPan by remember { mutableStateOf(Offset.Zero) }
            val zoomState = rememberTransformableState { zoomChange, panChange, _ ->
                zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                zoomPan = if (zoom <= 1f) Offset.Zero else zoomPan + panChange
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = !hasLoadedImage,
                        onClickLabel = "Choose an image",
                        onClick = onLoadFitsOrJpgClick
                    )
                    .background(
                        androidx.compose.ui.graphics.Color(0xFF07090E),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Telescope Glasses Live Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = zoomPan.x
                            translationY = zoomPan.y
                        }
                        .transformable(enabled = hasLoadedImage, state = zoomState)
                )
                if (zoom > 1.01f) {
                    TextButton(
                        onClick = { zoom = 1f; zoomPan = Offset.Zero },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) { Text("Reset zoom") }
                }
            }
        }

        // BOTTOM PANE: SWIPEABLE 3-PANEL CONTROL DECK (Expands to 66% height!)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(controlsPaneWeight)
                .background(
                    androidx.compose.ui.graphics.Color(0xFF0D111A),
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                panelTitles.forEachIndexed { index, title ->
                    val isSelected = pagerState.currentPage == index
                    Button(
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected)
                                androidx.compose.ui.graphics.Color(0xFF0284C7)
                            else
                                androidx.compose.ui.graphics.Color(0xFF151C2C)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> CurvesPanelPage(
                        editState = editState,
                        histogram = histogram,
                        onEditStateChange = onEditStateChange
                    )
                    1 -> SlidersPanelPage(
                        editState = editState,
                        onEditStateChange = onEditStateChange
                    )
                    2 -> FileAndMetadataPanelPage(
                        metadata = metadata,
                        editState = editState,
                        exportFormat = exportFormat,
                        exportQuality = exportQuality,
                        onExportFormatChange = onExportFormatChange,
                        onExportQualityChange = onExportQualityChange,
                        onMetadataChange = onMetadataChange,
                        onLoadFitsOrJpgClick = onLoadFitsOrJpgClick,
                        onPullFromSeestarWifiClick = onPullFromSeestarWifiClick,
                        onSaveAndShareClick = onSaveAndShareClick
                    )
                }
            }
        }
    }
}

@Composable
private fun CurvesPanelPage(
    editState: AstroEditState,
    histogram: FloatArray?,
    onEditStateChange: (AstroEditState) -> Unit
) {
    var activeChannel by remember { mutableStateOf("L") }
    val activeAsinh = editState.asinhFor(activeChannel)
    val stretchedHistogram = remember(histogram, activeAsinh) {
        histogram?.let { source ->
            val output = FloatArray(source.size)
            val beta = 1f + activeAsinh * 64f
            val denominator = asinh(beta)
            source.forEachIndexed { index, count ->
                val x = index.toFloat() / (source.lastIndex.coerceAtLeast(1))
                val stretchedX = if (activeAsinh > 0.005f) asinh(x * beta) / denominator else x
                val bin = (stretchedX * output.lastIndex).roundToInt().coerceIn(0, output.lastIndex)
                output[bin] += count
            }
            output
        }
    }
    val channelPoints = when (activeChannel) {
        "R" -> editState.curveR
        "G" -> editState.curveG
        "B" -> editState.curveB
        else -> editState.curveL
    }
    val channelColor = when (activeChannel) {
        "R" -> androidx.compose.ui.graphics.Color(0xFFF43F5E)
        "G" -> androidx.compose.ui.graphics.Color(0xFF10B981)
        "B" -> androidx.compose.ui.graphics.Color(0xFF38BDF8)
        else -> androidx.compose.ui.graphics.Color(0xFFF8FAFC)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Asinh • ${mapOf("L" to "Light", "R" to "Red", "G" to "Green", "B" to "Blue")[activeChannel]}  ${(activeAsinh * 100).roundToInt()}%",
                color = androidx.compose.ui.graphics.Color(0xFFFBBF24),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            TextButton(onClick = {
                onEditStateChange(
                    when (activeChannel) {
                        "R" -> editState.copy(curveR = listOf(PointF(0f, 0f), PointF(1f, 1f))).withAsinh("R", 0f)
                        "G" -> editState.copy(curveG = listOf(PointF(0f, 0f), PointF(1f, 1f))).withAsinh("G", 0f)
                        "B" -> editState.copy(curveB = listOf(PointF(0f, 0f), PointF(1f, 1f))).withAsinh("B", 0f)
                        else -> editState.copy(curveL = listOf(PointF(0f, 0f), PointF(1f, 1f))).withAsinh("L", 0.55f)
                    }
                )
            }) {
                Text("Reset $activeChannel", fontSize = 12.sp)
            }
        }

        Slider(
            value = activeAsinh,
            onValueChange = { onEditStateChange(editState.withAsinh(activeChannel, it)) },
            valueRange = 0f..1f
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("L" to "Light", "R" to "Red", "G" to "Green", "B" to "Blue").forEach { (key, label) ->
                FilterChip(
                    selected = activeChannel == key,
                    onClick = { activeChannel = key },
                    label = { Text(label, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        AstroCurvesEditor(
            points = channelPoints,
            channelColor = channelColor,
            histogram = stretchedHistogram,
            onPointsChanged = { updated ->
                onEditStateChange(
                    when (activeChannel) {
                        "R" -> editState.copy(curveR = updated)
                        "G" -> editState.copy(curveG = updated)
                        "B" -> editState.copy(curveB = updated)
                        else -> editState.copy(curveL = updated)
                    }
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 160.dp)
        )
    }
}

@Composable
private fun SlidersPanelPage(
    editState: AstroEditState,
    onEditStateChange: (AstroEditState) -> Unit
) {
    val controls = listOf(
        SliderControlSpec("Noise reduction", editState.noiseReduction, 0f..1f, "${(editState.noiseReduction * 100).roundToInt()}%") {
            onEditStateChange(editState.copy(noiseReduction = it))
        },
        SliderControlSpec("Saturation", editState.saturation, -1f..1f, "${(editState.saturation * 100).roundToInt()}%") {
            onEditStateChange(editState.copy(saturation = it))
        },
        SliderControlSpec("Green ↔ Purple tint", editState.tint, -1f..1f, "${(editState.tint * 100).roundToInt()}%") {
            onEditStateChange(editState.copy(tint = it))
        },
        SliderControlSpec("Blue ↔ Orange temp", editState.temperature, -1f..1f, "${(editState.temperature * 100).roundToInt()}%") {
            onEditStateChange(editState.copy(temperature = it))
        },
        SliderControlSpec("Fine detail", editState.sharpeningSmall, 0f..1f, "+${(editState.sharpeningSmall * 100).roundToInt()}%") {
            onEditStateChange(editState.copy(sharpeningSmall = it))
        },
        SliderControlSpec("Structure", editState.structureLarge, 0f..1f, "+${(editState.structureLarge * 100).roundToInt()}%") {
            onEditStateChange(editState.copy(structureLarge = it))
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        controls.chunked(2).forEach { rowControls ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowControls.forEach { control ->
                    AstroSliderControl(
                        label = control.label,
                        value = control.value,
                        valueRange = control.range,
                        displayValue = control.displayValue,
                        onValueChange = control.onValueChange,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

private data class SliderControlSpec(
    val label: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val displayValue: String,
    val onValueChange: (Float) -> Unit
)

@Composable
private fun AstroSliderControl(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(0xFF07090E), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = label, color = androidx.compose.ui.graphics.Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(text = displayValue, color = androidx.compose.ui.graphics.Color(0xFF38BDF8), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun FileAndMetadataPanelPage(
    metadata: SeestarMetadata,
    editState: AstroEditState,
    exportFormat: AstroExportFormat,
    exportQuality: Float,
    onExportFormatChange: (AstroExportFormat) -> Unit,
    onExportQualityChange: (Float) -> Unit,
    onMetadataChange: (SeestarMetadata) -> Unit,
    onLoadFitsOrJpgClick: () -> Unit,
    onPullFromSeestarWifiClick: () -> Unit,
    onSaveAndShareClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onLoadFitsOrJpgClick, modifier = Modifier.weight(1f)) {
                Text("Load .FIT / .JPG", fontSize = 12.sp)
            }
            Button(onClick = onPullFromSeestarWifiClick, modifier = Modifier.weight(1f)) {
                Text("Pull via Seestar Wi-Fi", fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            AstroExportFormat.entries.forEach { format ->
                FilterChip(
                    selected = exportFormat == format,
                    onClick = { onExportFormatChange(format) },
                    label = { Text(format.label, fontSize = 11.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF07090E), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Export quality", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp)
                Text(
                    text = if (exportFormat == AstroExportFormat.PNG) "PNG · Lossless" else "${exportQuality.roundToInt()}%",
                    color = androidx.compose.ui.graphics.Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = exportQuality,
                onValueChange = onExportQualityChange,
                valueRange = 0f..100f,
                enabled = exportFormat != AstroExportFormat.PNG
            )
        }

        OutlinedTextField(
            value = metadata.objectName,
            onValueChange = { onMetadataChange(metadata.copy(objectName = it)) },
            label = { Text("Target (OBJECT)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = metadata.filter,
                onValueChange = { onMetadataChange(metadata.copy(filter = it)) },
                label = { Text("Filter (FILTER)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = metadata.dateObs,
                onValueChange = { onMetadataChange(metadata.copy(dateObs = it)) },
                label = { Text("Date (DATE-OBS)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(text = editState.compactEditSummary(), color = androidx.compose.ui.graphics.Color(0xFF38BDF8), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)

        Button(
            onClick = onSaveAndShareClick,
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0284C7)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save ${exportFormat.label} to Pictures/Seestar & Share", fontWeight = FontWeight.Bold)
        }
    }
}

// ============================================================================
// 3. SEESTAR S50 FITS HEADER PARSER & WI-FI SMB FETCHER
// ============================================================================

object SeestarFitsRepository {

    fun readFitsMetadata(fitsFile: File): SeestarMetadata {
        Fits(fitsFile).use { fits ->
            val hdu: BasicHDU<*> = fits.getHDU(0)
            val header: Header = hdu.header

            val stackCnt = header.getIntValue("STACKCNT", 1)
            val expTime = header.getDoubleValue("EXPTIME", 10.0)
            val totalExp = header.getDoubleValue("TOTALEXP", stackCnt * expTime)

            return SeestarMetadata(
                objectName = header.getStringValue("OBJECT")?.trim() ?: "Unknown Target",
                telescope = header.getStringValue("TELESCOP")?.trim() ?: "ZWO Seestar S50",
                stackCount = stackCnt,
                subExposureSec = expTime,
                totalExposureSec = totalExp,
                filter = header.getStringValue("FILTER")?.trim() ?: "IRCUT",
                gain = header.getIntValue("GAIN", 80),
                ccdTempC = header.getDoubleValue("CCD-TEMP", 10.0),
                raDegrees = header.getDoubleValue("RA", 0.0),
                decDegrees = header.getDoubleValue("DEC", 0.0),
            dateObs = header.getStringValue("DATE-OBS")?.trim() ?: "",
                bayerPattern = header.getStringValue("BAYERPAT")?.trim() ?: "GRBG"
            )
        }
    }

    suspend fun fetchLatestStackedFitsOverSmb(
        seestarIp: String,
        targetFolder: String,
        localDestFile: File
    ): File = withContext(Dispatchers.IO) {
        val client = SMBClient()
        client.connect(seestarIp).use { connection ->
            val session = connection.authenticate(AuthenticationContext.anonymous())
            val share = session.connectShare("EMMC Images") as DiskShare

            val folderPath = "MyWorks\\$targetFolder"
            val stackedFitsItem = share.list(folderPath)
                .firstOrNull {
                    val name = it.fileName.lowercase()
                    (name.endsWith(".fit") || name.endsWith(".fits")) && name.startsWith("stacked_")
                } ?: error("No stacked .fit file found in $folderPath")

            val remoteFilePath = "$folderPath\\${stackedFitsItem.fileName}"
            share.openFile(
                remoteFilePath,
                setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
                com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { smbFile ->
                smbFile.inputStream.use { input ->
                    FileOutputStream(localDestFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        localDestFile
    }
}

// ============================================================================
// 4. 16-BIT ASTROPHOTOGRAPHY IMAGE PROCESSING ENGINE (OPENCV + 65,536 LUT)
// ============================================================================

object AstroImageProcessor {

    private const val LUT_16BIT_SIZE = 65536

    fun build16BitMonotonicSplineLut(points: List<PointF>): FloatArray {
        val ordered = points.sortedBy { it.x }
        require(ordered.isNotEmpty()) { "A curve must contain at least one point." }
        var previousY = 0f
        val sorted = ordered.map { point ->
            val monotonicY = max(previousY, point.y.coerceIn(0f, 1f))
            previousY = monotonicY
            PointF(point.x.coerceIn(0f, 1f), monotonicY)
        }
        val n = sorted.size
        val lut = FloatArray(LUT_16BIT_SIZE)

        if (n == 1) {
            lut.fill(sorted[0].y.coerceIn(0f, 1f))
            return lut
        }

        val dx = FloatArray(n - 1)
        val dy = FloatArray(n - 1)
        val slopes = FloatArray(n - 1)

        for (i in 0 until n - 1) {
            dx[i] = max(1e-5f, sorted[i + 1].x - sorted[i].x)
            dy[i] = sorted[i + 1].y - sorted[i].y
            slopes[i] = dy[i] / dx[i]
        }

        val tangents = FloatArray(n)
        tangents[0] = slopes[0]
        tangents[n - 1] = slopes[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = if (slopes[i - 1] * slopes[i] <= 0f) {
                0f
            } else {
                val previousWidth = dx[i - 1]
                val nextWidth = dx[i]
                val w1 = 2f * nextWidth + previousWidth
                val w2 = nextWidth + 2f * previousWidth
                (w1 + w2) / (w1 / slopes[i - 1] + w2 / slopes[i])
            }
        }

        // Fritsch–Carlson limiter: keep each Hermite segment monotone and reduce
        // the broad, overshooting bends that make nearby curve regions feel linked.
        for (i in slopes.indices) {
            val slope = slopes[i]
            if (slope <= 1e-8f) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
            } else {
                val alpha = tangents[i] / slope
                val beta = tangents[i + 1] / slope
                val magnitude = alpha * alpha + beta * beta
                if (magnitude > 9f) {
                    val factor = 3f / sqrt(magnitude)
                    tangents[i] = factor * alpha * slope
                    tangents[i + 1] = factor * beta * slope
                }
            }
        }

        for (i in 0 until LUT_16BIT_SIZE) {
            val x = i.toFloat() / (LUT_16BIT_SIZE - 1)
            when {
                x <= sorted.first().x -> lut[i] = sorted.first().y.coerceIn(0f, 1f)
                x >= sorted.last().x -> lut[i] = sorted.last().y.coerceIn(0f, 1f)
                else -> {
                    var seg = 0
                    for (j in 0 until n - 1) {
                        if (x >= sorted[j].x && x <= sorted[j + 1].x) {
                            seg = j
                            break
                        }
                    }
                    val h = dx[seg];
                    val t = (x - sorted[seg].x) / h
                    val t2 = t * t
                    val t3 = t2 * t

                    val h00 = 2f * t3 - 3f * t2 + 1f
                    val h10 = t3 - 2f * t2 + t
                    val h01 = -2f * t3 + 3f * t2
                    val h11 = t3 - t2

                    val y = h00 * sorted[seg].y +
                            h10 * h * tangents[seg] +
                            h01 * sorted[seg + 1].y +
                            h11 * h * tangents[seg + 1]

                    lut[i] = y.coerceIn(sorted[seg].y, sorted[seg + 1].y)
                }
            }
        }
        return lut
    }

    fun processAstroMat(inputRgbFloat32: Mat, state: AstroEditState): Bitmap {
        val working = inputRgbFloat32.clone()

        val lutL = build16BitMonotonicSplineLut(state.curveL)
        val lutR = build16BitMonotonicSplineLut(state.curveR)
        val lutG = build16BitMonotonicSplineLut(state.curveG)
        val lutB = build16BitMonotonicSplineLut(state.curveB)

        val totalChannels = (working.total() * working.channels()).toInt()
        val pixels = FloatArray(totalChannels)
        working.get(0, 0, pixels)

        val betaL = 1.0f + state.asinhL * 64.0f
        val betaR = 1.0f + state.asinhR * 64.0f
        val betaG = 1.0f + state.asinhG * 64.0f
        val betaB = 1.0f + state.asinhB * 64.0f
        val normL = asinh(betaL)
        val normR = asinh(betaR)
        val normG = asinh(betaG)
        val normB = asinh(betaB)
        val temperatureGain = exp(state.temperature.coerceIn(-1f, 1f) * 0.28f)
        val purpleGain = exp(state.tint.coerceIn(-1f, 1f) * 0.22f)

        var idx = 0
        while (idx < totalChannels) {
            var r = (pixels[idx] * temperatureGain * purpleGain).coerceIn(0f, 1f)
            var g = (pixels[idx + 1] / purpleGain).coerceIn(0f, 1f)
            var b = (pixels[idx + 2] / temperatureGain * purpleGain).coerceIn(0f, 1f)

            // L applies to luminance so a light stretch doesn't act as an RGB/color stretch.
            val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (state.asinhL > 0.005f && luminance > 1e-6f) {
                val stretchedLuminance = (asinh(luminance * betaL) / normL).coerceIn(0f, 1f)
                val ratio = stretchedLuminance / luminance
                r = (r * ratio).coerceIn(0f, 1f)
                g = (g * ratio).coerceIn(0f, 1f)
                b = (b * ratio).coerceIn(0f, 1f)
            }
            if (state.asinhR > 0.005f) r = (asinh(r * betaR) / normR).coerceIn(0f, 1f)
            if (state.asinhG > 0.005f) g = (asinh(g * betaG) / normG).coerceIn(0f, 1f)
            if (state.asinhB > 0.005f) b = (asinh(b * betaB) / normB).coerceIn(0f, 1f)

            r = lutR[(r * 65535f).roundToInt().coerceIn(0, 65535)]
            g = lutG[(g * 65535f).roundToInt().coerceIn(0, 65535)]
            b = lutB[(b * 65535f).roundToInt().coerceIn(0, 65535)]

            val currentLuminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (currentLuminance > 1e-6f) {
                val mappedLuminance = lutL[(currentLuminance * 65535f).roundToInt().coerceIn(0, 65535)]
                val ratio = mappedLuminance / currentLuminance
                r = (r * ratio).coerceIn(0f, 1f)
                g = (g * ratio).coerceIn(0f, 1f)
                b = (b * ratio).coerceIn(0f, 1f)
            }

            pixels[idx] = r
            pixels[idx + 1] = g
            pixels[idx + 2] = b
            idx += 3
        }
        working.put(0, 0, pixels)

        if (state.noiseReduction > 0.01f) {
            val denoised = Mat()
            val sigmaColor = 0.03 + state.noiseReduction * 0.12
            val sigmaSpace = 3.0 + state.noiseReduction * 5.0
            Imgproc.bilateralFilter(working, denoised, 5, sigmaColor, sigmaSpace)
            denoised.copyTo(working)
            denoised.release()
        }

        if (state.sharpeningSmall > 0.01f) {
            val blurredSmall = Mat()
            Imgproc.GaussianBlur(working, blurredSmall, Size(0.0, 0.0), 1.2)
            val amount = state.sharpeningSmall * 1.6
            Core.addWeighted(working, 1.0 + amount, blurredSmall, -amount, 0.0, working)
            blurredSmall.release()
        }

        if (state.structureLarge > 0.01f) {
            val blurredLarge = Mat()
            Imgproc.GaussianBlur(working, blurredLarge, Size(0.0, 0.0), 24.0)
            val amount = state.structureLarge * 0.95
            Core.addWeighted(working, 1.0 + amount, blurredLarge, -amount, 0.0, working)
            blurredLarge.release()
        }

        if (abs(state.saturation) > 0.01f) {
            val hsv = Mat()
            Imgproc.cvtColor(working, hsv, Imgproc.COLOR_RGB2HSV)
            val channels = ArrayList<Mat>(3)
            Core.split(hsv, channels)
            Core.multiply(channels[1], org.opencv.core.Scalar(1.0 + state.saturation), channels[1])
            Core.min(channels[1], org.opencv.core.Scalar(1.0), channels[1])
            Core.merge(channels, hsv)
            Imgproc.cvtColor(hsv, working, Imgproc.COLOR_HSV2RGB)
            channels.forEach { it.release() }
            hsv.release()
        }

        // Rotate only on explicit user request. Seestar FITS images are often already landscape.
        // The information frame is appended afterward so its text remains upright.
        if (state.isRotated90) {
            Core.rotate(working, working, Core.ROTATE_90_COUNTERCLOCKWISE)
        }

        Core.min(working, org.opencv.core.Scalar(1.0, 1.0, 1.0), working)
        Core.max(working, org.opencv.core.Scalar(0.0, 0.0, 0.0), working)

        val out8U = Mat()
        working.convertTo(out8U, CvType.CV_8UC3, 255.0)
        val bitmap = Bitmap.createBitmap(out8U.cols(), out8U.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out8U, bitmap)

        working.release()
        out8U.release()
        return bitmap
    }
}

// ============================================================================
// 5. SEESTAR S50 INFORMATION BORDER & EDIT AUDIT RENDERER
// ============================================================================

object SeestarFrameRenderer {

    fun appendMetadataBorder(
        photo: Bitmap,
        metadata: SeestarMetadata,
        editState: AstroEditState
    ): Bitmap {
        val width = photo.width
        val borderHeight = max(300, (photo.height * 0.27f).roundToInt())
        val totalHeight = photo.height + borderHeight

        val framedBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(framedBitmap)

        canvas.drawBitmap(photo, 0f, 0f, null)

        val y0 = photo.height.toFloat()
        val padX = width * 0.035f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#080B11")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, y0, width.toFloat(), totalHeight.toFloat(), bgPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            strokeWidth = 2f
        }
        canvas.drawLine(0f, y0, width.toFloat(), y0, linePaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = borderHeight * 0.17f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        drawFittedText(canvas, metadata.objectName, padX, y0 + borderHeight * 0.19f, width * 0.72f, titlePaint)

        val scopePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F59E0B")
            textSize = borderHeight * 0.068f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val captureTime = metadata.dateObs.replace('T', ' ').take(16)
        val deviceAndTime = listOf(shortDeviceModel(metadata.telescope), captureTime)
            .filter { it.isNotBlank() }
            .joinToString("  •  ")
        canvas.drawText(deviceAndTime, width - padX, y0 + borderHeight * 0.31f, scopePaint)

        val cardsTop = y0 + borderHeight * 0.39f
        val cardsBottom = y0 + borderHeight * 0.68f
        val cardGap = width * 0.012f
        val cardWidth = (width - padX * 2f - cardGap * 2f) / 3f
        val integrationMins = metadata.totalExposureSec.roundToInt() / 60
        val integrationSecs = metadata.totalExposureSec.roundToInt() % 60
        val specCards = listOf(
            "STACK • TOTAL" to "${metadata.stackCount} × ${metadata.subExposureSec.roundToInt()}s  ·  ${integrationMins}m ${integrationSecs}s",
            "FILTER" to metadata.filter,
            "GAIN • CCD" to "${metadata.gain}  ·  ${metadata.ccdTempC.roundToInt()}°C"
        )
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.STROKE
            strokeWidth = max(1f, borderHeight * 0.006f)
        }
        val cardLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = borderHeight * 0.055f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val cardValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = borderHeight * 0.072f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        specCards.forEachIndexed { index, (label, value) ->
            val left = padX + index * (cardWidth + cardGap)
            val rect = RectF(left, cardsTop, left + cardWidth, cardsBottom)
            canvas.drawRoundRect(rect, borderHeight * 0.035f, borderHeight * 0.035f, cardFill)
            canvas.drawRoundRect(rect, borderHeight * 0.035f, borderHeight * 0.035f, cardStroke)
            canvas.drawText(label, left + cardWidth * 0.06f, cardsTop + borderHeight * 0.10f, cardLabel)
            drawFittedText(canvas, value, left + cardWidth * 0.06f, cardsTop + borderHeight * 0.225f, cardWidth * 0.88f, cardValue)
        }

        val pillRect = RectF(padX, y0 + borderHeight * 0.75f, width - padX, y0 + borderHeight * 0.96f)
        val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(pillRect, 12f, 12f, pillFill)
        canvas.drawRoundRect(pillRect, 12f, 12f, pillStroke)

        val editLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = borderHeight * 0.047f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("EDITS", padX + 16f, pillRect.top + borderHeight * 0.065f, editLabelPaint)

        val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            textSize = borderHeight * 0.052f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val editTokens = editState.compactEditSummary().removePrefix("TG  •  ").split("   ·   ")
        val editLines = mutableListOf("", "")
        val lineWidth = pillRect.width() - 32f
        editTokens.forEach { token ->
            val candidate = if (editLines[0].isEmpty()) token else "${editLines[0]}   ·   $token"
            if (editPaint.measureText(candidate) <= lineWidth || editLines[0].isEmpty()) {
                editLines[0] = candidate
            } else {
                editLines[1] = if (editLines[1].isEmpty()) token else "${editLines[1]}   ·   $token"
            }
        }
        drawFittedText(canvas, editLines[0], padX + 16f, pillRect.top + borderHeight * 0.13f, lineWidth, editPaint)
        if (editLines[1].isNotBlank()) {
            drawFittedText(canvas, editLines[1], padX + 16f, pillRect.top + borderHeight * 0.195f, lineWidth, editPaint)
        }

        return framedBitmap
    }

    private fun shortDeviceModel(telescope: String): String {
        val match = Regex("S\\d{2}(?:\\s*PRO)?", RegexOption.IGNORE_CASE).find(telescope)?.value
        return match?.replace(Regex("\\s+"), " ")?.uppercase() ?: "SEESTAR"
    }

    private fun drawFittedText(canvas: Canvas, text: String, x: Float, baseline: Float, maxWidth: Float, paint: Paint) {
        var fitted = text
        while (fitted.isNotEmpty() && paint.measureText(fitted) > maxWidth) {
            fitted = fitted.dropLast(1)
        }
        if (fitted != text && fitted.length > 1) fitted = fitted.dropLast(1) + "…"
        canvas.drawText(fitted, x, baseline, paint)
    }
}

// ============================================================================
// 6. GALLERY SAVER (SO SEESTAR COMMUNITY & OTHER APPS SEE IT) + SHARE SHEET
// ============================================================================

object AstroExportHelper {

    fun saveToSeestarGalleryFolder(
        context: Context,
        bitmap: Bitmap,
        objectName: String,
        format: AstroExportFormat,
        quality: Int
    ): Uri? {
        val safeObjectName = objectName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "Astro_Image" }
        val fileName = "${safeObjectName}_TelescopeGlasses_${System.currentTimeMillis()}.${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Seestar")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        val output = resolver.openOutputStream(uri)
        if (output == null) {
            resolver.delete(uri, null, null)
            return null
        }
        val compressed = output.use { outStream ->
            bitmap.compress(format.bitmapCompressFormat(), quality.coerceIn(0, 100), outStream)
        }
        if (!compressed) {
            resolver.delete(uri, null, null)
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    fun launchSystemShareSheet(context: Context, imageUri: Uri, mimeType: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share from Telescope Glasses"))
    }
}

// ============================================================================
// 7. JETPACK COMPOSE INTERACTIVE RGB/L CURVES EDITOR VIEW
// ============================================================================

@Composable
fun AstroCurvesEditor(
    points: List<PointF>,
    channelColor: androidx.compose.ui.graphics.Color,
    onPointsChanged: (List<PointF>) -> Unit,
    histogram: FloatArray? = null,
    modifier: Modifier = Modifier
) {
    var activeDragIndex by remember { mutableIntStateOf(-1) }
    val currentPoints by rememberUpdatedState(points)
    val currentOnPointsChanged by rememberUpdatedState(onPointsChanged)
    val lut = remember(points) { AstroImageProcessor.build16BitMonotonicSplineLut(points) }

    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color(0xFF07090E), RoundedCornerShape(12.dp))
    ) {
        ComposeCanvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            val normX = (tapOffset.x / size.width).coerceIn(0f, 1f)
                            val normY = (1f - tapOffset.y / size.height).coerceIn(0f, 1f)
                            if (currentPoints.size > 2) {
                                val hitIdx = currentPoints.indexOfFirst {
                                    hypot(it.x - normX, it.y - normY) < 0.08f
                                }
                                if (hitIdx in 1 until currentPoints.lastIndex) {
                                    val updated = currentPoints.toMutableList().apply { removeAt(hitIdx) }
                                    currentOnPointsChanged(updated)
                                }
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val normX = (startOffset.x / size.width).coerceIn(0f, 1f)
                            val normY = (1f - startOffset.y / size.height).coerceIn(0f, 1f)
                            val existingIdx = currentPoints.indexOfFirst {
                                hypot(it.x - normX, it.y - normY) < 0.08f
                            }
                            if (existingIdx in 1 until currentPoints.lastIndex) {
                                activeDragIndex = existingIdx
                            } else if (existingIdx == -1 && currentPoints.size < 12) {
                                val sorted = currentPoints.sortedBy { it.x }
                                val insertion = sorted.indexOfFirst { it.x > normX }.let { if (it < 0) sorted.size else it }
                                if (insertion in 1 until sorted.size) {
                                    val left = sorted[insertion - 1]
                                    val right = sorted[insertion]
                                    val x = normX.coerceIn(left.x + 0.02f, right.x - 0.02f)
                                    val y = normY.coerceIn(left.y, right.y)
                                    val newPt = PointF(x, y)
                                    val updated = sorted.toMutableList().apply { add(insertion, newPt) }
                                    currentOnPointsChanged(updated)
                                    activeDragIndex = insertion
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val activePoints = currentPoints
                            if (activeDragIndex in 1 until activePoints.lastIndex) {
                                val normX = (change.position.x / size.width).coerceIn(0f, 1f)
                                val normY = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                val updated = activePoints.toMutableList()
                                val minX = updated[activeDragIndex - 1].x + 0.02f
                                val maxX = updated[activeDragIndex + 1].x - 0.02f
                                val minY = updated[activeDragIndex - 1].y
                                val maxY = updated[activeDragIndex + 1].y
                                updated[activeDragIndex] = PointF(normX.coerceIn(minX, maxX), normY.coerceIn(minY, maxY))
                                currentOnPointsChanged(updated)
                            }
                        },
                        onDragEnd = { activeDragIndex = -1 }
                    )
                }
        ) {
            val w = size.width
            val h = size.height

            val gridColor = androidx.compose.ui.graphics.Color(0xFF1E293B)

            histogram?.let { bins ->
                val peak = bins.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f
                val barWidth = w / bins.size
                bins.forEachIndexed { index, value ->
                    val barHeight = sqrt((value / peak).coerceIn(0f, 1f)) * h * 0.72f
                    drawLine(
                        color = channelColor.copy(alpha = 0.18f),
                        start = Offset((index + 0.5f) * barWidth, h),
                        end = Offset((index + 0.5f) * barWidth, h - barHeight),
                        strokeWidth = max(1f, barWidth * 0.82f)
                    )
                }
            }

            for (i in 1..3) {
                drawLine(gridColor, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 1.dp.toPx())
            }

            val path = Path()
            val steps = 256
            for (i in 0..steps) {
                val x = (i.toFloat() / steps) * w
                val lutIdx = ((i.toFloat() / steps) * 65535f).roundToInt().coerceIn(0, 65535)
                val y = (1f - lut[lutIdx]) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = channelColor, style = Stroke(width = 2.5.dp.toPx()))

            points.forEach { pt ->
                val cx = pt.x * w
                val cy = (1f - pt.y) * h
                drawCircle(androidx.compose.ui.graphics.Color(0xFF0F172A), radius = 6.dp.toPx(), center = Offset(cx, cy))
                drawCircle(channelColor, radius = 6.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}
