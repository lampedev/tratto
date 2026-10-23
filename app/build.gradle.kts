plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.caro.tratto"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.caro.tratto"
        minSdk = 34
        targetSdk = 36
        versionCode = 41
        versionName = "0.41"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // APK installabile direttamente: niente R8, firmato con la chiave di debug.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Un APK per processore oltre a quello universale: per il tablet basta arm64-v8a (le librerie native
    // di Ink per gli altri 3 processori pesano ~4 MB: l'APK universale supera i 30 MB inviabili in chat).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Con il Kotlin integrato di AGP 9 jvmTarget segue già compileOptions.targetCompatibility;
// lo fissiamo comunque esplicitamente a 17.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.bundles.ink)
    implementation(libs.androidx.input.motionprediction)
    implementation(libs.pdfbox.android) {
        // BouncyCastle serve solo ai PDF cifrati con certificato: senza, l'APK è ~8 MB più leggero
        exclude(group = "org.bouncycastle")
    }
}
