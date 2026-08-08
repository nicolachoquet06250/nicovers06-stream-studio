plugins {
    id("com.android.application")
}

android {
    namespace = "fr.nicovers06.streamstudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.nicovers06.streamstudio"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

android.sourceSets.named("main") {
    kotlin.directories += "src/main/kotlin"
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")

    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")

    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // 2.7.2 is the latest RootEncoder line compiled against Android API 36.
    implementation("com.github.pedroSG94.RootEncoder:library:2.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
