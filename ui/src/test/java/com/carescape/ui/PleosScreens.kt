package com.carescape.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation

/**
 * Official Pleos Connect app-window sizes in dp, read from the emulator's FormFactorConsole (2026-10-23).
 * Portrait = 1:1 split window, landscape = full-width window. See docs/pleos/form-factor-official-dp.md.
 */
enum class PleosScreen(val wDp: Int, val hDp: Int, val dpi: Int) {
    S17_PORT(840, 1184, 160),
    S17_LAND(1700, 1184, 160),
    S146_PORT(672, 948, 200),
    S146_LAND(1360, 948, 200),
    S129_PORT(628, 855, 160),
    S129_LAND(1274, 855, 160),
    WS_PORT(800, 810, 160),
    WS_LAND(1640, 810, 160),
    WE_PORT(800, 1400, 160),
    WE_LAND(1640, 1400, 160),
    WF_PORT(648, 800, 200),
    WF_LAND(1256, 800, 200);

    val deviceConfig: DeviceConfig
        get() = DeviceConfig(
            screenWidth = (wDp * dpi + 159) / 160, // round up so dp survives 200dpi
            screenHeight = (hDp * dpi + 159) / 160,
            xdpi = dpi,
            ydpi = dpi,
            density = Density.create(dpi),
            orientation = if (wDp > hDp) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
        )
}

/** Paparazzi rule rendering at one Pleos window size. Pair with a Parameterized test over PleosScreen.entries. */
fun pleosPaparazzi(screen: PleosScreen) = Paparazzi(deviceConfig = screen.deviceConfig, showSystemUi = false, useDeviceResolution = true)
