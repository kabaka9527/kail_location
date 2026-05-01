package com.kail.location.views.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kail.location.R
import com.kail.location.viewmodels.SettingsViewModel
import com.kail.location.xposed.core.FakeLocState

/**
 * 设置屏幕主界面
 * 展示所有可配置的应用选项，按类别分组显示。
 *
 * @param viewModel 设置界面的 ViewModel，用于读取和更新偏好设置
 * @param onBackClick 返回按钮点击回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    // State observation
    val joystickType by viewModel.joystickType.collectAsState()
    val walkSpeed by viewModel.walkSpeed.collectAsState()
    val runSpeed by viewModel.runSpeed.collectAsState()
    val bikeSpeed by viewModel.bikeSpeed.collectAsState()
    val altitude by viewModel.altitude.collectAsState()
    val mockSpeed by viewModel.mockSpeed.collectAsState()
    val accuracy by viewModel.accuracy.collectAsState()
    val minSatellites by viewModel.minSatellites.collectAsState()
    val reportInterval by viewModel.reportInterval.collectAsState()
    val randomOffset by viewModel.randomOffset.collectAsState()
    val latOffset by viewModel.latOffset.collectAsState()
    val lonOffset by viewModel.lonOffset.collectAsState()
    val logEnabled by viewModel.logEnabled.collectAsState()
    val historyExpiration by viewModel.historyExpiration.collectAsState()
    val baiduMapKey by viewModel.baiduMapKey.collectAsState()
    val mapZoom by viewModel.mapZoom.collectAsState()
    val gpsSatelliteSim by viewModel.gpsSatelliteSim.collectAsState()
    val enableAGPS by viewModel.enableAGPS.collectAsState()
    val enableNMEA by viewModel.enableNMEA.collectAsState()
    val enableMockWifi by viewModel.enableMockWifi.collectAsState()
    val allowGetCurrentLocation by viewModel.allowGetCurrentLocation.collectAsState()
    val allowRegisterListener by viewModel.allowRegisterListener.collectAsState()
    val allowGeofence by viewModel.allowGeofence.collectAsState()
    val allowGetFromLocation by viewModel.allowGetFromLocation.collectAsState()
    val disableFusedLocation by viewModel.disableFusedLocation.collectAsState()
    val downgradeToCdma by viewModel.downgradeToCdma.collectAsState()
    val disableWifiScan by viewModel.disableWifiScan.collectAsState()
    val loopBroadcast by viewModel.loopBroadcast.collectAsState()
    val hideMock by viewModel.hideMock.collectAsState()
    val simScheme by viewModel.simScheme.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_menu_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // ===== Group: 移动参数 =====
            PreferenceCategory(title = stringResource(R.string.setting_group_move))

            ListPreference(
                title = stringResource(R.string.setting_joystick_type),
                currentValue = joystickType,
                entries = stringArrayResource(R.array.array_joystick_type),
                entryValues = stringArrayResource(R.array.array_joystick_type_values),
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_JOYSTICK_TYPE, it) }
            )

            EditTextPreference(
                title = "步行速度 (m/s)",
                value = walkSpeed,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_WALK_SPEED, it) }
            )

            EditTextPreference(
                title = "跑步速度 (m/s)",
                value = runSpeed,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_RUN_SPEED, it) }
            )

            EditTextPreference(
                title = "骑行速度 (m/s)",
                value = bikeSpeed,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_BIKE_SPEED, it) }
            )

            // ===== Group: 位置模拟参数 =====
            PreferenceCategory(title = "位置模拟参数")

            EditTextPreference(
                title = "海拔高度 (米)",
                value = altitude,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_ALTITUDE, it) }
            )

            EditTextPreference(
                title = "模拟移动速度 (m/s)",
                value = mockSpeed,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_MOCK_SPEED, it) }
            )

            EditTextPreference(
                title = "定位抖动范围 (米)",
                value = accuracy,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_ACCURACY, it) }
            )

            EditTextPreference(
                title = "最少模拟卫星数量",
                value = minSatellites,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_MIN_SATELLITES, it) },
                description = "仅支持北斗卫星"
            )

            EditTextPreference(
                title = "上报位置频率 (ms)",
                value = reportInterval,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_REPORT_INTERVAL, it) },
                description = "单位毫秒，默认 100ms"
            )

            // ===== Group: 位置偏移 =====
            PreferenceCategory(title = stringResource(R.string.setting_group_location_offset))

            SwitchPreference(
                title = stringResource(R.string.setting_random_offset),
                checked = randomOffset,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_RANDOM_OFFSET, it) },
                summary = stringResource(R.string.setting_random_offset_summary)
            )

            EditTextPreference(
                title = "纬度最大偏移 (米)",
                value = latOffset,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_LAT_OFFSET, it) }
            )

            EditTextPreference(
                title = "经度最大偏移 (米)",
                value = lonOffset,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_LON_OFFSET, it) }
            )

            // ===== Group: 卫星与信号 =====
            PreferenceCategory(title = "卫星与信号模拟")

            SwitchPreference(
                title = "模拟 GPS 卫星信号",
                checked = gpsSatelliteSim,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_GPS_SATELLITE_SIM, it) },
                summary = "伪造北斗卫星数量和信号强度"
            )

            SwitchPreference(
                title = "启用 AGPS 模块",
                checked = enableAGPS,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_ENABLE_AGPS, it) },
                summary = "辅助定位相关（实验性）"
            )

            SwitchPreference(
                title = "启用 NMEA 模块",
                checked = enableNMEA,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_ENABLE_NMEA, it) },
                summary = "伪造 NMEA 数据输出"
            )

            SwitchPreference(
                title = "模拟 WLAN 数据",
                checked = enableMockWifi,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_ENABLE_MOCK_WIFI, it) },
                summary = "伪造 WiFi 定位数据"
            )

            ListPreference(
                title = "传感器模拟方案",
                currentValue = simScheme,
                entries = arrayOf("傅里叶级数 (Fourier)", "正弦+随机扰动 (Sine+Noise)"),
                entryValues = arrayOf("0", "1"),
                onValueChange = {
                    viewModel.updateStringPreference(SettingsViewModel.KEY_SIM_SCHEME, it)
                    FakeLocState.setSimScheme(it.toIntOrNull() ?: 0)
                }
            )

            // ===== Group: 拦截控制 =====
            PreferenceCategory(title = "拦截与降级控制")

            SwitchPreference(
                title = "允许 GetCurrentLocation",
                checked = allowGetCurrentLocation,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_ALLOW_GET_CURRENT_LOCATION, it) },
                summary = "关闭则拦截 getCurrentLocation 请求"
            )

            SwitchPreference(
                title = "允许注册位置监听器",
                checked = allowRegisterListener,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_ALLOW_REGISTER_LISTENER, it) },
                summary = "关闭则拦截 registerLocationListener"
            )

            SwitchPreference(
                title = "允许地理围栏请求",
                checked = allowGeofence,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_ALLOW_GEOFENCE, it) },
                summary = "关闭则拦截 addGeofence"
            )

            SwitchPreference(
                title = "允许位置获取",
                checked = allowGetFromLocation,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_ALLOW_GET_FROM_LOCATION, it) },
                summary = "关闭则拦截 getFromLocation 等反查"
            )

            SwitchPreference(
                title = "禁用融合定位",
                checked = disableFusedLocation,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_DISABLE_FUSED_LOCATION, it) },
                summary = "阻止应用使用 fused provider"
            )

            SwitchPreference(
                title = "网络定位降级为 CDMA",
                checked = downgradeToCdma,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_DOWNGRADE_TO_CDMA, it) },
                summary = "将网络定位结果伪装为基站定位"
            )

            SwitchPreference(
                title = "禁用扫描 WiFi 列表",
                checked = disableWifiScan,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_DISABLE_WIFI_SCAN, it) },
                summary = "阻止应用获取真实 WiFi 扫描结果"
            )

            // ===== Group: 反检测 =====
            PreferenceCategory(title = "反检测与防拉回")

            SwitchPreference(
                title = "隐藏模拟位置",
                checked = hideMock,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_HIDE_MOCK, it) },
                summary = "移除 Location.isFromMockProvider 标记"
            )

            SwitchPreference(
                title = "反定位拉回",
                checked = loopBroadcast,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_LOOP_BROADCAST, it) },
                summary = "持续广播位置，一定程度解决被系统拉回"
            )

            // ===== Group: 日志/其他 =====
            PreferenceCategory(title = "其他")

            EditTextPreference(
                title = "百度地图 Key (需重启生效)",
                value = baiduMapKey,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_BAIDU_MAP_KEY, it) }
            )

            SwitchPreference(
                title = "启用日志",
                checked = logEnabled,
                onCheckedChange = { viewModel.updateBooleanPreference(SettingsViewModel.KEY_LOG_ENABLED, it) },
                summary = "控制控制台输出与本地文件保存"
            )

            EditTextPreference(
                title = "历史记录有效期 (天)",
                value = historyExpiration,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_HISTORY_EXPIRATION, it) }
            )

            EditTextPreference(
                title = "悬浮窗地图缩放比例",
                value = mapZoom,
                onValueChange = { viewModel.updateStringPreference(SettingsViewModel.KEY_MAP_ZOOM, it) },
                description = "范围 10-21，数值越大地图越详细"
            )

            ListItem(
                headlineContent = { Text("当前版本") },
                supportingContent = { Text(viewModel.appVersion) }
            )
        }
    }
}

/**
 * 设置类别标题组件
 */
@Composable
fun PreferenceCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * 开关类设置项组件
 */
@Composable
fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

/**
 * 文本编辑类设置项组件
 */
@Composable
fun EditTextPreference(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String = ""
) {
    var showDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(value.ifEmpty { "未设置" })
                if (description.isNotEmpty()) {
                    Text(description, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        modifier = Modifier.clickable { showDialog = true }
    )

    if (showDialog) {
        var tempValue by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = tempValue,
                        onValueChange = { tempValue = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        singleLine = true
                    )
                    if (description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempValue.isNotBlank()) {
                            onValueChange(tempValue)
                        }
                        showDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 列表选择类设置项组件
 */
@Composable
fun ListPreference(
    title: String,
    currentValue: String,
    entries: Array<String>,
    entryValues: Array<String>,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    val index = entryValues.indexOf(currentValue)
    val displayValue = if (index >= 0 && index < entries.size) entries[index] else currentValue

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(displayValue) },
        modifier = Modifier.clickable { showDialog = true }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    entries.forEachIndexed { i, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChange(entryValues[i])
                                    showDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (entryValues[i] == currentValue),
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = entry)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
