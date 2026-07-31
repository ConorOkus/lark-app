plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(files("composeApp/src"))
}

// The UniFFI bindings under src/androidMain/kotlin/uniffi are machine-generated from the Rust
// crate (plan KTD-2) and are regenerated wholesale, never hand-edited — so detekt findings there
// are unactionable noise. Excluded here rather than in detekt.yml so the reason lives with the
// source-set wiring that put them in scope.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("**/uniffi/**")
}
