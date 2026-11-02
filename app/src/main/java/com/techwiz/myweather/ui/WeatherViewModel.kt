package com.techwiz.myweather.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techwiz.myweather.data.Forecast
import com.techwiz.myweather.data.Place
import com.techwiz.myweather.data.WeatherRepository
import com.techwiz.myweather.domain.Profile
import com.techwiz.myweather.domain.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

val DEFAULT_PLACE = Place("Fayetteville", 36.0626, -94.1574, "Arkansas, United States")

data class UiState(
    val place: Place = DEFAULT_PLACE,
    val profile: Profile = Profile.KITE,
    val settings: Settings = Settings(),
    val forecast: Forecast? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val searchResults: List<Place> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
)

class WeatherViewModel : ViewModel() {
    private val repo = WeatherRepository()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val place = _state.value.place
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val fc = repo.forecast(place)
                _state.update { it.copy(forecast = fc, loading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Couldn't load the forecast")
                }
            }
        }
    }

    fun selectPlace(place: Place) {
        _state.update {
            it.copy(place = place, forecast = null, searchResults = emptyList(), searchError = null)
        }
        refresh()
    }

    fun selectCoordinates(lat: Double, lon: Double) {
        selectPlace(Place(formatCoordinates(lat, lon), lat, lon))
    }

    fun setProfile(profile: Profile) = _state.update { it.copy(profile = profile) }

    fun setGroundFactor(value: Double) =
        _state.update { it.copy(settings = it.settings.copy(groundFactor = value)) }

    fun search(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            try {
                val results = repo.search(q)
                _state.update {
                    it.copy(
                        searching = false,
                        searchResults = results,
                        searchError = if (results.isEmpty()) "No places found" else null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(searching = false, searchError = e.message ?: "Search failed")
                }
            }
        }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = emptyList(), searchError = null) }
}

fun formatCoordinates(lat: Double, lon: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lon >= 0) "E" else "W"
    return "%.2f°%s, %.2f°%s".format(abs(lat), ns, abs(lon), ew)
}
