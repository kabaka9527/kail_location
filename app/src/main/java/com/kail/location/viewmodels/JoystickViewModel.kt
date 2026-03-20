package com.kail.location.viewmodels

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.kail.location.R
import com.kail.location.repositories.DataBaseHistoryLocation
import com.kail.location.utils.GoUtils
import com.kail.location.utils.KailLog
import com.kail.location.utils.MapUtils
import com.kail.location.views.history.HistoryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * ViewModel for managing joystick-related states and business logic.
 */
class JoystickViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val suggestionSearch: SuggestionSearch = SuggestionSearch.newInstance()

    // --- States ---

    private val _windowType = MutableStateFlow(WindowType.JOYSTICK)
    val windowType: StateFlow<WindowType> = _windowType.asStateFlow()

    private val _currentLocation = MutableStateFlow(LatLng(0.0, 0.0))
    val currentLocation: StateFlow<LatLng> = _currentLocation.asStateFlow()

    private val _markLocation = MutableStateFlow<LatLng?>(null)
    val markLocation: StateFlow<LatLng?> = _markLocation.asStateFlow()

    private val _speed = MutableStateFlow(1.2)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    private val _altitude = MutableStateFlow(55.0)
    val altitude: StateFlow<Double> = _altitude.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Map<String, Any>>>(emptyList())
    val searchResults: StateFlow<List<Map<String, Any>>> = _searchResults.asStateFlow()

    private val _historyRecords = MutableStateFlow<List<Map<String, Any>>>(emptyList())
    val historyRecords: StateFlow<List<Map<String, Any>>> = _historyRecords.asStateFlow()

    // Route states
    private val _isRoutePaused = MutableStateFlow(false)
    val isRoutePaused: StateFlow<Boolean> = _isRoutePaused.asStateFlow()

    private val _routeProgress = MutableStateFlow(0f)
    val routeProgress: StateFlow<Float> = _routeProgress.asStateFlow()

    private val _routeTotalDistance = MutableStateFlow("0m")
    val routeTotalDistance: StateFlow<String> = _routeTotalDistance.asStateFlow()

    private val _routeSpeed = MutableStateFlow(0.0)
    val routeSpeed: StateFlow<Double> = _routeSpeed.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == SettingsViewModel.KEY_JOYSTICK_SPEED) {
            _speed.value = sharedPreferences.getString(key, "1.2")?.toDoubleOrNull() ?: 1.2
        }
    }

    // --- Initialization ---

    init {
        _speed.value = sharedPreferences.getString(SettingsViewModel.KEY_JOYSTICK_SPEED, "1.2")?.toDoubleOrNull() ?: 1.2
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        
        suggestionSearch.setOnGetSuggestionResultListener { result ->
            if (result?.allSuggestions == null) {
                _searchResults.value = emptyList()
            } else {
                val data = result.allSuggestions.mapNotNull { info ->
                    if (info.pt == null) null
                    else mapOf(
                        LocationPickerViewModel.POI_NAME to (info.key ?: ""),
                        LocationPickerViewModel.POI_ADDRESS to ((info.city ?: "") + " " + (info.district ?: "")),
                        LocationPickerViewModel.POI_LONGITUDE to info.pt.longitude.toString(),
                        LocationPickerViewModel.POI_LATITUDE to info.pt.latitude.toString()
                    )
                }
                _searchResults.value = data
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        suggestionSearch.destroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    // --- Actions ---

    fun setWindowType(type: WindowType) {
        _windowType.value = type
        if (type == WindowType.HISTORY) {
            fetchHistoryRecords()
        }
    }

    fun setCurrentPosition(lng: Double, lat: Double, alt: Double) {
        val bd = MapUtils.wgs2bd(lng, lat)
        _currentLocation.value = LatLng(bd[1], bd[0])
        _altitude.value = alt
    }

    fun updateMarkLocation(ll: LatLng) {
        _markLocation.value = ll
    }

    fun setSpeed(speed: Double) {
        _speed.value = speed
    }

    fun search(query: String, city: String?) {
        if (query.isNotEmpty()) {
            suggestionSearch.requestSuggestion(
                SuggestionSearchOption().keyword(query).city(city ?: "")
            )
        } else {
            _searchResults.value = emptyList()
        }
    }

    fun fetchHistoryRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            val records = mutableListOf<Map<String, Any>>()
            try {
                val dbHelper = DataBaseHistoryLocation(getApplication())
                val db = dbHelper.readableDatabase
                val cursor = db.query(
                    DataBaseHistoryLocation.TABLE_NAME, null,
                    "${DataBaseHistoryLocation.DB_COLUMN_ID} > ?", arrayOf("0"),
                    null, null, "${DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP} DESC"
                )

                while (cursor.moveToNext()) {
                    val item = mutableMapOf<String, Any>()
                    val id = cursor.getInt(0)
                    val location = cursor.getString(1)
                    val lng = cursor.getString(2)
                    val lat = cursor.getString(3)
                    val timestamp = cursor.getLong(4)
                    val bdLng = cursor.getString(5)
                    val bdLat = cursor.getString(6)

                    val doubleLng = BigDecimal(lng).setScale(11, RoundingMode.HALF_UP).toDouble()
                    val doubleLat = BigDecimal(lat).setScale(11, RoundingMode.HALF_UP).toDouble()
                    val doubleBdLng = BigDecimal(bdLng).setScale(11, RoundingMode.HALF_UP).toDouble()
                    val doubleBdLat = BigDecimal(bdLat).setScale(11, RoundingMode.HALF_UP).toDouble()

                    item[HistoryActivity.KEY_ID] = id.toString()
                    item[HistoryActivity.KEY_LOCATION] = location
                    item[HistoryActivity.KEY_TIME] = GoUtils.timeStamp2Date(timestamp.toString())
                    item[HistoryActivity.KEY_LNG_LAT_WGS] = "[经度:$doubleLng 纬度:$doubleLat]"
                    item[HistoryActivity.KEY_LNG_LAT_CUSTOM] = "[经度:$doubleBdLng 纬度:$doubleBdLat]"
                    records.add(item)
                }
                cursor.close()
                db.close()
                _historyRecords.value = records
            } catch (e: Exception) {
                KailLog.e(getApplication(), "JOYSTICK", "Error fetching history: ${e.message}")
            }
        }
    }

    fun confirmTeleport(actionListener: ActionListener) {
        val mark = _markLocation.value
        if (mark != null) {
            val wgs = MapUtils.bd2wgs(mark.longitude, mark.latitude)
            actionListener.onPositionInfo(wgs[0], wgs[1], _altitude.value)
            setWindowType(WindowType.JOYSTICK)
            _markLocation.value = null
        }
    }

    fun selectHistoryRecord(record: Map<String, Any>, actionListener: ActionListener) {
        try {
            val wgs84LatLng = record[HistoryActivity.KEY_LNG_LAT_WGS].toString()
            val inner = wgs84LatLng.substring(wgs84LatLng.indexOf('[') + 1, wgs84LatLng.indexOf(']'))
            val parts = inner.split(" ".toRegex()).toTypedArray()
            val wgs84Longitude = parts[0].substring(parts[0].indexOf(':') + 1).toDouble()
            val wgs84Latitude = parts[1].substring(parts[1].indexOf(':') + 1).toDouble()
            
            actionListener.onPositionInfo(wgs84Longitude, wgs84Latitude, _altitude.value)
            setWindowType(WindowType.JOYSTICK)
        } catch (e: Exception) {
            KailLog.e(getApplication(), "JOYSTICK", "Error selecting history: ${e.message}")
        }
    }

    fun updateRouteStatus(progress: Float, distance: String, currentLatLng: LatLng?) {
        _routeProgress.value = progress
        _routeTotalDistance.value = distance
        currentLatLng?.let { _currentLocation.value = it }
    }

    fun setRoutePauseState(isPaused: Boolean) {
        _isRoutePaused.value = isPaused
    }

    fun setRouteSpeed(speed: Double) {
        _routeSpeed.value = speed
    }

    // --- Helper Methods ---

    enum class WindowType {
        JOYSTICK, MAP, HISTORY, ROUTE_CONTROL
    }

    /**
     * Interface for actions triggered by the joystick UI that need to be handled by the service.
     */
    interface ActionListener {
        fun onMoveInfo(speed: Double, disLng: Double, disLat: Double, angle: Double)
        fun onPositionInfo(lng: Double, lat: Double, alt: Double)
        fun onRouteControl(action: String)
        fun onRouteSeek(progress: Float)
        fun onRouteSpeedChange(speed: Double)
    }
}
