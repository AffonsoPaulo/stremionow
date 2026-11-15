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
private val ANDROID_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

/**
 * The ABIs this build makes: all of [ANDROID_ABIS], or those `-Pstremionow.abis` lists (comma
 * separated). CI's checks build only `arm64-v8a`, which is enough to compile, lint and test and
 * compiles the Rust core once instead of three times; releases always build them all.
 */
internal fun Project.androidAbis(): List<String> {
    val chosen =
        providers
            .gradleProperty("stremionow.abis")
            .orNull
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: return ANDROID_ABIS
    require(chosen.isNotEmpty() && ANDROID_ABIS.containsAll(chosen)) {
        "stremionow.abis must list some of ${ANDROID_ABIS.joinToString()}"
    }
    return chosen
}

/**
 * Settings shared by every Android application module: SDK levels, Java level, per-ABI APKs,
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
            }
            // One APK per ABI, so each device downloads only its own native libraries (about a
            // third of a universal APK), plus a universal APK for sideloading anywhere. Splits
            // also limit the ABIs, which dependencies such as JNA would otherwise widen.
            splits {
                abi {
                    isEnable = true
                    reset()
                    include(*project.androidAbis().toTypedArray())
                    isUniversalApk = true
                }
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

internal fun Project.versionCatalog(): VersionCatalog =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias)
        .orElseThrow { IllegalStateException("Missing version \"$alias\" in libs.versions.toml") }
        .requiredVersion
        .toInt()

private fun VersionCatalog.javaVersion(): JavaVersion = JavaVersion.toVersion(intVersion("java"))
