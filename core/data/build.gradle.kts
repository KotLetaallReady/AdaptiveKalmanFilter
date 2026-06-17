plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module — the sensor-source abstraction (Flow<Observation>).
// The Android implementation lives in :app, so this stays framework-free.
dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
