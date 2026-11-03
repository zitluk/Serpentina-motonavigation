plugins {
    // AGP 9 má Kotlin vestavěný, plugin org.jetbrains.kotlin.android se už nepoužívá
    id("com.android.application")
}

android {
    namespace = "cz.motonavigace"
    // Překlad proti Androidu 17, cílení na Android 17 – běží od Androidu 8.0 (minSdk 26)
    compileSdk = 37

    defaultConfig {
        applicationId = "cz.motonavigace"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "0.4.3"
    }

    // Klíč pro vydání je jen na počítači vývojáře (~/.gradle/gradle.properties), v repozitáři není;
    // bez něj se vydání sestaví nepodepsané
    val storeFilePath = providers.gradleProperty("serpentinaStoreFile").orNull
    signingConfigs {
        if (storeFilePath != null) create("release") {
            storeFile = file(storeFilePath)
            storePassword = providers.gradleProperty("serpentinaStorePassword").get()
            keyAlias = providers.gradleProperty("serpentinaKeyAlias").get()
            keyPassword = providers.gradleProperty("serpentinaKeyPassword").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
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

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // org.json je v Androidu vestavěný, v testech na JVM jsou jen prázdné stuby
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        // @StringRes apod. na parametrech konstruktoru platí i pro vlastnost (budoucí výchozí chování Kotlinu)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}
