// SPDX-License-Identifier: GPL-3.0-or-later
// The Rust core's UniFFI bindings, shared by Android and the desktop (see docs/adr/0006-shared-ui.md).
plugins {
    id("stremionow.multiplatform.library")
    id("stremionow.rust-core")
}

kotlin {
    android {
        namespace = "io.github.affonsopaulo.stremionow.core"
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
        lint { lintConfig = file("lint.xml") }
    }

    sourceSets {
        commonMain.dependencies {
            // Async functions of the core become suspend functions.
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
        }
        named("desktopMain").dependencies {
            // The desktop app bundles the core's native library and adds it to JNA's search path.
            api(libs.jna)
        }
    }
}

dependencies {
    // The UniFFI bindings call the native library through JNA; the AAR carries its native part.
    "androidMainApi"(libs.jna) {
        artifact { type = "aar" }
    }
}
