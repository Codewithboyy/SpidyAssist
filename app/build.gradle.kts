import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // The modern Compose Compiler tracking plugin which eliminates the old manual composeOptions block
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties()
val keystoreFile = rootProject.file("release.properties")

if (keystoreFile.exists()) {
    keystoreProps.load(FileInputStream(keystoreFile))
}

android {
    namespace = "com.spydr.spidy"
    // Target SDK 36 requires using Java 17+ tooling rules
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spydr.spidy"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        // Upgraded bytecode toolchain targets to Java 17 to meet targetSdk 36 build platform mandates
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Essential safeguard: Prevent Vosk native JNI engine components (.so libs) 
            // from being corrupted or improperly compressed during production packaging
            useLegacyPackaging = true
            pickFirsts += "lib/**/libvosk_jni.so"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // Jetpack Compose BOM Integration Lane
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))

    // Android Core KTX Layer
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Android Architecture Components Lifecycle Layer
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // UI & Core Graphics Elements Deck
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    
    // Design Componentry Infrastructure
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Asynchronous Flow Execution Context
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Offline Voice Processing Engine Model Artifact (Target API 34+ AAR Release wrapper)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Canvas Prototyping Diagnostics Tools Loop
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
