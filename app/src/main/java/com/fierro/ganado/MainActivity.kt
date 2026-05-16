@file:OptIn(ExperimentalMaterial3Api::class)

package com.fierro.ganado

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ─── API Key ───────────────────────────────────────────────────────────────────
// Obtener la API Key desde BuildConfig (configurada en build.gradle y local.properties)
private val API_KEY: String = BuildConfig.GEMINI_API_KEY
private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="

// ─── Cliente HTTP singleton ────────────────────────────────────────────────────
private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

// ──────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

@ExperimentalGetImage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContent {
            GanadoTheme {
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

// ─── Llamada a Gemini 1.5 Pro ─────────────────────────────────────────────────
suspend fun analyzeImageWithGemini(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

    // Construir body con JSONObject para evitar errores de escapado
    val textPart = JSONObject().apply {
        put("text",
            "Analiza este animal de ganado de forma muy breve y resumida. " +
                    "Responde en este formato exacto:\n" +
                    "• RAZA: [Nombre]\n" +
                    "• PESO: [Número] kg\n" +
                    "• SALUD: [Estado breve]\n" +
                    "• NOTA: [Una observación corta]"
        )
    }

    val imagePart = JSONObject().apply {
        put("inline_data", JSONObject().apply {
            put("mime_type", "image/jpeg")
            put("data", base64Image)
        })
    }

    val body = JSONObject().apply {
        put("contents", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("parts", org.json.JSONArray().apply {
                    put(textPart)
                    put(imagePart)
                })
            })
        })
    }

    val request = Request.Builder()
        .url("$GEMINI_URL$API_KEY")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val response = httpClient.newCall(request).execute()
    val responseBody = response.body?.string() ?: throw Exception("Sin respuesta del servidor")

    if (!response.isSuccessful) throw Exception("Error ${response.code}: $responseBody")

    JSONObject(responseBody)
        .getJSONArray("candidates")
        .getJSONObject(0)
        .getJSONObject("content")
        .getJSONArray("parts")
        .getJSONObject(0)
        .getString("text")
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

    var selectedTab by remember { mutableStateOf(0) }
    val historyList = remember { mutableStateListOf<HistoryItem>() }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Scanner") },
                        label = { Text("Scanner") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.History, contentDescription = "Historial") },
                        label = { Text("Historial") }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (selectedTab == 0) {
                    if (hasCameraPermission) {
                        CameraPreview(cameraExecutor) { newItem ->
                            historyList.add(0, newItem)
                        }
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
                        }
                    }
                } else {
                    HistoryScreen(historyList)
                }
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
            "Esta herramienta utiliza IA para ayudarte a identificar y evaluar tu ganado rápidamente.",
            Icons.Default.Info
        ),
        TutorialStep(
            "Escanea tu Ganado",
            "Apunta la cámara al animal. Intenta que se vea de perfil para mejores resultados de peso.",
            Icons.Default.CameraAlt
        ),
        TutorialStep(
            "Análisis con IA",
            "Toca el botón con estrellas (AutoAwesome) para que la IA identifique la raza, peso y salud.",
            Icons.Default.AutoAwesome
        ),
        TutorialStep(
            "Historial y Galería",
            "Consulta tus análisis pasados en la pestaña Historial o sube fotos desde tu galería.",
            Icons.Default.History
        )
    )

    Surface(
        color = Color.Black.copy(alpha = 0.85f),
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
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = steps[currentStep].description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
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
                    color = Color.White.copy(alpha = 0.6f)
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
fun HistoryScreen(historyList: List<HistoryItem>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Historial de Análisis", style = MaterialTheme.typography.headlineMedium)
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
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Resumen: Primera línea (generalmente la raza)
                                val summary = item.result.lines().firstOrNull { it.isNotBlank() } ?: "Análisis"
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.date.split(" ").first(), // Solo la fecha
                                    style = MaterialTheme.typography.labelSmall
                                )
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
fun CameraPreview(cameraExecutor: ExecutorService, onResultAdded: (HistoryItem) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var scannedText by remember { mutableStateOf("") }
    var aiResponse by remember { mutableStateOf<String?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()

    // Cerrar el scanner correctamente al salir del Composable
    val scanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

    val imageCapture = remember { ImageCapture.Builder().build() }

    // ── Lanzador de galería ──
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            if (isAnalyzing) return@let
            isAnalyzing = true
            showSheet = true
            aiResponse = "Analizando imagen de galería..."
            scope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        val response = analyzeImageWithGemini(bitmap)
                        aiResponse = response
                        onResultAdded(HistoryItem(result = response))
                    } else {
                        aiResponse = "Error: No se pudo cargar la imagen"
                    }
                } catch (e: Exception) {
                    aiResponse = "Error: ${e.message}"
                } finally {
                    isAnalyzing = false
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Vista de cámara ──
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                        processImageProxy(scanner, imageProxy) { scannedText = it }
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
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón galería
            IconButton(
                onClick = { if (!isAnalyzing) galleryLauncher.launch("image/*") },
                enabled = !isAnalyzing,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(0.5f), CircleShape)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Galería", tint = Color.White)
            }

            // Botón captura (solo guarda foto)
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(4.dp)
                    .background(Color.White, CircleShape)
                    .clickable(enabled = !isAnalyzing) {
                        takePhoto(context, imageCapture, cameraExecutor)
                    }
            )

            // Botón análisis IA
            IconButton(
                onClick = {
                    if (isAnalyzing) return@IconButton
                    isAnalyzing = true
                    showSheet = true
                    aiResponse = "Analizando imagen..."
                    imageCapture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bitmap = imageProxyToBitmap(image)
                                image.close()
                                scope.launch {
                                    try {
                                        if (bitmap == null) throw Exception("No se pudo procesar la imagen")
                                        val response = analyzeImageWithGemini(bitmap)
                                        aiResponse = response
                                        onResultAdded(HistoryItem(result = response))
                                    } catch (e: Exception) {
                                        aiResponse = "Error: ${e.message}"
                                    } finally {
                                        isAnalyzing = false
                                    }
                                }
                            }
                            override fun onError(e: ImageCaptureException) {
                                aiResponse = "Error al capturar: ${e.message}"
                                isAnalyzing = false
                            }
                        }
                    )
                },
                enabled = !isAnalyzing,
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Analizar con IA", tint = Color.White)
                }
            }
        }

        // ── Bottom sheet con resultado ──
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 48.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Análisis IA", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))

                    if (isAnalyzing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(aiResponse ?: "Procesando...")
                        }
                    } else {
                        Text(aiResponse ?: "Sin resultado")
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun takePhoto(context: Context, imageCapture: ImageCapture, executor: ExecutorService) {
    val name = "IMG_${System.currentTimeMillis()}.jpg"
    val folderName = "Hato" // Nombre de tu carpeta personalizada

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Esto creará automáticamente la carpeta dentro de Pictures
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folderName")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onError(e: ImageCaptureException) {
            Log.e("Camera", "Error al guardar foto", e)
        }
        override fun onImageSaved(o: ImageCapture.OutputFileResults) {
            (context as? MainActivity)?.runOnUiThread {
                Toast.makeText(context, "Foto guardada en Pictures/$folderName", Toast.LENGTH_SHORT).show()
            }
        }
    })
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