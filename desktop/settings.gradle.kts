// SPDX-License-Identifier: GPL-3.0-or-later
// Standalone build of the desktop app (Compose Multiplatform on the JVM). It is kept apart from
// android/ until the shared `ui` module moves to Kotlin Multiplatform; see docs/poc/phase-5.md.
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

include(":app")
