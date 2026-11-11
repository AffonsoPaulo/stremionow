// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension

/** ABIs the apps ship and the Rust core is built for. */
internal val ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

/**
 * Settings shared by every Android application module: SDK levels, Java level, ABIs,
 * release shrinking and release signing.
 *
 * Release signing reads the keystore from environment variables, so keys never live in the
 * repository (see docs/RELEASING.md). Without them, release builds are produced unsigned.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.application")
        val libs = project.versionCatalog()

        project.extensions.configure(ApplicationExtension::class.java) {
            compileSdk = libs.intVersion("compileSdk")
            compileSdkMinor = libs.intVersion("compileSdkMinor")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                targetSdk = libs.intVersion("targetSdk")
                ndk { abiFilters += ANDROID_ABIS }
            }
            compileOptions {
                sourceCompatibility = libs.javaVersion()
                targetCompatibility = libs.javaVersion()
            }

            val keystore = project.providers.environmentVariable("STREMIONOW_KEYSTORE_FILE")
            if (keystore.isPresent) {
                signingConfigs.create("release") {
                    storeFile = project.file(keystore.get())
                    storePassword = project.providers.environmentVariable("STREMIONOW_KEYSTORE_PASSWORD").get()
                    keyAlias = project.providers.environmentVariable("STREMIONOW_KEY_ALIAS").get()
                    keyPassword = project.providers.environmentVariable("STREMIONOW_KEY_PASSWORD").get()
                }
            }

            buildTypes.getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                signingConfigs.findByName("release")?.let { signingConfig = it }
            }
        }
    }
}

/** Settings shared by every Android library module: SDK levels and Java level. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.android.library")
        val libs = project.versionCatalog()

        project.extensions.configure(LibraryExtension::class.java) {
            compileSdk = libs.intVersion("compileSdk")
            compileSdkMinor = libs.intVersion("compileSdkMinor")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
            }
            compileOptions {
                sourceCompatibility = libs.javaVersion()
                targetCompatibility = libs.javaVersion()
            }
        }
    }
}

private fun Project.versionCatalog(): VersionCatalog =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

private fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias)
        .orElseThrow { IllegalStateException("Missing version \"$alias\" in libs.versions.toml") }
        .requiredVersion
        .toInt()

private fun VersionCatalog.javaVersion(): JavaVersion = JavaVersion.toVersion(intVersion("java"))
