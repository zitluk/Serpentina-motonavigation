plugins {
    id("com.android.application") version "9.5.0" apply false
}

// Výstupy buildu mimo OneDrive – synchronizace by kolidovala s Gradle cache
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/Library/Caches/Motonavigace/build/${project.name}"))
}
