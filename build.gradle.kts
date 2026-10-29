// Thin Watchtower distribution for Android (ruling L1): RUM batches, crash/ANR/app-start/frame
// metrics and masked screenshot replay over the Watchtower edge. No product logic lives here.
plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    `maven-publish`
}

group = "dev.watchtower"
version = (System.getenv("GITHUB_REF_NAME") ?: "v0.1.0").removePrefix("v")

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
    publishing { singleVariant("release") { withSourcesJar() } }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "watchtower-android"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven("https://maven.pkg.github.com/watchtowerobservability/watchtower-android") {
            name = "GitHubPackages"
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
