package com.carescape.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.carescape.core.SimVehicleGateway
import com.carescape.ui.C
import com.carescape.ui.CarEscapeApp
import com.carescape.ui.DemoControls
import com.carescape.ui.EpisodeOption
import com.carescape.ui.GameActions
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    // AAOS dangerous permissions: PERF_VEHICLE_SPEED, EV_CHARGE_STATE, EV_CHARGE_PERCENT_LIMIT. Pleos CAR_* are install-time.
    private val carPerms = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY")
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) vm.onCarPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!vm.demo) {
            val missing = carPerms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty() && savedInstanceState == null) askPerms.launch(missing.toTypedArray())
        }
        val g = vm.game
        // Demo recordings only: `am start --el seed N` pins 오늘의 금고 to seed N. Release always uses the date.
        val demoSeed = intent.getLongExtra("seed", -1L).takeIf { BuildConfig.BUILD_TYPE != "release" && it >= 0 }
        val actions = GameActions(
            g::startMatching, g::startEpisode, g::skipIntro, g::requestHint, g::skipPuzzle, g::backToTitle,
            onDaily = { id -> g.startDaily(id, demoSeed ?: LocalDate.now().toEpochDay()) }, // 오늘의 금고: same set all day
            onResume = g::resumeAfterDoor,
            onReadyStart = g::startNext,
            onReadyDismiss = g::dismissReady,
            onClearLog = vm::clearLog,
            onParkStay = g::stayInCar,
            onParkSave = g::saveAnchor,
            onParkSkip = g::skipAnchor,
            onReturnOpen = g::openReturn,
            onReturnReveal = g::revealReturn,
            onReturnClose = g::closeReturn,
            onLook = vm::setLook,
            onMoodPreview = vm::previewMood,
            onMoodEnd = vm::endMoodPreview,
        )
        val episodes = g.episodes.map { EpisodeOption(it.id, it.title, it.minutes, it.intro, it.puzzles.size, it.puzzles.flatMap { p -> p.spec.inputs }.distinct()) }
        // 테마 팩: the whole skin reads C.theme, so one write here recomposes every screen.
        C.theme = vm.theme.value
        lifecycleScope.launch { vm.theme.collect { C.theme = it } }
        setContent {
            val ui by g.ui.collectAsState()
            val vehicle by vm.vehicle.collectAsState()
            val blocked by vm.blocked.collectAsState()
            val timelapse by vm.timelapse.collectAsState()
            val gateway by vm.gateway.collectAsState()
            val progress by vm.progress.collectAsState()
            val look by vm.look.collectAsState()
            val newOpened by vm.newOpened.collectAsState()
            val moodOn by vm.moodOn.collectAsState()
            val controls = remember(gateway, timelapse) {
                DemoControls(
                    update = { f -> (gateway as? SimVehicleGateway)?.update(f) },
                    timelapse = timelapse,
                    onTimelapse = { vm.timelapse.value = it },
                )
            }
            CarEscapeApp(ui, vehicle, actions, controls, showDemo = vm.demo, blocked = blocked, episodes = episodes, progress = progress, look = look, newOpened = newOpened, moodOn = moodOn, modifier = Modifier.fillMaxSize())
        }
    }

    override fun onStart() { super.onStart(); vm.resume() }
    override fun onStop() { vm.pause(); super.onStop() }
}
