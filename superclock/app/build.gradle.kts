import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "org.dalwadi.superclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.dalwadi.superclock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Every Android phone is one of these two. Shipping the x86 and mips
            // variants of libvosk would add ~20 MB that no handset can load.
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Vosk reaches native code through JNA reflection; shrinking is not worth
            // the risk on a sideloaded personal build.
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // The Vosk model ships as a single already-compressed archive. Storing it
    // uncompressed keeps aapt from re-deflating 40 MB of deflated data and lets
    // ModelInstaller stream it straight out of the APK.
    androidResources {
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
        }
    }
}

// The speech model is fetched, not committed (see scripts/fetch-model.sh). Without this
// check the build happily produces an APK that installs and then cannot load the model at
// runtime — a far worse failure than not building at all.
tasks.named("preBuild") {
    doFirst {
        val model = file("src/main/assets/model-en-us.zip")
        if (!model.exists()) {
            throw GradleException(
                "Speech model missing: ${model.path}\n" +
                "Run ./scripts/fetch-model.sh first (downloads ~125 MB)."
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.alphacephei:vosk-android:0.3.47")

    testImplementation("junit:junit:4.13.2")
}
