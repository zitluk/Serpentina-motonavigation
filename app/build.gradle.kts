plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.motonavigace"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.motonavigace"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
        resources.excludes += "META-INF/*"
    }
}

val mapsforge = "0.25.0"
val vtm = "0.25.0"
val brouter = "v1.7.9"

dependencies {
    // Offline vektorové mapy: VTM (OpenGL, 3D) nad soubory Mapsforge
    implementation("org.mapsforge:vtm:$vtm")
    implementation("org.mapsforge:vtm-themes:$vtm")
    implementation("org.mapsforge:vtm-android:$vtm")
    for (abi in listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
        runtimeOnly("org.mapsforge:vtm-android:$vtm:natives-$abi")
    }
    implementation("com.caverock:androidsvg:1.4")
    // Jen geometrie (LatLong, vzdálenosti) pro routing a hledání
    implementation("org.mapsforge:mapsforge-core:$mapsforge")

    // Offline routing
    for (m in listOf("core", "mapaccess", "expressions", "util", "codec")) {
        implementation("com.github.abrensch.brouter:brouter-$m:$brouter")
    }

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
