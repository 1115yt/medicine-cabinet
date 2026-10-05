package app.medicinecabinet.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import app.medicinecabinet.domain.CodeFormat
import app.medicinecabinet.domain.ParsedBarcode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onClose: () -> Unit, onManual: () -> Unit, onCode: (ParsedBarcode) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) launcher.launch(Manifest.permission.CAMERA) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val codeCallback by rememberUpdatedState(onCode)
    val session = remember { ScannerSession({ codeCallback(it) }, { cameraError = it }) }
    fun close() { session.close(); onClose() }
    fun manual() { session.close(); onManual() }
    BackHandler(onBack = ::close)
    DisposableEffect(session) { onDispose { session.close() } }
    Scaffold(topBar = { TopAppBar(title = { Text("扫描商品条码") }, navigationIcon = {
        IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回药箱") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (allowed) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CameraPreview(Modifier.fillMaxSize(), session)
                    Box(Modifier.size(250.dp).border(2.dp, Color.White, RoundedCornerShape(24.dp)))
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("扫码需要使用摄像头", style = MaterialTheme.typography.titleLarge)
                    Text("也可以直接手动录入药品。", Modifier.padding(vertical = 12.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("允许摄像头") }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface) {
                ScannerGuidance(cameraError, ::manual)
            }
        }
    }
}

@Composable
internal fun ScannerGuidance(error: String?, onManual: () -> Unit) {
    // 大字号和横屏时说明区独立滚动，保留取景空间和手动录入入口。
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
        .padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(error ?: "将药盒商品条码放入取景框", modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
        }, style = MaterialTheme.typography.titleMedium,
            color = if (error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        Text("追溯码和商品码可能不同。本应用记录条码，不查询药品真伪；识别后请核对包装信息。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("改用手动录入") }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraPreview(modifier: Modifier, session: ScannerSession) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(context) { PreviewView(context) }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(owner, view, session) {
        val executor = Executors.newSingleThreadExecutor()
        val processing = AtomicBoolean(false)
        val active = AtomicBoolean(true)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
        ).build())
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null || !active.get() || !session.acceptsFrames() || !processing.compareAndSet(false, true)) {
                proxy.close()
            } else {
                val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(input).addOnSuccessListener { results ->
                    if (!active.get()) return@addOnSuccessListener
                    for (code in results) {
                        val raw = code.rawValue ?: continue
                        if (raw.isBlank()) continue
                        val format = when (code.format) {
                            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E -> CodeFormat.RETAIL
                            Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX -> CodeFormat.GS1_CAPABLE
                            else -> CodeFormat.OTHER
                        }
                        if (session.submit(raw, format)) break
                    }
                }.addOnFailureListener {
                    if (active.get()) session.reportError("识别失败，请调整距离，或使用手动录入。")
                }
                    .addOnCompleteListener { proxy.close(); processing.set(false) }
            }
        }
        future.addListener({
            if (active.get() && session.acceptsFrames()) {
                runCatching {
                    provider = future.get()
                    provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { session.reportError("无法打开摄像头，请使用手动录入。") }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active.set(false)
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            scanner.close()
            executor.shutdown()
        }
    }
}
