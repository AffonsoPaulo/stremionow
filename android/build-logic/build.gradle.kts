// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(localGroovy())
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "stremionow.android.application"
            implementationClass = "stremionow.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "stremionow.android.library"
            implementationClass = "stremionow.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("designTokens") {
            id = "stremionow.design-tokens"
            implementationClass = "stremionow.buildlogic.DesignTokensPlugin"
        }
        register("rustCore") {
            id = "stremionow.rust-core"
            implementationClass = "stremionow.buildlogic.RustCorePlugin"
        }
    }
}
