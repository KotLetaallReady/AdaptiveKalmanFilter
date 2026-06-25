plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module with the sensor-source abstraction
// (Flow<Observation>). Android implementations live outside this module.
dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
