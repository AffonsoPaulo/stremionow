// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.affonsopaulo.stremionow.ui.desktop.display

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

/**
 * Windows: HDR through the display configuration API (`DisplayConfigGetDeviceInfo` and
 * `DisplayConfigSetDeviceInfo`), the calls the Settings app uses; refresh rates through
 * `ChangeDisplaySettingsEx` with `CDS_FULLSCREEN`, a temporary mode that Windows drops when the
 * process ends.
 *
 * Windows 11 24H2 separates HDR from "advanced color" (which also covers automatic color
 * management on SDR monitors): there, HDR is read and set with the `ADVANCED_COLOR_INFO_2` and
 * `SET_HDR_STATE` requests; older versions use `ADVANCED_COLOR_INFO` and
 * `SET_ADVANCED_COLOR_STATE`, where advanced color is HDR.
 *
 * Structures are laid out by hand in native memory, following `wingdi.h`.
 */
internal class Win32DisplayControl : DisplayControl {
    private val user32 = User32.INSTANCE
    private val api = Api.INSTANCE

    override fun displayOf(window: Window): DisplayTarget? =
        runCatching {
            val monitor =
                user32.MonitorFromWindow(
                    HWND(Native.getWindowPointer(window)),
                    WinUser.MONITOR_DEFAULTTONEAREST,
                )
            val info = WinUser.MONITORINFOEX()
            if (!user32.GetMonitorInfo(monitor, info).booleanValue()) return null
            val deviceName = Native.toString(info.szDevice)
            activePaths().firstOrNull { it.sourceName.equals(deviceName, ignoreCase = true) }?.target
        }.getOrNull()

    override fun hdr(target: DisplayTarget): HdrState? =
        runCatching {
            if (activePaths().none { it.target == target }) return null
            // Windows 11 24H2 and later.
            val modern = packet(GET_ADVANCED_COLOR_INFO_2, GET_ADVANCED_COLOR_INFO_2_SIZE, target)
            if (api.DisplayConfigGetDeviceInfo(modern) == ERROR_SUCCESS) {
                val flags = modern.getInt(HEADER_SIZE)
                return HdrState(supported = flags and HDR_SUPPORTED != 0, enabled = flags and HDR_USER_ENABLED != 0)
            }
            val legacy = packet(GET_ADVANCED_COLOR_INFO, GET_ADVANCED_COLOR_INFO_SIZE, target)
            if (api.DisplayConfigGetDeviceInfo(legacy) != ERROR_SUCCESS) return null
            val flags = legacy.getInt(HEADER_SIZE)
            HdrState(
                supported = flags and ADVANCED_COLOR_SUPPORTED != 0 && flags and ADVANCED_COLOR_FORCE_DISABLED == 0,
                enabled = flags and ADVANCED_COLOR_ENABLED != 0,
            )
        }.getOrNull()

    override fun setHdr(
        target: DisplayTarget,
        enabled: Boolean,
    ): Boolean =
        runCatching {
            val value = if (enabled) 1 else 0
            val modern = packet(SET_HDR_STATE, SET_STATE_SIZE, target).apply { setInt(HEADER_SIZE, value) }
            if (api.DisplayConfigSetDeviceInfo(modern) == ERROR_SUCCESS) return true
            val legacy = packet(SET_ADVANCED_COLOR_STATE, SET_STATE_SIZE, target).apply { setInt(HEADER_SIZE, value) }
            api.DisplayConfigSetDeviceInfo(legacy) == ERROR_SUCCESS
        }.getOrDefault(false)

    override fun refreshRate(target: DisplayTarget): Int? =
        runCatching { currentMode(target)?.getInt(DM_DISPLAY_FREQUENCY)?.takeIf { it > 1 } }.getOrNull()

