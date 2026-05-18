package com.fierro.ganado

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.min

data class Deteccion(
    val clase: String,
    val confianza: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

class DetectorGanado(context: Context) {
    
    private var session: OrtSession? = null
    private val isInitialized: Boolean
        get() = session != null
    
    private val clases = listOf("BOVINO", "EQUINO", "PORCINO", "OVINO", "CAPRINO")
    private val inputSize = 640
    
    init {
        inicializarModelo(context)
    }
    
    private fun inicializarModelo(context: Context) {
        try {
            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                addConfigEntry("execution_mode", "sequential")
            }
            
            val modelBytes = context.resources.openRawResource(R.raw.best).readBytes()
            session = env.createSession(modelBytes, sessionOptions)
            
            android.util.Log.d("DetectorGanado", "✅ Modelo ONNX cargado correctamente")
        } catch (e: Exception) {
            android.util.Log.e("DetectorGanado", "❌ Error al cargar modelo: ${e.message}")
        }
    }
    
    fun detectar(bitmap: Bitmap, umbralConfianza: Float = 0.5f): List<Deteccion> {
        if (!isInitialized) return emptyList()
        
        return try {
            // Redimensionar imagen a 640x640
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // Convertir a FloatBuffer (normalizar a [0, 1])
            val inputBuffer = bitmapToFloatBuffer(resized)
            
            // Crear tensor de entrada
            val inputTensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                inputBuffer,
                longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            )
            
            // Ejecutar modelo
            val outputs = session?.run(mapOf("images" to inputTensor))
            
            // Procesar resultados
            val detecciones = mutableListOf<Deteccion>()
            
            if (outputs != null && outputs.size() > 0) {
                // En YOLOv8 ONNX, la salida suele ser [1, 84, 8400] o similar (depende del modelo)
                // Ajustamos el procesamiento según el formato esperado del usuario
                val outputValue = outputs.get(0).value
                
                // El procesamiento de salida de YOLOv8 es complejo (NMS, etc.)
                // Aquí mantenemos la lógica simplificada del usuario pero corregida para el API
                if (outputValue is Array<*>) {
                    // YOLOv8 ONNX suele ser float[1][84][8400]
                    val outputArray = outputValue[0] as Array<FloatArray>
                    val numDetections = outputArray[0].size // 8400
                    val numElements = outputArray.size // 84 (4 boxes + 80 classes)
                    
                    val tempDetecciones = mutableListOf<Deteccion>()
                    
                    for (i in 0 until numDetections) {
                        var maxConf = 0f
                        var classId = -1
                        
                        for (j in 4 until numElements) {
                            if (outputArray[j][i] > maxConf) {
                                maxConf = outputArray[j][i]
                                classId = j - 4
                            }
                        }
                        
                        if (maxConf > umbralConfianza) {
                            val x = outputArray[0][i]
                            val y = outputArray[1][i]
                            val w = outputArray[2][i]
                            val h = outputArray[3][i]
                            
                            val scaleX = bitmap.width.toFloat() / inputSize
                            val scaleY = bitmap.height.toFloat() / inputSize
                            
                            val left = ((x - w/2) * scaleX).coerceIn(0f, bitmap.width.toFloat())
                            val top = ((y - h/2) * scaleY).coerceIn(0f, bitmap.height.toFloat())
                            val right = ((x + w/2) * scaleX).coerceIn(0f, bitmap.width.toFloat())
                            val bottom = ((y + h/2) * scaleY).coerceIn(0f, bitmap.height.toFloat())
                            
                            val clase = if (classId in clases.indices) clases[classId] else "DESCONOCIDO"
                            tempDetecciones.add(Deteccion(clase, maxConf, left, top, right, bottom))
                        }
                    }
                    
                    // NMS Simplificado (opcional pero recomendado)
                    detecciones.addAll(aplicarNMS(tempDetecciones))
                }
            }
            
            inputTensor.close()
            outputs?.close()
            detecciones
            
        } catch (e: Exception) {
            android.util.Log.e("DetectorGanado", "Error en detección: ${e.message}")
            emptyList()
        }
    }
    
    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(3 * inputSize * inputSize)
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        
        // Formato NCHW (Planar)
        // Red
        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16) and 0xFF).toFloat() / 255f
            buffer.put(i, r)
        }
        // Green
        for (i in pixels.indices) {
            val g = ((pixels[i] shr 8) and 0xFF).toFloat() / 255f
            buffer.put(inputSize * inputSize + i, g)
        }
        // Blue
        for (i in pixels.indices) {
            val b = (pixels[i] and 0xFF).toFloat() / 255f
            buffer.put(2 * inputSize * inputSize + i, b)
        }
        
        buffer.rewind()
        return buffer
    }
    
    private fun aplicarNMS(detecciones: List<Deteccion>): List<Deteccion> {
        val sorted = detecciones.sortedByDescending { it.confianza }.toMutableList()
        val results = mutableListOf<Deteccion>()
        
        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            results.add(first)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calcularIoU(first, next) > 0.45f) {
                    iterator.remove()
                }
            }
        }
        return results
    }
    
    private fun calcularIoU(a: Deteccion, b: Deteccion): Float {
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = areaA + areaB - intersection
        
        return if (union > 0) intersection / union else 0f
    }

    fun cerrar() {
        session?.close()
        session = null
    }
}
