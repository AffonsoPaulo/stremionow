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
        // Memory: the interface keeps a few tens of MB of objects (images and video live in native
        // memory), but the JVM's defaults size the heap from the machine's RAM: a quarter of it as
        // the limit, a large first commit and G1's tables to match. With a heap this small the
        // serial collector pauses for a few milliseconds and needs no tables; together with a
        // small start and a cap, the JVM's own memory went from about 420 MB to 115 MB.
        jvmArgs +=
            listOf(
                "-Xms32m",
                "-Xmx1g",
                "-XX:+UseSerialGC",
                "-XX:MinHeapFreeRatio=10",
                "-XX:MaxHeapFreeRatio=30",
            )
        // macOS: the player reads the NSWindow behind its video window from AWT's peer, which has
        // no public accessor (see android/player/.../macos/MacWindows.kt). Builds run on the OS
        // they package for, so this is decided here.
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            jvmArgs +=
                listOf("java.awt", "sun.lwawt", "sun.lwawt.macosx").map { "--add-opens=java.desktop/$it=ALL-UNNAMED" }
        }

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
            // From `./gradlew suggestRuntimeModules`: jlink leaves out the rest of the JDK, and a module
            // missing here fails only in installed apps (java.management, through jdk.management,
            // sizes the video memory from the computer's RAM). Two are loaded as services, which
            // that task cannot see: jdk.localedata holds every locale but English (without it,
            // dates, numbers and sorting are English whatever the language), and
            // jdk.accessibility bridges Swing to Windows' screen readers.
            modules("java.instrument", "jdk.management", "jdk.unsupported", "jdk.localedata", "jdk.accessibility")

            // Drawn by AppIcon.kt; `./gradlew :app:writeAppIcons` writes them again after a change.
            windows {
                iconFile.set(layout.projectDirectory.file("icons/StremioNow.ico"))
                menuGroup = "StremioNow"
                perUserInstall = true
                shortcut = true
                // Fixed so that newer installers upgrade older ones instead of installing side by side.
                upgradeUuid = "6f0c1d2e-5b7a-4f43-9c1e-8a2d4b6e9f10"
            }
            linux {
                iconFile.set(layout.projectDirectory.file("icons/StremioNow.png"))
            }
        }
    }
}

// `./gradlew run` is the desktop's debug build: it unlocks the hidden test-crash menu
// (docs/adr/0007-crash-reports.md). Installers never set the property.
tasks.withType<JavaExec>().named { it == "run" }.configureEach { systemProperty("stremionow.debug", "true") }
// Development runs only: on macOS and Linux, where libmpv is not bundled yet, let the player fall
// back to the system's libmpv (Homebrew, libmpv2). Installed apps never get this property and use
// only the bundled one (see MpvLibrary and docs/adr/0005-desktop-ui.md).
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("stremionow.libmpv.allowSystem", "true")
}

// Compose registers its packaging tasks after this script runs, hence the lazy lookup by name.
tasks.named { it == "prepareAppResources" }.configureEach { dependsOn("prepareNativeResources") }

// The installers' icons in icons/, drawn by AppIcon.kt like the windows' own.
tasks.register<JavaExec>("writeAppIcons") {
    description = "Draws the installers' icons into icons/ from AppIcon.kt."
    group = "build"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.affonsopaulo.stremionow.desktop.AppIconFilesKt"
    args(
        layout.projectDirectory
            .dir("icons")
            .asFile.absolutePath,
    )
    systemProperty("java.awt.headless", "true")
}
