import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fierro.ganado"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fierro.ganado"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Leer la clave de local.properties de forma correcta
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }
        val apiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""
        
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true  // ← AGREGAR ESTO: activa la generación de BuildConfig
    }
    androidResources {
        noCompress.add("onnx")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit
    implementation(libs.mlkit.barcode.scanning)

    // LiteRT (Anteriormente TensorFlow Lite, soporta 16 KB page size)
    implementation(libs.litert)
    implementation(libs.litert.support) {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
    implementation(libs.litert.metadata) {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
    implementation(libs.litert.gpu) {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:latest.release")
    
    // OpenCV
    implementation(libs.opencv)

    // OkHttp
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}