package com.kail.location.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.kail.location.models.RouteInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.ArrayList

import com.kail.location.models.UpdateInfo
import com.kail.location.utils.UpdateChecker
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.baidu.mapapi.model.LatLng
import org.json.JSONObject
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import androidx.core.content.ContextCompat
import com.kail.location.service.ServiceGoRoot
import com.kail.location.service.ServiceGoNoroot

import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.baidu.mapapi.search.sug.OnGetSuggestionResultListener
import com.baidu.mapapi.search.sug.SuggestionResult

/**
 * 路线模拟页面的 ViewModel。
 * 负责加载历史路线并检查应用更新。
 *
 * @property application 应用上下文。
 */
class RouteSimulationViewModel(application: Application) : AndroidViewModel(application) {
    private val _historyRoutes = MutableStateFlow<List<RouteInfo>>(emptyList())
    /**
     * 历史路线列表的状态流。
     */
    val historyRoutes: StateFlow<List<RouteInfo>> = _historyRoutes.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    /**
     * 可用更新信息的状态流。
     */
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    private val _installUri = MutableStateFlow<android.net.Uri?>(null)
    val installUri: StateFlow<android.net.Uri?> = _installUri.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    private val _selectedRouteId = MutableStateFlow<String?>(null)
    val selectedRouteId: StateFlow<String?> = _selectedRouteId.asStateFlow()

    private val _settings = MutableStateFlow(com.kail.location.models.SimulationSettings())
    val settings: StateFlow<com.kail.location.models.SimulationSettings> = _settings.asStateFlow()

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val _runMode = MutableStateFlow("root")
    val runMode: StateFlow<String> = _runMode.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // Search
    private val _searchResults = MutableStateFlow<List<Map<String, Any>>>(emptyList())
    val searchResults: StateFlow<List<Map<String, Any>>> = _searchResults.asStateFlow()

    private val _searchMarker = MutableStateFlow<LatLng?>(null)
    /**
     * 当前搜索结果选中点的状态流（用于在地图上标点）。
     */
    val searchMarker: StateFlow<LatLng?> = _searchMarker.asStateFlow()

    private val suggestionSearch: SuggestionSearch = SuggestionSearch.newInstance()

    companion object {
        const val POI_NAME = "name"
        const val POI_ADDRESS = "address"
        const val POI_LATITUDE = "latitude"
        const val POI_LONGITUDE = "longitude"
    }
    
