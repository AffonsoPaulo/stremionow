// SPDX-License-Identifier: GPL-3.0-or-later
// The video player: shared state and contract in commonMain, a libmpv implementation per platform
// (see docs/adr/0006-shared-ui.md).
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("stremionow.multiplatform.library")
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "io.github.affonsopaulo.stremionow.player"
        // The Mozilla CA bundle ships as an asset.
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            api(libs.libmpv)
            api(libs.kotlinx.coroutines.android)
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui)
        }
        named("desktopMain").dependencies {
            implementation(libs.jna)
            // Part of Android; the shared track and chapter parsing uses it on the desktop too.
            implementation(libs.org.json)
        }
        named("androidHostTest").dependencies {
            implementation(libs.junit)
            // Android's org.json is a stub in local unit tests; use the real library there.
            implementation(libs.org.json)
        }
    }
}

// Only the Android surface is a composable; the desktop player draws into a native window.
composeCompiler {
    targetKotlinPlatforms.set(setOf(KotlinPlatformType.androidJvm))
}