    override fun refreshRates(target: DisplayTarget): List<Int> =
        runCatching {
            val current = currentMode(target) ?: return emptyList()
            val width = current.getInt(DM_PELS_WIDTH)
            val height = current.getInt(DM_PELS_HEIGHT)
            val bits = current.getInt(DM_BITS_PER_PEL)
            val rates = sortedSetOf<Int>()
            var index = 0
            while (index < MAX_MODES) {
                val mode = devMode()
                if (!api.EnumDisplaySettingsExW(WString(target.deviceName), index, mode, 0)) break
                if (mode.getInt(DM_PELS_WIDTH) == width && mode.getInt(DM_PELS_HEIGHT) == height &&
                    mode.getInt(DM_BITS_PER_PEL) == bits
                ) {
                    mode.getInt(DM_DISPLAY_FREQUENCY).takeIf { it > 1 }?.let(rates::add)
                }
                index++
            }
            rates.toList()
        }.getOrDefault(emptyList())

    override fun setRefreshRate(
        target: DisplayTarget,
        hertz: Int,
    ): Boolean =
        runCatching {
            val mode = currentMode(target) ?: return false
            // Same resolution and depth; only the refresh rate changes.
            mode.setInt(DM_FIELDS, DM_BITSPERPEL or DM_PELSWIDTH or DM_PELSHEIGHT or DM_DISPLAYFREQUENCY)
            mode.setInt(DM_DISPLAY_FREQUENCY, hertz)
            val name = WString(target.deviceName)
            if (api.ChangeDisplaySettingsExW(name, mode, null, CDS_TEST, null) != DISP_CHANGE_SUCCESSFUL) return false
            api.ChangeDisplaySettingsExW(name, mode, null, CDS_FULLSCREEN, null) == DISP_CHANGE_SUCCESSFUL
        }.getOrDefault(false)

    override fun resetRefreshRate(target: DisplayTarget): Boolean =
        runCatching {
            // No mode: back to the one saved in the registry, which CDS_FULLSCREEN never touched.
            api.ChangeDisplaySettingsExW(WString(target.deviceName), null, null, 0, null) == DISP_CHANGE_SUCCESSFUL
        }.getOrDefault(false)

    private fun currentMode(target: DisplayTarget): Memory? =
        devMode().takeIf { api.EnumDisplaySettingsExW(WString(target.deviceName), ENUM_CURRENT_SETTINGS, it, 0) }

    private fun devMode(): Memory =
        Memory(DEVMODE_SIZE.toLong()).apply {
            clear()
            setShort(DM_SIZE, DEVMODE_SIZE.toShort())
        }

    private class ActivePath(
        val sourceName: String,
        val target: DisplayTarget,
    )

