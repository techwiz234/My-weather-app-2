package com.techwiz.myweather

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.techwiz.myweather.ui.AlertsDialog
import com.techwiz.myweather.ui.ForecastScreen
import com.techwiz.myweather.ui.MapActions
import com.techwiz.myweather.ui.MapScreen
import com.techwiz.myweather.ui.MyWeatherTheme
import com.techwiz.myweather.ui.WeatherViewModel
import com.techwiz.myweather.ui.activeAlerts

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MyWeatherTheme { App() }
        }
    }
}

private enum class Tab { MAP, FORECAST }

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
private fun App(vm: WeatherViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    var showAlerts by remember { mutableStateOf(false) }
    var denials by remember { mutableIntStateOf(0) }

    fun hasPermission() = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun locate() {
        LocationHelper.locate(context) { loc ->
            if (loc != null) vm.onLocationFound(loc.latitude, loc.longitude) else vm.onLocationUnavailable()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        vm.onLocationPermissionResult(granted)
        if (granted) locate() else denials++
    }

    // Ask for the location once on launch; fall back to the last saved place if declined.
    LaunchedEffect(Unit) {
        if (hasPermission()) {
            vm.onLocationPermissionResult(true)
            locate()
        } else {
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    fun onMyLocation() {
        when {
            hasPermission() -> locate()
            // Android stops showing the prompt after repeated denials; send them to Settings.
            denials >= 2 -> context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    BackHandler(enabled = tab == Tab.FORECAST) { tab = Tab.MAP }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == Tab.MAP,
                    onClick = { tab = Tab.MAP },
                    icon = { Icon(Icons.Default.Place, contentDescription = null) },
                    label = { Text("Map") },
                )
                NavigationBarItem(
                    selected = tab == Tab.FORECAST,
                    onClick = { tab = Tab.FORECAST },
                    icon = { Icon(Icons.Default.Menu, contentDescription = null) },
                    label = { Text("Forecast") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // Exactly one screen exists at a time. (An earlier version stacked the forecast on top
            // of the live map, and the map view fought the forecast list for touches, which broke
            // scrolling.)
            when (tab) {
                Tab.MAP -> MapScreen(
                    state = state,
                    actions = MapActions(
                        onTap = vm::selectCoordinates,
                        onSearch = vm::search,
                        onPickResult = vm::selectPlace,
                        onClearSearch = vm::clearSearch,
                        onOpenForecast = { tab = Tab.FORECAST },
                        onViewport = vm::onViewport,
                        onLayer = vm::setLayer,
                        onGridHour = vm::setGridHour,
                        onTogglePlay = vm::togglePlaying,
                        onFrame = vm::setFrame,
                        onMyLocation = ::onMyLocation,
                        onAlertClick = { showAlerts = true },
                    ),
                )

                Tab.FORECAST -> ForecastScreen(
                    state = state,
                    onProfile = vm::setProfile,
                    onGroundFactor = vm::setGroundFactor,
                    onRefresh = { vm.refresh() },
                    onAlertClick = { showAlerts = true },
                )
            }
        }
    }

    if (showAlerts) {
        val alerts = state.alerts.activeAlerts()
        if (alerts.isEmpty()) showAlerts = false else AlertsDialog(alerts) { showAlerts = false }
    }
}
