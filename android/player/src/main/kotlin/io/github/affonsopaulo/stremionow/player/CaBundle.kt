// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.player

import android.content.Context
import java.io.File

/**
 * Installs the Mozilla CA bundle that mpv uses to verify HTTPS servers.
 *
 * mpv's network stack cannot read Android's certificate store, so without a bundle it would
 * have to skip verification. The bundle ships as an asset and is copied to private storage
 * on first use and again after every app update, which is when the shipped copy can change.
 */
internal object CaBundle {
    private const val ASSET = "cacert.pem"

    fun install(context: Context): File {
        val target = File(context.noBackupFilesDir, ASSET)
        val appUpdatedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (!target.isFile || target.lastModified() < appUpdatedAt) {
            val temporary = File(target.parentFile, "$ASSET.tmp")
            context.assets.open(ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(target)) { "Could not install the CA bundle" }
        }
        return target
    }
}
