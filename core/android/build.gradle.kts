plugins {
    alias(libs.plugins.android.library)
}

// Android adapters for the pure-JVM SDK core: implementations of the engine's
// SensorDataSource, SmootherStore and Logger interfaces. This is the only core
// module that depends on the Android framework.
android {
    namespace = "com.katka.android"
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.play.services.location)
}