    /** The active display paths, each with its source's GDI name and its target. */
    private fun activePaths(): List<ActivePath> {
        val pathCount = IntByReference()
        val modeCount = IntByReference()
        if (api.GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, pathCount, modeCount) != ERROR_SUCCESS) {
            return emptyList()
        }
        if (pathCount.value <= 0) return emptyList()
        val paths = Memory(pathCount.value.toLong() * PATH_SIZE)
        val modes = Memory(modeCount.value.coerceAtLeast(1).toLong() * MODE_SIZE)
        if (api.QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, pathCount, paths, modeCount, modes, null) != ERROR_SUCCESS) {
            return emptyList()
        }
        return (0 until pathCount.value).mapNotNull { index ->
            val offset = index.toLong() * PATH_SIZE
            val sourceAdapterLow = paths.getInt(offset)
            val sourceAdapterHigh = paths.getInt(offset + 4)
            val sourceId = paths.getInt(offset + 8)
            val name =
                Memory(SOURCE_NAME_SIZE.toLong()).apply {
                    clear()
                    header(GET_SOURCE_NAME, SOURCE_NAME_SIZE, sourceAdapterLow, sourceAdapterHigh, sourceId)
                }
            if (api.DisplayConfigGetDeviceInfo(name) != ERROR_SUCCESS) return@mapNotNull null
            val deviceName = name.getWideString(HEADER_SIZE.toLong())
            val target =
                DisplayTarget(
                    adapterLow = paths.getInt(offset + PATH_TARGET),
                    adapterHigh = paths.getInt(offset + PATH_TARGET + 4),
                    targetId = paths.getInt(offset + PATH_TARGET + 8),
                    deviceName = deviceName,
                )
            ActivePath(deviceName, target)
        }
    }

    private fun packet(
        type: Int,
        size: Int,
        target: DisplayTarget,
    ): Memory =
        Memory(size.toLong()).apply {
            clear()
            header(type, size, target.adapterLow, target.adapterHigh, target.targetId)
        }

    /** `DISPLAYCONFIG_DEVICE_INFO_HEADER`: type, size, adapter LUID and source or target id. */
    private fun Memory.header(
        type: Int,
        size: Int,
        adapterLow: Int,
        adapterHigh: Int,
        id: Int,
    ) {
        setInt(0, type)
        setInt(4, size)
        setInt(8, adapterLow)
        setInt(12, adapterHigh)
        setInt(16, id)
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Api : StdCallLibrary {
        fun GetDisplayConfigBufferSizes(
            flags: Int,
            pathCount: IntByReference,
            modeCount: IntByReference,
        ): Int

        fun QueryDisplayConfig(
            flags: Int,
            pathCount: IntByReference,
            paths: Pointer,
            modeCount: IntByReference,
            modes: Pointer,
            topology: Pointer?,
        ): Int

        fun DisplayConfigGetDeviceInfo(packet: Pointer): Int

        fun DisplayConfigSetDeviceInfo(packet: Pointer): Int

        fun EnumDisplaySettingsExW(
            deviceName: WString,
            modeNumber: Int,
            mode: Pointer,
            flags: Int,
        ): Boolean

        fun ChangeDisplaySettingsExW(
            deviceName: WString,
            mode: Pointer?,
            window: HWND?,
            flags: Int,
            parameter: Pointer?,
        ): Int

        companion object {
            val INSTANCE: Api = Native.load("user32", Api::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }

    private companion object {
        const val ERROR_SUCCESS = 0
        const val QDC_ONLY_ACTIVE_PATHS = 2

        // DISPLAYCONFIG_PATH_INFO (72 bytes) and DISPLAYCONFIG_MODE_INFO (64 bytes).
        const val PATH_SIZE = 72
        const val PATH_TARGET = 20L
        const val MODE_SIZE = 64

        // DISPLAYCONFIG_DEVICE_INFO_TYPE values and packet sizes.
        const val HEADER_SIZE = 20L
        const val GET_SOURCE_NAME = 1
        const val SOURCE_NAME_SIZE = 20 + 64
        const val GET_ADVANCED_COLOR_INFO = 9
        const val GET_ADVANCED_COLOR_INFO_SIZE = 32
        const val SET_ADVANCED_COLOR_STATE = 10
        const val GET_ADVANCED_COLOR_INFO_2 = 15
        const val GET_ADVANCED_COLOR_INFO_2_SIZE = 36
        const val SET_HDR_STATE = 16
        const val SET_STATE_SIZE = 24

        // Bits of DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO.value.
        const val ADVANCED_COLOR_SUPPORTED = 1
        const val ADVANCED_COLOR_ENABLED = 2
        const val ADVANCED_COLOR_FORCE_DISABLED = 8

        // Bits of DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO_2.value.
        const val HDR_SUPPORTED = 1 shl 4
        const val HDR_USER_ENABLED = 1 shl 5

        // DEVMODEW (220 bytes): offsets of the fields used.
        const val DEVMODE_SIZE = 220
        const val DM_SIZE = 68L
        const val DM_FIELDS = 72L
        const val DM_BITS_PER_PEL = 168L
        const val DM_PELS_WIDTH = 172L
        const val DM_PELS_HEIGHT = 176L
        const val DM_DISPLAY_FREQUENCY = 184L
        const val DM_BITSPERPEL = 0x00040000
        const val DM_PELSWIDTH = 0x00080000
        const val DM_PELSHEIGHT = 0x00100000
        const val DM_DISPLAYFREQUENCY = 0x00400000

        const val ENUM_CURRENT_SETTINGS = -1
        const val MAX_MODES = 4096
        const val CDS_FULLSCREEN = 0x00000004
        const val CDS_TEST = 0x00000002
        const val DISP_CHANGE_SUCCESSFUL = 0
    }
}
