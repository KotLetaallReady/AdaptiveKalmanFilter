plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module — the SDK's domain models. No Android dependencies, so
// it can be imported (and unit-tested) anywhere.
dependencies {
    testImplementation(libs.junit)
}
