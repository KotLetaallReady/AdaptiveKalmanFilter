plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module: Kalman filter, neural trajectory smoother, metrics
// and math. Android specifics are injected through interfaces and implemented
// in :core:android or the demo app.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
