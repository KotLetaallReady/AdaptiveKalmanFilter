plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module — the SDK core: Kalman filter, neural trajectory
// smoother, metrics and math. Zero Android dependencies; fully unit-tested on
// the JVM. Android specifics (persistence, logging, sensors) are injected via
// the interfaces in this module and implemented in :app.
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
