@file:OptIn(ExperimentalMaterial3Api::class)

package com.fierro.ganado

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fierro.ganado.ui.theme.GanadoTheme
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ──────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

@ExperimentalGetImage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContent {
            GanadoTheme(darkTheme = false, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScannerScreen(cameraExecutor)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

// ─── Estructura de Historial ──────────────────────────────────────────────────
data class HistoryItem(
    val id: Long = System.currentTimeMillis(),
    val result: String,
    val date: String = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
)

@ExperimentalGetImage
@Composable
fun ScannerScreen(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    
    // Estado para la acción activa: null (elección), "camera", "gallery_uri", "history"
    var activeAction by rememberSaveable { mutableStateOf<String?>(null) }

    // Estado para mostrar el tutorial (solo la primera vez)
    val prefs = remember { context.getSharedPreferences("ganado_prefs", Context.MODE_PRIVATE) }
    var showTutorial by rememberSaveable { mutableStateOf(prefs.getBoolean("first_run", true)) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    val historyList = remember { mutableStateListOf<HistoryItem>() }

    if (activeAction != null) {
        BackHandler {
            activeAction = null
        }
    }

    // Lanzador de galería para la pantalla de selección
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            activeAction = "gallery_$uri"
        } else {
            activeAction = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (activeAction == null) {
                SelectionScreen(
                    onCameraClick = { activeAction = "camera" },
                    onGalleryClick = { galleryLauncher.launch("image/*") },
                    onHistoryClick = { activeAction = "history" }
                )
            } else if (activeAction == "camera") {
                if (hasCameraPermission) {
                    CameraPreview(
                        cameraExecutor = cameraExecutor,
                        onBack = { activeAction = null },
                        onResultAdded = { newItem -> historyList.add(0, newItem) }
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Permiso de cámara necesario")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                            Text("Permitir")
                        }
                        TextButton(onClick = { activeAction = null }) {
                            Text("Volver")
                        }
                    }
                }
            } else if (activeAction?.startsWith("gallery_") == true) {
                val uriString = activeAction!!.removePrefix("gallery_")
                val uri = android.net.Uri.parse(uriString)
                CameraPreview(
                    cameraExecutor = cameraExecutor,
                    initialGalleryUri = uri,
                    onBack = { activeAction = null },
                    onResultAdded = { newItem -> historyList.add(0, newItem) }
                )
            } else if (activeAction == "history") {
                HistoryScreen(
                    historyList = historyList,
                    onBack = { activeAction = null }
                )
            }
        }

        // Overlay del Tutorial
        if (showTutorial) {
            OnboardingTutorial {
                showTutorial = false
                prefs.edit().putBoolean("first_run", false).apply()
            }
        }
    }
}

@Composable
fun OnboardingTutorial(onDismiss: () -> Unit) {
    var currentStep by remember { mutableStateOf(0) }
    val steps = listOf(
        TutorialStep(
            "¡Bienvenido a Hato!",
            "Esta herramienta utiliza visión artificial local para ayudarte a identificar tu ganado rápidamente.",
            Icons.Default.Info
        ),
        TutorialStep(
            "Identificación en Vivo",
            "Apunta la cámara al animal. El sistema detectará automáticamente la especie en pantalla.",
            Icons.Default.CameraAlt
        ),
        TutorialStep(
            "Historial y Galería",
            "Consulta tus detecciones pasadas en la pestaña Historial o sube fotos desde tu galería.",
            Icons.Default.History
        )
    )

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = steps[currentStep].icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = steps[currentStep].title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = steps[currentStep].description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(48.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Indicador de pasos
                Text(
                    text = "${currentStep + 1} de ${steps.size}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Button(
                    onClick = {
                        if (currentStep < steps.size - 1) {
                            currentStep++
                        } else {
                            onDismiss()
                        }
                    }
                ) {
                    Text(if (currentStep < steps.size - 1) "Siguiente" else "Comenzar")
                }
            }
        }
    }
}

