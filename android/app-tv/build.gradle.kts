// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("stremionow.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.affonsopaulo.stremionow.tv"

    defaultConfig {
        applicationId = "io.github.affonsopaulo.stremionow.tv"
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core-bridge"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Layout Inspector support in debug builds; the shared ui module has a single variant.
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.tvprovider)
}
