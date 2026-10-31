plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Výstupy buildu mimo OneDrive – synchronizace by kolidovala s Gradle cache
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/Library/Caches/Motonavigace/build/${project.name}"))
}
