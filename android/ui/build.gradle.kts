// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("stremionow.android.library")
    alias(libs.plugins.kotlin.compose)
    id("stremionow.design-tokens")
}

android {
    namespace = "io.github.affonsopaulo.stremionow.ui"

    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        // UI strings live in the repository-wide l10n folder (see docs/adr/0003-strings-format.md).
        variant.sources.res?.addStaticSourceDirectory(rootDir.resolve("../l10n/res").absolutePath)
    }
}

dependencies {
    api(project(":core-bridge"))
    api(project(":player"))
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.ui)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
