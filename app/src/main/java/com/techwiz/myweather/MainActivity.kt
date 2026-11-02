package com.techwiz.myweather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.techwiz.myweather.ui.ForecastScreen
import com.techwiz.myweather.ui.MapScreen
import com.techwiz.myweather.ui.MyWeatherTheme
import com.techwiz.myweather.ui.WeatherViewModel

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

@Composable
private fun App(vm: WeatherViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }

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
            // The map stays composed underneath so its view (and position) isn't rebuilt
            // every time you flip tabs.
            MapScreen(
                state = state,
                onTap = vm::selectCoordinates,
                onSearch = vm::search,
                onPickResult = vm::selectPlace,
                onClearSearch = vm::clearSearch,
                onOpenForecast = { tab = Tab.FORECAST },
            )
            if (tab == Tab.FORECAST) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        // Swallow any touch the forecast doesn't use so it can't reach the map below.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                ) {
                    ForecastScreen(
                        state = state,
                        onProfile = vm::setProfile,
                        onGroundFactor = vm::setGroundFactor,
                        onRefresh = vm::refresh,
                    )
                }
            }
        }
    }
}
