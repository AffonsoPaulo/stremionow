// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Maven group of the shared modules; the desktop build substitutes them by `group:name`. */
const val SHARED_MODULES_GROUP = "io.github.affonsopaulo.stremionow"

/** Name of the desktop JVM target, and so of its source sets (`desktopMain`, `desktopTest`). */
const val DESKTOP_TARGET = "desktop"

/**
 * Settings shared by every Kotlin Multiplatform library: an Android target with the same SDK
 * levels and Java level as the Android modules, a desktop JVM target, and Android Lint.
 *
 * Both targets run on the JVM, so `commonMain` is compiled once per target and may use the JDK
 * and JVM libraries (JNA, the UniFFI bindings); see docs/adr/0006-shared-ui.md.
 *
 * Android Kotlin Multiplatform libraries have a single variant. So that the usual commands
 * (`lintDebug`, `testDebugUnitTest`, `assembleDebug`) cover them too, each of those names is
 * registered here as an alias of the module's Android lint, host test and assemble tasks.
 */
class MultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply("com.android.kotlin.multiplatform.library")
        project.pluginManager.apply("com.android.lint")
        project.group = SHARED_MODULES_GROUP

        val libs = project.versionCatalog()
        val javaVersion = libs.intVersion("java")
        val jvmTarget = JvmTarget.fromTarget(javaVersion.toString())

        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        // Platform types such as PlatformContext are expect classes, still Beta in Kotlin 2.4.
        kotlin.compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
        (kotlin as ExtensionAware).extensions.configure(KotlinMultiplatformAndroidLibraryTarget::class.java) {
            compileSdk {
                version = release(libs.intVersion("compileSdk")) { minorApiLevel = libs.intVersion("compileSdkMinor") }
            }
            minSdk = libs.intVersion("minSdk")
            compilerOptions { this.jvmTarget.set(jvmTarget) }
            withHostTest {}
        }
        kotlin.jvm(DESKTOP_TARGET) {
            compilerOptions { this.jvmTarget.set(jvmTarget) }
        }
        project.tasks.withType(JavaCompile::class.java).configureEach { options.release.set(javaVersion) }

        project.tasks.register("lintDebug") {
            group = "verification"
            description = "Runs Android Lint on the Android target (alias of lintAndroidMain)."
            dependsOn("lintAndroidMain")
        }
        project.tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Runs the Android host tests (alias of testAndroidHostTest)."
            dependsOn("testAndroidHostTest")
        }
        project.tasks.register("assembleDebug") {
            group = "build"
            description = "Assembles the Android library (alias of assembleAndroidMain)."
            dependsOn("assembleAndroidMain")
        }
    }
}
