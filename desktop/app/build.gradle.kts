// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import stremionow.desktop.buildlogic.DesktopNativesPlugin
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.inject.Inject

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
val appVersion = "0.1.0"

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
            // Windows installs with Velopack's installer instead (packageVelopack below): an MSI from
            // here would install into the same folder and block it.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
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
                // The icon of StremioNow.exe, the app folder's launcher.
                iconFile.set(layout.projectDirectory.file("icons/StremioNow.ico"))
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

// The Windows installer people download: Velopack's StremioNow-win.msi, a wizard that welcomes,
// asks to accept the licence (installer/), installs the app in the user's profile
// (%LOCALAPPDATA%\StremioNow, no administrator rights) with the Start menu and desktop shortcuts,
// and ends with a box, ticked, to open the app. Its pages show icons/StremioNow-dialog.bmp and
// icons/StremioNow-banner.bmp, and icons/StremioNow-splash.png shows while the app installs or
// updates. Velopack also makes the packages that later updates are built from, and a one-click
// StremioNow-win-Setup.exe, which is not published. It wraps
// the folder `createDistributable` makes, so packaging needs a JDK with jpackage, as the MSI:
//   ./gradlew :app:packageVelopack -Pstremionow.packaging.javaHome="C:/path/to/jdk-25"
// `vpk` is a .NET tool pinned in desktop/dotnet-tools.json (`dotnet tool restore` installs it).
// Each release needs a higher version than the last (appVersion above) for updates to apply.
// Output: build/velopack/ (StremioNow-win.msi, the portable zip and the packages).
fun installer(name: String) =
    layout.projectDirectory
        .file("installer/$name")
        .asFile.absolutePath

tasks.register<Exec>("packageVelopack") {
    description = "Builds the Windows installer (Velopack) from the app folder createDistributable makes."
    group = "compose desktop"
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").lowercase().contains("windows") }
    val appFolder = layout.buildDirectory.dir("compose/binaries/main/app/StremioNow")
    val output = layout.buildDirectory.dir("velopack")
    workingDir = projectDir
    commandLine(
        "dotnet",
        "vpk",
        "pack",
        "--packId",
        "StremioNow",
        "--packTitle",
        "StremioNow",
        "--packAuthors",
        "StremioNow contributors",
        "--packVersion",
        appVersion,
        // The bundled runtime and libmpv are 64-bit (ARM64 builds bundle no libmpv yet).
        "--runtime",
        "win-x64",
        "--packDir",
        appFolder.get().asFile.absolutePath,
        "--mainExe",
        "StremioNow.exe",
        "--icon",
        layout.projectDirectory
            .file("icons/StremioNow.ico")
            .asFile.absolutePath,
        "--splashImage",
        layout.projectDirectory
            .file("icons/StremioNow-splash.png")
            .asFile.absolutePath,
        "--splashProgressColor",
        "#8B7CFF",
        "--shortcuts",
        "Desktop,StartMenuRoot",
        // The app is not a .NET or Rust Velopack app: it handles Velopack's hook arguments itself (Main.kt).
        "--skipVeloAppCheck",
        // The app updates from full packages only (AppUpdates.kt).
        "--delta",
        "None",
        "--msi",
        "--instLocation",
        "PerUser",
        "--instWelcome",
        installer("welcome.txt"),
        "--instLicense",
        installer("license.md"),
        "--instConclusion",
        installer("conclusion.txt"),
        "--msiTopBanner",
        layout.projectDirectory
            .file("icons/StremioNow-banner.bmp")
            .asFile.absolutePath,
        "--msiDialogBackground",
        layout.projectDirectory
            .file("icons/StremioNow-dialog.bmp")
            .asFile.absolutePath,
        "--outputDir",
        output.get().asFile.absolutePath,
    )
}

// Attaches the Windows files packageVelopack made to the GitHub release v<appVersion>: the
// installer (the MSI), the portable zip and what installed apps update from (AppUpdates.kt reads the
// latest published release). The Release workflow adds the Android APKs to the same release
// when the tag is pushed; whichever runs first creates the draft, the other adds to it. Needs the
// GitHub CLI signed in (`gh auth login`) and the commit pushed:
//   ./gradlew :app:packageVelopack :app:publishVelopack -Pstremionow.packaging.javaHome=...
tasks.register<PublishWindowsRelease>("publishVelopack") {
    description = "Attaches the Windows installer and update packages to this version's GitHub release (a draft)."
    group = "compose desktop"
    mustRunAfter("packageVelopack")
    onlyIf { System.getProperty("os.name").lowercase().contains("windows") }
    packages.set(layout.buildDirectory.dir("velopack"))
    version.set(appVersion)
    repository.set(layout.projectDirectory)
}

abstract class PublishWindowsRelease
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Internal abstract val packages: DirectoryProperty

        @get:Input abstract val version: Property<String>

        @get:Internal abstract val repository: DirectoryProperty

        @TaskAction
        fun publish() {
            val tag = "v${version.get()}"
            val folder = packages.get().asFile
            val files =
                listOf(
                    "StremioNow-win.msi",
                    "StremioNow-win-Portable.zip",
                    "StremioNow-${version.get()}-full.nupkg",
                    "releases.win.json",
                ).map { File(folder, it) }
            val missing = files.filterNot { it.isFile }
            if (missing.isNotEmpty()) {
                throw GradleException("Missing ${missing.joinToString { it.name }}: run packageVelopack first")
            }
            val paths = files.map { it.absolutePath }
            val exists =
                gh(listOf("release", "view", tag), ignoreFailure = true, quiet = true) == 0
            if (exists) {
                gh(listOf("release", "upload", tag, "--clobber") + paths)
            } else {
                val commit = ByteArrayOutputStream()
                exec.exec {
                    workingDir = repository.get().asFile
                    commandLine("git", "rev-parse", "HEAD")
                    standardOutput = commit
                }
                gh(
                    listOf(
                        "release",
                        "create",
                        tag,
                        "--draft",
                        "--target",
                        commit.toString().trim(),
                        "--title",
                        "StremioNow $tag",
                        "--generate-notes",
                    ) + paths,
                )
            }
            logger.lifecycle("Attached the Windows files to the draft release $tag; publish it on GitHub.")
        }

        private fun gh(
            args: List<String>,
            ignoreFailure: Boolean = false,
            quiet: Boolean = false,
        ): Int =
            exec
                .exec {
                    workingDir = repository.get().asFile
                    commandLine(listOf("gh") + args)
                    isIgnoreExitValue = ignoreFailure
                    if (quiet) {
                        standardOutput = OutputStream.nullOutputStream()
                        errorOutput = OutputStream.nullOutputStream()
                    }
                }.exitValue
    }
