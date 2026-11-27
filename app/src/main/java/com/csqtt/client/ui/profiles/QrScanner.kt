package com.csqtt.client.ui.profiles

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.csqtt.client.R
import com.csqtt.client.ui.components.Btn
import com.csqtt.client.ui.components.BtnKind
import com.csqtt.client.ui.components.IconBtn
import com.csqtt.client.ui.components.MessageHost
import com.csqtt.client.ui.design.DarkPalette
import com.csqtt.client.ui.design.Icons
import com.csqtt.client.ui.design.LocalPalette
import com.csqtt.client.ui.design.Type
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import com.csqtt.client.ui.shell.LocalNav
import com.csqtt.client.ui.shell.LocalOwners
import com.csqtt.client.ui.shell.PendingLink
import com.csqtt.client.ui.shell.Routes
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.detector.Detector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val QrHints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

/**
 * The text of the QR code in a picture, [pixels] being its ARGB rows; null when there is none to read. The reader the
 * camera uses, tried on the picture as it is, with the other way of telling dark from light, and inverted (a light code
 * on a dark ground).
 */
internal fun decodeQrPixels(width: Int, height: Int, pixels: IntArray): String? {
    val source: LuminanceSource = RGBLuminanceSource(width, height, pixels)
    val takes = listOf({ HybridBinarizer(source) }, { GlobalHistogramBinarizer(source) }, { HybridBinarizer(source.invert()) })
    return takes.firstNotNullOfOrNull { take ->
        runCatching { MultiFormatReader().decode(BinaryBitmap(take()), QrHints).text }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** «Из галереи»: the code in the picked picture, read at no more than about 2 000 px a side. */
private fun decodeQrImage(context: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)
        ?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val (width, height) = bitmap.width to bitmap.height
    bitmap.recycle()
    decodeQrPixels(width, height, pixels)
}.getOrNull()

/**
 * The QR scanner (the canvas, F-QrScanner): the camera, or a saved picture through «Из галереи». A code that holds a
 * csqtt:// link goes to the link's question; any other is said to be one, and the scanning goes on. Without the camera's
 * permission the picture still works.
 */
@Composable
internal fun QrScanner(close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val nav = LocalNav.current
    val messages = LocalOwners.current.messages
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val foreign = stringResource(R.string.qr_foreign)
    val noCode = stringResource(R.string.qr_image_none)

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val activity = LocalActivity.current
    var camera by remember { mutableStateOf(granted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    // «Разрешить» was pressed while Android would no longer show its question (it gives no rationale after a refusal
    // it took as final): the refusal that comes back then was not the user's answer to anything
    var notAsking by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { given ->
        camera = given
        // the app's page in the system settings is where the permission is given then
        if (!given && notAsking) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        notAsking = false
    }
    LaunchedEffect(Unit) {
        if (!camera && !asked) {
            asked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    // given in the system settings meanwhile: seen on the return
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { camera = granted() } }

    /** True when the code was a link and the scanner is done; false resumes the scanning. */
    fun take(text: String, missing: String): Boolean {
        val raw = text.trim()
        if (parseCsqttLink(raw) == null) {
            messages.show(missing)
            return false
        }
        // the same question as a link from the browser (M-70): never written without asking
        nav.closeRoutes(Routes.QR_SCANNER, Routes.ADD_PROFILE)
        nav.open(Routes.linkImport(PendingLink.put(context, raw)))
        return true
    }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) { decodeQrImage(context, uri) }
                take(text.orEmpty(), noCode)
            }
        }
    }

    Dialog(
        onDismissRequest = close,
        // the camera's picture fills the screen, under the system's bars too; what is drawn over it keeps clear of them
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        // the scanner is dark whatever the app's theme: its pieces are drawn in the dark palette
        CompositionLocalProvider(LocalPalette provides DarkPalette) {
        Box(Modifier.fillMaxSize().background(ScannerGround)) {
            var zoom by remember { mutableFloatStateOf(1f) }
            var active by remember { mutableStateOf<Camera?>(null) }
            var frame by remember { mutableStateOf(Rect.Zero) }
            if (camera) {
                CameraView({ take(it, foreign) }, { active = it }, { zoom = it })
                // the picture around the frame is dimmed, so the frame is where the eye goes and the text over the
                // picture is read
                Box(
                    Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawBehind {
                        drawRect(Color.Black.copy(alpha = 0.45f))
                        drawRoundRect(
                            Color.Black, topLeft = frame.topLeft, size = frame.size, cornerRadius = CornerRadius(28.dp.toPx()),
                            blendMode = BlendMode.Clear,
                        )
                    },
                )
            }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.qr_title), style = Type.dialogTitle, color = Color.White,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconBtn(Icons.close, stringResource(R.string.action_close), close, tint = Color.White)
                }
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Box(
                        Modifier.size(260.dp).onGloballyPositioned { frame = it.boundsInRoot() }
                            .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!camera) {
                            Column(
                                Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(stringResource(R.string.qr_no_camera), style = Type.fact, color = Color.White, textAlign = TextAlign.Center)
                                Btn(
                                    stringResource(R.string.qr_allow_camera),
                                    {
                                        notAsking = activity?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } ?: false
                                        permission.launch(Manifest.permission.CAMERA)
                                    },
                                    kind = BtnKind.Primary, small = true,
                                )
                            }
                        }
                    }
                    if (camera) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (level in listOf(1f, 2f, 3f)) {
                                val on = zoom - level in -0.3f..0.3f
                                Text(
                                    stringResource(R.string.qr_zoom, level.toInt()), style = Type.buttonSmall,
                                    color = if (on) ScannerInk else Color.White,
                                    modifier = Modifier.clip(RoundedCornerShape(percent = 50))
                                        .background(if (on) Color.White else Color.White.copy(alpha = 0.2f))
                                        .selectable(selected = on, role = Role.RadioButton) { active?.cameraControl?.setZoomRatio(level) }
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 34.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.qr_hint), style = Type.fact, color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp),
                        )
                    }
                    Btn(stringResource(R.string.qr_from_gallery), { picture.launch("image/*") }, icon = Icons.image)
                }
                // the scanner is a window over the app's: what it says is drawn in it
                MessageHost(messages, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            }
        }
        }
    }
}

