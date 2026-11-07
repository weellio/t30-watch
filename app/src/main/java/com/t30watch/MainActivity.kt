package com.t30watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.t30watch.data.settings.Settings
import com.t30watch.ui.insights.InsightsScreen
import com.t30watch.ui.rewards.RewardsScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.t30watch.data.settings.AppSettings
import com.t30watch.service.WatchService
import com.t30watch.ble.CameraEvent
import com.t30watch.ui.camera.CameraScreen
import com.t30watch.ui.connect.ConnectScreen
import com.t30watch.ui.debug.DebugScreen
import com.t30watch.ui.history.HistoryScreen
import com.t30watch.ui.measure.MeasureScreen
import com.t30watch.ui.more.MoreScreen
import com.t30watch.ui.theme.T30Theme
import com.t30watch.ui.today.TodayScreen
import com.t30watch.ui.watchsettings.WatchSettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Single activity. All screens are Compose destinations inside it. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val prefs = s ?: Settings()
            T30Theme(themeId = prefs.themeId, darkMode = prefs.darkMode, amoled = prefs.amoled) {
                // Start the keep-connected service whenever the app comes to the front (it needs the
                // Bluetooth permission, which may have just been granted).
                LifecycleResumeEffect(Unit) {
                    lifecycleScope.launch { if (settings.current().keepConnected) WatchService.start(applicationContext) }
                    onPauseOrDispose { }
                }
                AppNav()
            }
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: Int) {
    TODAY("today", "Today", R.drawable.ic_tab_today),
    HISTORY("history", "History", R.drawable.ic_tab_history),
    INSIGHTS("insights", "Insights", R.drawable.ic_tab_insights),
    WATCH("watch", "Watch", R.drawable.ic_tab_watch),
    MORE("more", "More", R.drawable.ic_tab_more),
}

private object Routes {
    const val DEBUG = "debug"
    const val WATCH_SETTINGS = "watch_settings"
    const val CAMERA = "camera"
    const val MEASURE = "measure"
    const val REWARDS = "rewards"
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }

    // Camera mode opened from the watch's own menu → show the shutter screen.
    val appVm: AppViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        appVm.cameraEvents.collect { e ->
            if (e == CameraEvent.OPENED_FROM_WATCH && nav.currentDestination?.route != Routes.CAMERA) nav.navigate(Routes.CAMERA)
        }
    }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(painterResource(t.icon), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { outer ->
        Box(Modifier.padding(bottom = outer.calculateBottomPadding())) {
            NavHost(navController = nav, startDestination = Tab.TODAY.route) {
                composable(Tab.TODAY.route) {
                    TodayScreen(onOpenWatch = { nav.goTab(Tab.WATCH.route) }, onOpenRewards = { nav.navigate(Routes.REWARDS) })
                }
                composable(Tab.HISTORY.route) { HistoryScreen() }
                composable(Tab.INSIGHTS.route) {
                    InsightsScreen(onOpenMeasure = { nav.navigate(Routes.MEASURE) }, onOpenRewards = { nav.navigate(Routes.REWARDS) })
                }
                composable(Routes.MEASURE) { MeasureScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.REWARDS) { RewardsScreen(onBack = { nav.popBackStack() }) }
                composable(Tab.WATCH.route) {
                    ConnectScreen(
                        onOpenDebug = { nav.navigate(Routes.DEBUG) },
                        onOpenMeasure = { nav.navigate(Routes.MEASURE) },
                        onOpenWatchSettings = { nav.navigate(Routes.WATCH_SETTINGS) },
                    )
                }
                composable(Tab.MORE.route) { MoreScreen(onOpenDebug = { nav.navigate(Routes.DEBUG) }) }
                composable(Routes.DEBUG) { DebugScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.WATCH_SETTINGS) { WatchSettingsScreen(onBack = { nav.popBackStack() }, onOpenCamera = { nav.navigate(Routes.CAMERA) }) }
                composable(Routes.CAMERA) { CameraScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}

/** Standard bottom-bar navigation: one copy of each tab, state kept when switching. */
private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
