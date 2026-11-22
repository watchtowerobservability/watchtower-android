// Thin Watchtower distribution for Android (ruling L1): RUM batches, crash/ANR/app-start/frame
// metrics and masked screenshot replay over the Watchtower edge. No product logic lives here.
plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.25"
    `maven-publish`
    signing
}

group = "dev.watchtower"
version = (System.getenv("GITHUB_REF_NAME") ?: "v0.1.2").removePrefix("v")

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
    // SDK-003: the NDK crash handler (src/main/cpp) builds only with -Pwatchtower.ndk=true, which
    // needs the Android NDK and CMake; without it NativeCrash finds no library and does nothing.
    if (project.findProperty("watchtower.ndk") == "true") {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "watchtower-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Watchtower Android SDK")
                description.set("RUM, crashes, ANRs, app start, frame vitals and masked replay over the Watchtower edge.")
                url.set("https://github.com/watchtowerobservability/watchtower-android")
                licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
                developers { developer { id.set("watchtower"); name.set("Watchtower") } }
                scm {
                    url.set("https://github.com/watchtowerobservability/watchtower-android")
                    connection.set("scm:git:https://github.com/watchtowerobservability/watchtower-android.git")
                }
            }
        }
    }
    repositories {
        // Maven Central through the Central Portal's OSSRH staging API; credentials are CI secrets.
        // Without them the repository is not added, so local and fork builds are unaffected.
        System.getenv("MAVEN_CENTRAL_USERNAME")?.let { user ->
            maven("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/") {
                name = "MavenCentral"
                credentials {
                    username = user
                    password = System.getenv("MAVEN_CENTRAL_PASSWORD") ?: ""
                }
            }
        }
        maven("https://maven.pkg.github.com/watchtowerobservability/watchtower-android") {
            name = "GitHubPackages"
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

// Signing is required by Maven Central; the armored key and its passphrase come from CI secrets only.
signing {
    val key = System.getenv("SIGNING_KEY")
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, System.getenv("SIGNING_PASSWORD") ?: "")
        sign(publishing.publications)
    }
}

dependencies {
    // WatchtowerInterceptor only; apps bring their own OkHttp (Apache-2.0)
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    // WatchtowerSQLiteOpenHelperFactory only; Room and SQLDelight bring it (Apache-2.0)
    compileOnly("androidx.sqlite:sqlite:2.4.0")
    // fragment screen views only; apps with fragments already have it (Apache-2.0)
    compileOnly("androidx.fragment:fragment:1.8.2")
    testImplementation("androidx.sqlite:sqlite:2.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
