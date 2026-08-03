import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.qrose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            // The generated UniFFI bindings (src/androidMain/kotlin/uniffi) import com.sun.jna.*.
            // Android needs the @aar variant: it carries the Android .so set the device loads.
            implementation("${libs.jna.get().module}:${libs.versions.jna.get()}@aar")
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            // ...and the host JVM needs the plain jar, whose bundled natives are desktop
            // builds. The @aar's natives are Android-only and cannot load on macOS, so the
            // FFI unit-test lane would fail to initialise JNA without this. Both, not either.
            implementation(libs.jna)
        }
        // The on-device lane. It exists because the JVM host cannot answer one question the
        // adapter depends on: whether the crate's async calls complete off the main thread.
        // See docs/ffi/kotlin-bindings-status.md — on the host they complete only under
        // runBlocking on the JUnit thread, which is not a shape FfiLarkCore can use.
        androidInstrumentedTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "xyz.lark.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.lark.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

// --- lark-ffi test lanes (plan U1) -----------------------------------------------------------
// The FFI lane loads the real Rust library through JNA, so point JNA at the cargo host artifact.
//
// The path MUST be absolute: a test JVM's working directory is the module directory, so the
// relative string "rust/lark-ffi/target/debug" resolves to composeApp/rust/... and the library
// is silently never found. `rootProject` anchors it to the repo root instead.
val larkFfiHostLibDir: String =
    rootProject.layout.projectDirectory.dir("rust/lark-ffi/target/debug").asFile.absolutePath

/**
 * Turns the FFI lane's "skip when the library is missing" into "fail when it is missing".
 *
 * The skip exists so `./gradlew` works without a Rust toolchain (plan R6) — but on a lane that is
 * supposed to *prove* the Rust core works, a silent skip would let a green run mean nothing was
 * verified (plan R7). `scripts/ci.sh` exports this whenever it runs the Rust leg, so the two
 * behaviors cannot drift apart.
 *
 * Forwarded rather than read at configuration time so toggling it does not require a re-configure.
 */
val requireFfiEnvVar = "LARK_REQUIRE_FFI"
val ffiLaneRequired: Boolean =
    providers.environmentVariable(requireFfiEnvVar).getOrElse("") == "1"

tasks.withType<Test>().configureEach {
    systemProperty("jna.library.path", larkFfiHostLibDir)
    environment(requireFfiEnvVar, if (ffiLaneRequired) "1" else "")
    if (ffiLaneRequired) {
        // The native library is reached through a system-property path, so Gradle cannot see it as
        // an input — measured: hiding the library leaves this task UP-TO-DATE, and a prior run's
        // result is even served FROM-CACHE. Either way a run where the FFI lane skipped is
        // indistinguishable from one where it passed, which is exactly how a required lane goes
        // green having verified nothing. On the required lane, force real execution; `scripts/ci.sh`
        // then asserts the lane reported zero skips (plan R7).
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
