// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("desktopNatives") {
            id = "stremionow.desktop.natives"
            implementationClass = "stremionow.desktop.buildlogic.DesktopNativesPlugin"
        }
    }
}
