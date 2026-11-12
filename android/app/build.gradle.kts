plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "sh.ahmd.wallflower"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "sh.ahmd.wallflower"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // sideloaded personal build, for now
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":screen"))
    implementation(project(":terminal"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsch)
    implementation(libs.bouncycastle) // Ed25519 host keys, X25519 and chacha20-poly1305 for JSch
    implementation(libs.code.scanner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    constraints {
        implementation(libs.androidx.fragment) // Play services' is too old for the activity result APIs
    }
}