    init {
        _runMode.value = sharedPreferences.getString("setting_run_mode", "root") ?: "root"
        loadSettings()
        loadRoutes()

        suggestionSearch.setOnGetSuggestionResultListener(object : OnGetSuggestionResultListener {
            override fun onGetSuggestionResult(res: SuggestionResult?) {
                if (res == null || res.allSuggestions == null) {
                    _searchResults.value = emptyList()
                    return
                }
                val results = res.allSuggestions.mapNotNull { suggestion ->
                    if (suggestion.pt == null) null
                    else mapOf(
                        POI_NAME to (suggestion.key ?: ""),
                        POI_ADDRESS to (suggestion.address ?: ""),
                        POI_LATITUDE to suggestion.pt.latitude,
                        POI_LONGITUDE to suggestion.pt.longitude
                    )
                }
                _searchResults.value = results
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        suggestionSearch.destroy()
    }

    fun search(keyword: String, city: String?) {
        if (keyword.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        suggestionSearch.requestSuggestion(
            SuggestionSearchOption()
                .city(city ?: "全国")
                .keyword(keyword)
        )
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    /**
     * 选中搜索结果。
     * 更新搜索 Marker 并清空搜索结果列表。
     *
     * @param lat 纬度
     * @param lng 经度
     */
    fun selectSearchResult(lat: Double, lng: Double) {
        _searchMarker.value = LatLng(lat, lng)
        _searchResults.value = emptyList()
    }

    /**
     * 清除搜索 Marker。
     */
    fun clearSearchMarker() {
        _searchMarker.value = null
    }

    /**
     * 检查应用更新。
     *
     * @param context 用于检查更新的上下文。
     * @param isAuto 是否为自动检查。
     */
    fun checkUpdate(context: Context, isAuto: Boolean = false) {
        UpdateChecker.check(context) { info, error ->
            if (info != null) {
                _updateInfo.value = info
            } else {
                if (!isAuto) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (error != null) {
                            Toast.makeText(context, "检查更新失败: $error", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * 关闭更新弹窗。
     */
    fun dismissUpdateDialog() {
        _updateInfo.value = null
    }
    
    fun clearInstallUri() {
        _installUri.value = null
    }
    
    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun startUpdateDownload(context: Context) {
        val info = _updateInfo.value ?: return
        if (_isDownloading.value) return
        _isDownloading.value = true
        _downloadProgress.value = 0
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(info.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response")
                val body = response.body ?: throw java.io.IOException("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                val dir = java.io.File(context.getExternalFilesDir(null), "Updates")
                if (!dir.exists()) dir.mkdirs()
                val outFile = java.io.File(dir, info.filename)
                body.byteStream().use { input ->
                    java.io.FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var sum = 0L
                        while (true) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            sum += bytesRead
                            if (total > 0) {
                                val pct = ((sum * 100) / total).toInt().coerceIn(0, 100)
                                _downloadProgress.value = pct
                            }
                        }
                        output.flush()
                    }
                }
                _downloadProgress.value = 100
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileProvider",
                    outFile
                )
                _installUri.value = uri
            } catch (_: Exception) {
                _isDownloading.value = false
            } finally {
                _isDownloading.value = false
            }
        }
    }

    fun setSimulating(value: Boolean) {
        _isSimulating.value = value
    }

    fun startSimulation(): Boolean {
        val app = getApplication<Application>()
        val points = getSelectedRoutePoints() ?: return false
        if (points.size < 4) return false

        // 关键修复：启动前强制同步一次最新的运行模式
        val currentRunMode = sharedPreferences.getString("setting_run_mode", "root") ?: "root"
        _runMode.value = currentRunMode

        // 检查步频模拟权限
        if (settings.value.stepFreqSimulation) {
            if (currentRunMode != "root") {
                _toastMessage.value = "步频模拟需要 ROOT 模式"
                return false
            }
            val pollOffset = sharedPreferences.getString("setting_poll_offset", "") ?: ""
            if (pollOffset.isEmpty()) {
                _toastMessage.value = "请先在设置中配置传感器参数"
                return false
            }
        }

        val serviceClass = if (currentRunMode == "root") ServiceGoRoot::class.java else ServiceGoNoroot::class.java
        val intent = Intent(app, serviceClass)
        val extraRoutePoints = if (currentRunMode == "root") ServiceGoRoot.EXTRA_ROUTE_POINTS else ServiceGoNoroot.EXTRA_ROUTE_POINTS
        val extraRouteLoop = if (currentRunMode == "root") ServiceGoRoot.EXTRA_ROUTE_LOOP else ServiceGoNoroot.EXTRA_ROUTE_LOOP
        val extraJoystickEnabled = if (currentRunMode == "root") ServiceGoRoot.EXTRA_JOYSTICK_ENABLED else ServiceGoNoroot.EXTRA_JOYSTICK_ENABLED
        val extraRouteSpeed = if (currentRunMode == "root") ServiceGoRoot.EXTRA_ROUTE_SPEED else ServiceGoNoroot.EXTRA_ROUTE_SPEED
        val extraCoordType = if (currentRunMode == "root") ServiceGoRoot.EXTRA_COORD_TYPE else ServiceGoNoroot.EXTRA_COORD_TYPE
        val extraSpeedFluctuation = if (currentRunMode == "root") ServiceGoRoot.EXTRA_SPEED_FLUCTUATION else ServiceGoNoroot.EXTRA_SPEED_FLUCTUATION
        intent.putExtra(extraRoutePoints, points)
        intent.putExtra(extraRouteLoop, settings.value.isLoop)
        intent.putExtra(extraJoystickEnabled, false)
        intent.putExtra(extraRouteSpeed, settings.value.speed)
        intent.putExtra(extraCoordType, "BD09")
        intent.putExtra(extraSpeedFluctuation, settings.value.speedFluctuation)
        if (currentRunMode == "root") {
            intent.putExtra(ServiceGoRoot.EXTRA_STEP_ENABLED, settings.value.stepFreqSimulation)
            intent.putExtra(ServiceGoRoot.EXTRA_STEP_FREQ, settings.value.stepCadenceSpm)
            intent.putExtra("EXTRA_IS_ROUTE_SIMULATION", true)
        }
        ContextCompat.startForegroundService(app, intent)
        _isSimulating.value = true
        _isPaused.value = false
        return true
    }

    fun stopSimulation() {
        val app = getApplication<Application>()
        val serviceClass = if (_runMode.value == "root") ServiceGoRoot::class.java else ServiceGoNoroot::class.java
        app.stopService(Intent(app, serviceClass))
        _isSimulating.value = false
        _isPaused.value = false
    }

    fun pauseSimulation() {
        val app = getApplication<Application>()
        val serviceClass = if (_runMode.value == "root") ServiceGoRoot::class.java else ServiceGoNoroot::class.java
        val controlAction = if (_runMode.value == "root") ServiceGoRoot.CONTROL_PAUSE else ServiceGoNoroot.CONTROL_PAUSE
        val intent = Intent(app, serviceClass)
        intent.putExtra("EXTRA_CONTROL_ACTION", controlAction)
        app.startService(intent)
        _isPaused.value = true
    }

    fun resumeSimulation() {
        val app = getApplication<Application>()
        val serviceClass = if (_runMode.value == "root") ServiceGoRoot::class.java else ServiceGoNoroot::class.java
        val controlAction = if (_runMode.value == "root") ServiceGoRoot.CONTROL_RESUME else ServiceGoNoroot.CONTROL_RESUME
        val intent = Intent(app, serviceClass)
        intent.putExtra("EXTRA_CONTROL_ACTION", controlAction)
        app.startService(intent)
        _isPaused.value = false
    }
    fun setRunMode(mode: String) {
        _runMode.value = mode
        sharedPreferences.edit().putString("setting_run_mode", mode).apply()
    }

    fun selectRoute(id: String?) {
        _selectedRouteId.value = id
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val speed = prefs.getFloat("route_sim_speed", _settings.value.speed)
        val loop = prefs.getBoolean("route_sim_loop", _settings.value.isLoop)
        val speedFluctuation = prefs.getBoolean("route_sim_speed_fluctuation", _settings.value.speedFluctuation)
        val stepEnabled = prefs.getBoolean("route_sim_step_enabled", _settings.value.stepFreqSimulation)
        val raw = prefs.getFloat("route_sim_step_freq", _settings.value.stepCadenceSpm)
        val stepCadenceSpm = if (raw <= 10f) raw * 60f else raw
        _settings.value = _settings.value.copy(
            speed = speed, 
            isLoop = loop, 
            speedFluctuation = speedFluctuation, 
            stepFreqSimulation = stepEnabled, 
            stepCadenceSpm = stepCadenceSpm
        )
    }

    fun updateSpeed(speed: Float) {
        _settings.value = _settings.value.copy(speed = speed)
        PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putFloat("route_sim_speed", speed).apply()
        if (_isSimulating.value) {
            val app = getApplication<Application>()
            val serviceClass = if (_runMode.value == "root") ServiceGoRoot::class.java else ServiceGoNoroot::class.java
            val controlAction = if (_runMode.value == "root") ServiceGoRoot.CONTROL_SET_SPEED else ServiceGoNoroot.CONTROL_SET_SPEED
            val routeSpeed = if (_runMode.value == "root") ServiceGoRoot.EXTRA_ROUTE_SPEED else ServiceGoNoroot.EXTRA_ROUTE_SPEED
            val intent = Intent(app, serviceClass)
            intent.putExtra("EXTRA_CONTROL_ACTION", controlAction)
            intent.putExtra(routeSpeed, speed)
            app.startService(intent)
        }
    }

    fun updateLoop(loop: Boolean) {
        _settings.value = _settings.value.copy(isLoop = loop)
        PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putBoolean("route_sim_loop", loop).apply()
    }

    fun updateSpeedFluctuation(enabled: Boolean) {
        _settings.value = _settings.value.copy(speedFluctuation = enabled)
        PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putBoolean("route_sim_speed_fluctuation", enabled).apply()
        if (_isSimulating.value) {
            val app = getApplication<Application>()
            val serviceClass = if (_runMode.value == "root") ServiceGoRoot::class.java else ServiceGoNoroot::class.java
            val controlAction = if (_runMode.value == "root") ServiceGoRoot.CONTROL_SET_SPEED_FLUCTUATION else ServiceGoNoroot.CONTROL_SET_SPEED_FLUCTUATION
            val speedFluctuation = if (_runMode.value == "root") ServiceGoRoot.EXTRA_SPEED_FLUCTUATION else ServiceGoNoroot.EXTRA_SPEED_FLUCTUATION
            val intent = Intent(app, serviceClass)
            intent.putExtra("EXTRA_CONTROL_ACTION", controlAction)
            intent.putExtra(speedFluctuation, enabled)
            app.startService(intent)
        }
    }

    fun updateStepFreqSimulation(enabled: Boolean) {
        _settings.value = _settings.value.copy(stepFreqSimulation = enabled)
        PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putBoolean("route_sim_step_enabled", enabled).apply()
        if (_isSimulating.value && _runMode.value == "root") {
            val app = getApplication<Application>()
            val intent = Intent(app, ServiceGoRoot::class.java)
            intent.putExtra("EXTRA_CONTROL_ACTION", ServiceGoRoot.CONTROL_SET_STEP)
            intent.putExtra(ServiceGoRoot.EXTRA_STEP_ENABLED, enabled)
            intent.putExtra(ServiceGoRoot.EXTRA_STEP_FREQ, _settings.value.stepCadenceSpm)
            app.startService(intent)
        }
    }

    fun updateStepCadenceSpm(spm: Float) {
        _settings.value = _settings.value.copy(stepCadenceSpm = spm)
        PreferenceManager.getDefaultSharedPreferences(getApplication())
            .edit().putFloat("route_sim_step_freq", spm).apply()
        if (_isSimulating.value && _runMode.value == "root") {
            val app = getApplication<Application>()
            val intent = Intent(app, ServiceGoRoot::class.java)
            intent.putExtra("EXTRA_CONTROL_ACTION", ServiceGoRoot.CONTROL_SET_STEP)
            intent.putExtra(ServiceGoRoot.EXTRA_STEP_ENABLED, _settings.value.stepFreqSimulation)
            intent.putExtra(ServiceGoRoot.EXTRA_STEP_FREQ, spm)
            app.startService(intent)
        }
    }

    fun updateMode(mode: com.kail.location.models.TransportMode) {
        _settings.value = _settings.value.copy(mode = mode)
    }

    /**
     * 从 SharedPreferences 加载已保存的路线。
     */
    fun loadRoutes() {
        viewModelScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val res = prefs.getString("saved_routes", "[]") ?: "[]"
            val list = parseRoutes(res)
            _historyRoutes.value = list
            enrichRouteNamesIfNeeded()
        }
    }

    /**
     * 解析路线的 JSON 字符串为 RouteInfo 列表。
     *
     * @param json 包含路线数据的 JSON 字符串。
     * @return RouteInfo 列表。
     */
    private fun parseRoutes(json: String): List<RouteInfo> {
        return try {
            val arr = JSONArray(json)
            val list = ArrayList<Pair<Long, RouteInfo>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val time = obj.optLong("time", 0L)
                val points = obj.optJSONArray("points") ?: continue
                if (points.length() == 0) continue
                
                val first = points.optJSONObject(0) ?: continue
                val last = points.optJSONObject(points.length() - 1) ?: continue
                val s = obj.optString("startName",
                    String.format("%.6f,%.6f", first.optDouble("lat"), first.optDouble("lng")))
                val e = obj.optString("endName",
                    String.format("%.6f,%.6f", last.optDouble("lat"), last.optDouble("lng")))
                list.add(time to RouteInfo(time.toString(), s, e, ""))
            }
            list.sortByDescending { it.first }
            list.map { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRoute(points: List<LatLng>) {
        viewModelScope.launch {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
                val existing = prefs.getString("saved_routes", "[]") ?: "[]"
                val arr = JSONArray(existing)
                val obj = JSONObject()
                obj.put("time", System.currentTimeMillis())
                val pts = JSONArray()
                points.forEach { pt ->
                    val p = JSONObject()
                    p.put("lat", pt.latitude)
                    p.put("lng", pt.longitude)
                    pts.put(p)
                }
                obj.put("points", pts)
                arr.put(obj)
                prefs.edit().putString("saved_routes", arr.toString()).apply()
                _historyRoutes.value = parseRoutes(arr.toString())
                enrichNamesForRoute(obj)
            } catch (_: Exception) {}
        }
    }

    fun getLatestRoutePoints(): DoubleArray? {
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val res = prefs.getString("saved_routes", "[]") ?: "[]"
            val arr = JSONArray(res)
            if (arr.length() == 0) return null
            val obj = arr.optJSONObject(arr.length() - 1) ?: return null
            val points = obj.optJSONArray("points") ?: return null
            val out = DoubleArray(points.length() * 2)
            var i = 0
            for (idx in 0 until points.length()) {
                val p = points.optJSONObject(idx) ?: continue
                out[i++] = p.optDouble("lng")
                out[i++] = p.optDouble("lat")
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    fun getSelectedRoutePoints(): DoubleArray? {
        val id = _selectedRouteId.value
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        val res = prefs.getString("saved_routes", "[]") ?: "[]"
        val arr = JSONArray(res)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("time", 0L).toString() == id) {
                val points = obj.optJSONArray("points") ?: return null
                val out = DoubleArray(points.length() * 2)
                var j = 0
                for (idx in 0 until points.length()) {
                    val p = points.optJSONObject(idx) ?: continue
                    out[j++] = p.optDouble("lng")
                    out[j++] = p.optDouble("lat")
                }
                return out
            }
        }
        return getLatestRoutePoints()
    }

    fun renameRoute(id: String, newName: String) {
        viewModelScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val res = prefs.getString("saved_routes", "[]") ?: "[]"
            val arr = JSONArray(res)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optLong("time", 0L).toString() == id) {
                    obj.put("label", newName)
                    // 也更新 start/end 文本以便旧界面显示更友好
                    obj.put("startName", newName)
                    obj.put("endName", newName)
                    break
                }
            }
            prefs.edit().putString("saved_routes", arr.toString()).apply()
            _historyRoutes.value = parseRoutes(arr.toString())
        }
    }

    fun deleteRoute(id: String) {
        viewModelScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val res = prefs.getString("saved_routes", "[]") ?: "[]"
            val arr = JSONArray(res)
            val outArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optLong("time", 0L).toString() != id) {
                    outArr.put(obj)
                }
            }
            prefs.edit().putString("saved_routes", outArr.toString()).apply()
            _historyRoutes.value = parseRoutes(outArr.toString())
            if (_selectedRouteId.value == id) _selectedRouteId.value = null
        }
    }

    private fun enrichRouteNamesIfNeeded() {
        viewModelScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
            val res = prefs.getString("saved_routes", "[]") ?: "[]"
            val arr = JSONArray(res)
            var changed = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (!obj.has("startName") || !obj.has("endName")) {
                    enrichNamesForRoute(obj)
                    changed = true
                }
            }
            if (changed) {
                prefs.edit().putString("saved_routes", arr.toString()).apply()
                _historyRoutes.value = parseRoutes(arr.toString())
            }
        }
    }
    private fun enrichNamesForRoute(obj: JSONObject) {
        try {
            val points = obj.optJSONArray("points") ?: return
            if (points.length() < 1) return
            val first = points.optJSONObject(0) ?: return
            val last = points.optJSONObject(points.length() - 1) ?: return
            reverseGeocode(first.optDouble("lat"), first.optDouble("lng")) { name ->
                obj.put("startName", name)
                val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
                val res = prefs.getString("saved_routes", "[]") ?: "[]"
                val arr = JSONArray(res)
                prefs.edit().putString("saved_routes", arr.toString()).apply()
                _historyRoutes.value = parseRoutes(arr.toString())
            }
            reverseGeocode(last.optDouble("lat"), last.optDouble("lng")) { name ->
                obj.put("endName", name)
                val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
                val res = prefs.getString("saved_routes", "[]") ?: "[]"
                val arr = JSONArray(res)
                prefs.edit().putString("saved_routes", arr.toString()).apply()
                _historyRoutes.value = parseRoutes(arr.toString())
            }
        } catch (_: Exception) {}
    }

    private fun reverseGeocode(lat: Double, lng: Double, onResult: (String) -> Unit) {
        try {
            val coder = GeoCoder.newInstance()
            coder.setOnGetGeoCodeResultListener(object : OnGetGeoCoderResultListener {
                override fun onGetGeoCodeResult(geoCodeResult: com.baidu.mapapi.search.geocode.GeoCodeResult?) {}
                override fun onGetReverseGeoCodeResult(result: com.baidu.mapapi.search.geocode.ReverseGeoCodeResult?) {
                    val name = if (result != null && result.error == SearchResult.ERRORNO.NO_ERROR) {
                        result.address ?: "未知地点"
                    } else "未知地点"
                    onResult(name)
                    coder.destroy()
                }
            })
            coder.reverseGeoCode(ReverseGeoCodeOption().location(com.baidu.mapapi.model.LatLng(lat, lng)))
        } catch (_: Exception) {
            onResult("未知地点")
        }
    }
}
