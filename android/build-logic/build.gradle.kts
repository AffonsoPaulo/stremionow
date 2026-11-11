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
