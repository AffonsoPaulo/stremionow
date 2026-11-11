// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("stremionow.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.affonsopaulo.stremionow.player"

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(libs.libmpv)
    api(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
}
