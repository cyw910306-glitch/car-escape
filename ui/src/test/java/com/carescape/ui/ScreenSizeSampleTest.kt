package com.carescape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScreenSizeSampleTest(private val screen: PleosScreen) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = PleosScreen.entries.toList()
    }

    @get:Rule val paparazzi = pleosPaparazzi(screen)

    @Test fun dummy() = paparazzi.snapshot(name = screen.name) {
        val c = LocalConfiguration.current
        Box(Modifier.fillMaxSize().background(Color(0xFF101418)), contentAlignment = Alignment.Center) {
            Text(
                "${screen.name} · ${c.screenWidthDp}x${c.screenHeightDp}dp",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
    }
}
