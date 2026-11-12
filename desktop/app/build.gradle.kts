// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import stremionow.desktop.buildlogic.DesktopNativesPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    // Rust core, libmpv and CA store: see build-logic/.../DesktopNativesPlugin.kt.
    id("stremionow.desktop.natives")
}

java {
    targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    sourceCompatibility = targetCompatibility
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
    }
}

/** Shown in the app's profile page; the same as the Android apps' version name. */
val appVersion = "0.0.1"

dependencies {
    // The shared interface, with the player and the Rust core's bindings (android/ui and friends).
    implementation(libs.stremionow.ui)
    implementation(compose.desktop.currentOs)
    implementation(libs.jna)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "io.github.affonsopaulo.stremionow.desktop.MainKt"
        // Installers are built with jpackage from this JDK, which also provides the bundled runtime.
        // Defaults to the JDK running Gradle; the Android Studio JBR has no jpackage, so point this
        // at a full JDK 25 (preferably an OpenJDK build such as Temurin) when packaging.
        providers.gradleProperty("stremionow.packaging.javaHome").orNull?.let { javaHome = it }
        // JNA calls native code; JDK 25 warns (and later releases refuse) without this.
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dstremionow.version=$appVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "StremioNow"
            // Installers need MAJOR > 0; the core's version is shown in the app.
            packageVersion = "1.0.0"
            description = "StremioNow, a Stremio client"
            vendor = "StremioNow contributors"
            licenseFile.set(rootProject.layout.projectDirectory.file("../LICENSE"))
            // Compose copies `common/` and the folder of the current OS into the app's resources
            // directory, for `run` and for installers alike (compose.application.resources.dir).
            appResourcesRootDir.set(layout.buildDirectory.dir(DesktopNativesPlugin.RESOURCES_ROOT))
            modules("jdk.unsupported")

            windows {
                menuGroup = "StremioNow"
                perUserInstall = true
                shortcut = true
                // Fixed so that newer installers upgrade older ones instead of installing side by side.
                upgradeUuid = "6f0c1d2e-5b7a-4f43-9c1e-8a2d4b6e9f10"
            }
        }
    }
}

// `./gradlew run` is the desktop's debug build: it unlocks the hidden test-crash menu
// (docs/adr/0007-crash-reports.md). Installers never set the property.
tasks.withType<JavaExec>().named { it == "run" }.configureEach { systemProperty("stremionow.debug", "true") }

// Compose registers its packaging tasks after this script runs, hence the lazy lookup by name.
tasks.named { it == "prepareAppResources" }.configureEach { dependsOn("prepareNativeResources") }
