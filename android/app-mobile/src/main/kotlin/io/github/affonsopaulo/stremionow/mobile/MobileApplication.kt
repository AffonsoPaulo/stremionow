// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.mobile

import android.app.Application
import io.github.affonsopaulo.stremionow.ui.crash.CrashReports

/** The phone app's process: catches crashes from the very start (docs/adr/0007-crash-reports.md). */
class MobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this, isTv = false)
    }
}
