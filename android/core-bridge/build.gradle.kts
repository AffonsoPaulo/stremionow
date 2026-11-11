// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.library)
    id("stremionow.rust-core")
}

android {
    namespace = "io.github.affonsopaulo.stremionow.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The UniFFI bindings call the native library through JNA; the AAR carries its native part.
    api("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}