data class TutorialStep(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun SelectionScreen(onCameraClick: () -> Unit, onGalleryClick: () -> Unit, onHistoryClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo de la App
        Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.hato),
            contentDescription = "Logo Hato",
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(24.dp)), // Esquinas redondeadas suaves
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Bienvenido a Hato",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "¿Cómo deseas tratar a tu ganado?",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onCameraClick,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.camara),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color.Unspecified
            )
            Spacer(Modifier.width(12.dp))
            Text("Usar Cámara en Vivo", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onGalleryClick,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.lupa),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color.Unspecified
            )
            Spacer(Modifier.width(12.dp))
            Text("Cargar desde el Dispositivo", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(50.dp))

        TextButton(
            onClick = onHistoryClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.historial),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color.Unspecified
            )
            Spacer(Modifier.width(8.dp))
            Text("Ver Historial de Análisis", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun HistoryScreen(historyList: List<HistoryItem>, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "Volver")
            }
            Text("Historial de Análisis", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))

        if (historyList.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("No hay análisis recientes", color = Color.Gray)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyList) { item ->
                    var expanded by remember { mutableStateOf(false) }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.hist),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    // Resumen: Primera línea (generalmente la raza)
                                    val summary = item.result.lines().firstOrNull { it.isNotBlank() } ?: "Análisis"
                                    Text(
                                        text = summary,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = item.date,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                            
                            if (expanded) {
                                Spacer(Modifier.height(8.dp))
                                Divider(thickness = 0.5.dp, color = Color.LightGray)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = item.result,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Hora: ${item.date.split(" ").last()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.End),
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Vista principal con cámara ───────────────────────────────────────────────
@ExperimentalGetImage
@Composable
fun CameraPreview(
    cameraExecutor: ExecutorService,
    initialGalleryUri: android.net.Uri? = null,
    onBack: () -> Unit,
    onResultAdded: (HistoryItem) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var scannedText by remember { mutableStateOf("") }
    
    var detecciones by remember { mutableStateOf<List<Deteccion>>(emptyList()) }
    var analysisSize by remember { mutableStateOf(Size.Zero) }
    var previewSize by remember { mutableStateOf(Size.Zero) }
    val textMeasurer = rememberTextMeasurer()

    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(initialGalleryUri) }
    var isProcessingGallery by remember { mutableStateOf(false) }

    // Cerrar el scanner correctamente al salir del Composable
    val scanner = remember { BarcodeScanning.getClient() }
    val detector = remember { DetectorGanado(context) }
    
    // Si entramos con una URI de galería, procesarla inmediatamente
    LaunchedEffect(initialGalleryUri) {
        if (initialGalleryUri != null && detecciones.isEmpty()) {
            isProcessingGallery = true
            try {
                kotlinx.coroutines.delay(1000)
                val inputStream = context.contentResolver.openInputStream(initialGalleryUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    analysisSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                    detecciones = detector.detectar(bitmap)
                    if (detecciones.isNotEmpty()) {
                        val resultString = detecciones.joinToString("\n") { "${it.clase} (${(it.confianza * 100).toInt()}%)" }
                        onResultAdded(HistoryItem(result = resultString))
                    }
                }
            } catch (e: Exception) {
                Log.e("Gallery", "Error", e)
            } finally {
                isProcessingGallery = false
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose { 
            scanner.close()
            detector.cerrar()
        }
    }

    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedImageUri == null) {
            // ── Vista de cámara ──
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                            previewSize = Size((right - left).toFloat(), (bottom - top).toFloat())
                        }
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            // Solo procesar cámara si no hay imagen de galería seleccionada
                            if (selectedImageUri == null) {
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val bitmap = imageProxyToBitmap(imageProxy)
                                if (bitmap != null) {
                                    analysisSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                                    val results = detector.detectar(bitmap)
                                    previewView.post { detecciones = results }
                                }
                            }
                            
                            // Detección de código de barras (opcional mantener aquí)
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            barcode.rawValue?.let { text ->
                                                previewView.post { scannedText = text }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, imageAnalysis, imageCapture
                            )
                        } catch (e: Exception) {
                            Log.e("Camera", "Error al vincular ciclo de vida", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // ── Vista de Imagen de Galería ──
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                        BitmapFactory.decodeStream(context.contentResolver.openInputStream(selectedImageUri!!))?.asImageBitmap() ?: Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).asImageBitmap()
                    ),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
                
                if (isProcessingGallery) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(16.dp))
                            Text("Identificando especies...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }

        // ── Capa de dibujo de detecciones ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (analysisSize.width > 0 && analysisSize.height > 0) {
                val scaleX = size.width / analysisSize.width
                val scaleY = size.height / analysisSize.height

                detecciones.forEach { det ->
                    val scaledLeft = det.left * scaleX
                    val scaledTop = det.top * scaleY
                    val scaledRight = det.right * scaleX
                    val scaledBottom = det.bottom * scaleY

                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(scaledLeft, scaledTop),
                        size = Size(scaledRight - scaledLeft, scaledBottom - scaledTop),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${det.clase} ${(det.confianza * 100).toInt()}%",
                        topLeft = Offset(scaledLeft, (scaledTop - 25.dp.toPx()).coerceAtLeast(0f)),
                        style = TextStyle(color = Color.Green, fontSize = 14.sp)
                    )
                }
            }
        }

        // ── Código escaneado ──
        if (scannedText.isNotEmpty()) {
            Card(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                Text("Escaneado: $scannedText", modifier = Modifier.padding(16.dp))
            }
        }

        // ── Botones inferiores ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón captura (solo guarda foto)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(4.dp)
                    .background(Color.White, CircleShape)
                    .clickable {
                        val resultString = if (detecciones.isEmpty()) "Sin detecciones" 
                                           else detecciones.joinToString("\n") { "${it.clase} (${(it.confianza * 100).toInt()}%)" }
                        onResultAdded(HistoryItem(result = resultString))
                        takePhoto(context, imageCapture, cameraExecutor, detecciones, analysisSize)
                    }
            )
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    if (image.format == android.graphics.ImageFormat.YUV_420_888) {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else {
        // Fallback para otros formatos (como RGBA_8888 si se capturó así)
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    detecciones: List<Deteccion>,
    analysisSize: Size
) {
    imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bitmap = imageProxyToBitmap(image)
            image.close()

            if (bitmap == null) return

            // Crear un bitmap mutable para poder dibujar encima
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(mutableBitmap)

            // Configurar pinceles
            val paint = Paint().apply {
                color = android.graphics.Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = bitmap.width / 150f // Grosor dinámico según resolución
                isAntiAlias = true
            }

            val textPaint = Paint().apply {
                color = android.graphics.Color.GREEN
                textSize = bitmap.width / 35f // Tamaño de texto dinámico
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }

            // Dibujar cada detección escalada
            if (analysisSize.width > 0 && analysisSize.height > 0) {
                val scaleX = bitmap.width.toFloat() / analysisSize.width
                val scaleY = bitmap.height.toFloat() / analysisSize.height

                detecciones.forEach { det ->
                    val left = det.left * scaleX
                    val top = det.top * scaleY
                    val right = det.right * scaleX
                    val bottom = det.bottom * scaleY

                    canvas.drawRect(left, top, right, bottom, paint)
                    canvas.drawText(
                        "${det.clase} ${(det.confianza * 100).toInt()}%",
                        left,
                        (top - (bitmap.width / 100f)).coerceAtLeast(textPaint.textSize),
                        textPaint
                    )
                }
            }

            saveBitmapToGallery(context, mutableBitmap)
        }

        override fun onError(e: ImageCaptureException) {
            Log.e("Camera", "Error al capturar foto", e)
        }
    })
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
    val name = "IMG_HATO_${System.currentTimeMillis()}.jpg"
    val folderName = "Hato"

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folderName")
        }
    }

    val resolver = context.contentResolver
    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    imageUri?.let { uri ->
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            (context as? MainActivity)?.runOnUiThread {
                Toast.makeText(context, "Foto guardada con éxito en $folderName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("Camera", "Error al guardar bitmap", e)
        }
    }
}

@ExperimentalGetImage
private fun processImageProxy(
    scanner: BarcodeScanner,
    imageProxy: ImageProxy,
    onSuccess: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) barcode.rawValue?.let { onSuccess(it) }
            }
            .addOnFailureListener { imageProxy.close() }
            .addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}