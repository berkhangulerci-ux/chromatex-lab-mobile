import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "org.chromatex.lab"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.chromatex.lab"
        minSdk = 26
        targetSdk = 35
        versionCode = 78
        versionName = "0.5.26"
    }

    signingConfigs {
        // Field kiosk tablets use the field cert so OTA updates preserve signature.
        create("field") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val playStorePath = keystoreProps.getProperty("storeFile")
            ?: System.getenv("CHROMATEX_KEYSTORE_FILE")
        if (!playStorePath.isNullOrBlank()) {
            create("playstoreRelease") {
                storeFile = file(playStorePath).let { if (it.exists()) it else rootProject.file(playStorePath) }
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("CHROMATEX_KEYSTORE_PASSWORD")
                    ?: ""
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("CHROMATEX_KEY_ALIAS")
                    ?: ""
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("CHROMATEX_KEY_PASSWORD")
                    ?: ""
            }
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("playstore") {
            dimension = "edition"
            applicationId = "org.chromatex.lab"
            buildConfigField("boolean", "IS_PLAY_STORE", "true")
            signingConfigs.findByName("playstoreRelease")?.let {
                signingConfig = it
            }
        }
        create("kiosk") {
            dimension = "edition"
            applicationId = "org.chromatex.lab.kiosk"
            buildConfigField("boolean", "IS_PLAY_STORE", "false")
            signingConfig = signingConfigs.getByName("field")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.camera.camera2.interop.ExperimentalCamera2Interop",
            "-opt-in=androidx.camera.core.ExperimentalGetImage",
        )
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    val camera = "1.4.0"
    implementation("androidx.camera:camera-core:$camera")
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")
    implementation("androidx.camera:camera-view:$camera")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
