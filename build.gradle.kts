// Thin Watchtower distribution for Android (ruling L1): RUM batches, crash/ANR/app-start/frame
// metrics and masked screenshot replay over the Watchtower edge. No product logic lives here.
plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.4.21"
}

android {
    namespace = "dev.watchtower"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
