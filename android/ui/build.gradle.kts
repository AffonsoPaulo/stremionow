// SPDX-License-Identifier: GPL-3.0-or-later
// The interface shared by the phone, TV and desktop apps (see docs/adr/0006-shared-ui.md).
plugins {
    id("stremionow.multiplatform.library")
    alias(libs.plugins.kotlin.compose)
    id("stremionow.design-tokens")
    id("stremionow.strings")
}

kotlin {
    android {
        namespace = "io.github.affonsopaulo.stremionow.ui"
        // Fonts, the window theme, the launcher icon and the strings of l10n/ (see StringsPlugin).
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core-bridge"))
            api(project(":player"))
            api(libs.multiplatform.compose.foundation)
            api(libs.multiplatform.compose.ui)
            api(libs.multiplatform.lifecycle.viewmodel.compose)
            api(libs.multiplatform.lifecycle.runtime.compose)
            api(libs.multiplatform.navigation.compose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.okhttp)
        }
        androidMain.dependencies {
            // The same androidx versions as before the module became multiplatform.
            api(project.dependencies.platform(libs.compose.bom))
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.androidx.lifecycle.runtime.compose)
            api(libs.androidx.navigation.compose)
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.ui.tooling.preview)
        }
        named("desktopMain").dependencies {
            // Back handling (Esc) on the desktop.
            implementation(libs.multiplatform.navigationevent.compose)
            // Windows DPAPI for secrets and Win32 window placement for the player.
            implementation(libs.jna.platform)
            // Dispatchers.Main on the AWT event thread.
            implementation(libs.kotlinx.coroutines.swing)
        }
        named("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}

// Desktop text uses the same font files as Android, read from the classpath.
val desktopFonts =
    tasks.register<Sync>("syncDesktopFonts") {
        from("src/androidMain/res/font")
        into(layout.buildDirectory.dir("generated/desktopFonts/font"))
    }
kotlin.sourceSets.named("desktopMain") {
    resources.srcDir(desktopFonts.map { it.destinationDir.parentFile })
}