private val ScannerGround = Color(0xFF1A1D23)
private val ScannerInk = Color(0xFF1C2230)

/** The camera's picture with the reader on it. [onText] says whether the code was taken; when not, the reading resumes after 2 s. */
@Composable
private fun CameraView(onText: (String) -> Boolean, onCamera: (Camera?) -> Unit, onZoom: (Float) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val taken by rememberUpdatedState(onText)
    var scanned by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<Camera?>(null) }
    var bound by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val gone = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            // the camera is the activity's, not this window's: it is let go with the scanner, not left on behind it —
            // and a start that ends after the scanner has closed binds nothing
            gone.set(true)
            runCatching { bound?.unbindAll() }
            executor.shutdown()
            onCamera(null)
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val state = active?.cameraInfo?.zoomState?.value ?: return false
                    val target = (state.zoomRatio * detector.scaleFactor).coerceIn(state.minZoomRatio, min(state.maxZoomRatio, 6f))
                    active?.cameraControl?.setZoomRatio(target)
                    return true
                }
            })
            view.setOnTouchListener { touched, event ->
                pinch.onTouchEvent(event)
                if (event.action == android.view.MotionEvent.ACTION_UP) touched.performClick()
                true
            }
            val providers = ProcessCameraProvider.getInstance(ctx)
            providers.addListener({
                // a camera that fails to start leaves the scanner with «Из галереи»; it throws nothing here
                val provider = runCatching { providers.get() }.getOrNull()
                if (provider == null || gone.get()) return@addListener
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val reader = MultiFormatReader().apply { setHints(QrHints) }
                var lastAutoZoom = 0L
                analysis.setAnalyzer(executor) { image: ImageProxy ->
                    if (scanned) {
                        image.close()
                        return@setAnalyzer
                    }
                    val buffer = image.planes[0].buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    val (width, height) = image.width to image.height
                    val bitmap = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)))
                    try {
                        val text = reader.decodeWithState(bitmap).text
                        if (!scanned && !text.isNullOrBlank()) {
                            scanned = true
                            // a code that is not a link: said, and the reading goes on after a pause
                            view.post { if (!taken(text)) view.postDelayed({ scanned = false }, 2_000L) }
                        }
                    } catch (_: NotFoundException) {
                        // a code seen but too small to read: zoom in on it
                        val now = System.currentTimeMillis()
                        if (now - lastAutoZoom > 800) {
                            runCatching {
                                val points = Detector(bitmap.blackMatrix).detect().points
                                if (points != null && points.size >= 3) {
                                    val side = max(
                                        hypot(points[0].x - points[1].x, points[0].y - points[1].y),
                                        hypot(points[1].x - points[2].x, points[1].y - points[2].y),
                                    )
                                    val frame = min(width, height)
                                    val current = active?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                                    val wanted = (frame * 0.48f / side).coerceIn(1.5f, 3.5f)
                                    if (side > 15 && side < frame * 0.30f && wanted > current + 0.35f) {
                                        lastAutoZoom = now
                                        active?.cameraControl?.setZoomRatio(wanted)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        image.close()
                    }
                }
                runCatching {
                    provider.unbindAll()
                    val opened = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    bound = provider
                    active = opened
                    onCamera(opened)
                    opened.cameraInfo.zoomState.observe(owner) { onZoom(it.zoomRatio) }
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
