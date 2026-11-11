// SPDX-License-Identifier: GPL-3.0-or-later
// The desktop app (Compose Multiplatform on the JVM). Its interface, player and Rust core
// bindings are the shared Kotlin Multiplatform modules of the Android build, included here and
// substituted by their coordinates (see docs/adr/0006-shared-ui.md).
pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "StremioNow-desktop"

includeBuild("../android")

include(":app")
