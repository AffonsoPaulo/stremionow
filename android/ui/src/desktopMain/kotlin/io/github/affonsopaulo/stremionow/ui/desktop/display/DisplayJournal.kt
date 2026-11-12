// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop.display

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * What the app turned on and must turn off again, written down before the change is made, so
 * that the next launch can undo it after a crash or a kill. Only HDR needs it: Windows keeps it
 * on, while a temporary refresh rate ends with the process.
 */
interface DisplayJournal {
    /** The monitor HDR was turned on for, or null when nothing is pending. */
    fun read(): DisplayTarget?

    fun write(target: DisplayTarget)

    fun clear()
}

/** A [DisplayJournal] in a small file of the app's data folder, replaced atomically. */
class FileDisplayJournal(
    private val file: File,
) : DisplayJournal {
    override fun read(): DisplayTarget? {
        if (!file.isFile || file.length() > MAX_SIZE) return null
        val properties =
            runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrNull() ?: return null
        val name = properties.getProperty(NAME)?.takeIf { DEVICE_NAME.matches(it) } ?: return null
        return DisplayTarget(
            adapterLow = properties.getProperty(ADAPTER_LOW)?.toIntOrNull() ?: return null,
            adapterHigh = properties.getProperty(ADAPTER_HIGH)?.toIntOrNull() ?: return null,
            targetId = properties.getProperty(TARGET)?.toIntOrNull() ?: return null,
            deviceName = name,
        )
    }

    override fun write(target: DisplayTarget) {
        val properties =
            Properties().apply {
                setProperty(ADAPTER_LOW, target.adapterLow.toString())
                setProperty(ADAPTER_HIGH, target.adapterHigh.toString())
                setProperty(TARGET, target.targetId.toString())
                setProperty(NAME, target.deviceName)
            }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "HDR turned on by StremioNow, to turn off again") }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_SIZE = 4096L
        const val ADAPTER_LOW = "adapterLow"
        const val ADAPTER_HIGH = "adapterHigh"
        const val TARGET = "target"
        const val NAME = "device"
        val DEVICE_NAME = Regex("""\\\\\.\\DISPLAY\d{1,3}""")
    }
}
