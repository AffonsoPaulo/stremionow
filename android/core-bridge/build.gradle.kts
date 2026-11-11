// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("stremionow.android.library")
    id("stremionow.rust-core")
}

android {
    namespace = "io.github.affonsopaulo.stremionow.core"
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // The UniFFI bindings call the native library through JNA; the AAR carries its native part.
    api(libs.jna) {
        artifact { type = "aar" }
    }
    // Async functions of the core become suspend functions.
    api(libs.kotlinx.coroutines.android)
}
