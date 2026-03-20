package com.kail.location.views.joystick

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.baidu.mapapi.map.MapView
import com.kail.location.viewmodels.JoystickViewModel

/**
 * Root Composable for all joystick-related floating windows.
 * Switches between different overlays based on the ViewModel state.
 */
@Composable
fun JoystickRoot(
    viewModel: JoystickViewModel,
    mapView: MapView?,
    actionListener: JoystickViewModel.ActionListener,
    onMoveInfo: (Boolean, Double, Double) -> Unit,
    onWindowDrag: (Float, Float) -> Unit,
    onClose: () -> Unit
) {
    val windowType by viewModel.windowType.collectAsState()
    val isPaused by viewModel.isRoutePaused.collectAsState()
    val routeSpeed by viewModel.routeSpeed.collectAsState()
    val routeProgress by viewModel.routeProgress.collectAsState()
    val routeTotalDistance by viewModel.routeTotalDistance.collectAsState()
    val historyRecords by viewModel.historyRecords.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    when (windowType) {
        JoystickViewModel.WindowType.JOYSTICK -> {
            JoyStickOverlay(
                viewModel = viewModel,
                onMoveInfo = onMoveInfo,
                onWindowDrag = onWindowDrag
            )
        }
        JoystickViewModel.WindowType.MAP -> {
            if (mapView != null) {
                JoyStickMapOverlay(
                    mapView = mapView,
                    onClose = { viewModel.setWindowType(JoystickViewModel.WindowType.JOYSTICK) },
                    onWindowDrag = onWindowDrag,
                    onGo = { viewModel.confirmTeleport(actionListener) },
                    onBackToCurrent = {
                        // Centering logic handled by MapView/BaiduMap in the manager
                    },
                    onSearch = { query -> viewModel.search(query, null) },
                    searchResults = searchResults,
                    onSelectSearchResult = { item ->
                        val lat = item[com.kail.location.viewmodels.LocationPickerViewModel.POI_LATITUDE].toString().toDouble()
                        val lng = item[com.kail.location.viewmodels.LocationPickerViewModel.POI_LONGITUDE].toString().toDouble()
                        viewModel.updateMarkLocation(com.baidu.mapapi.model.LatLng(lat, lng))
                    }
                )
            }
        }
        JoystickViewModel.WindowType.HISTORY -> {
            JoyStickHistoryOverlay(
                historyRecords = historyRecords,
                onClose = { viewModel.setWindowType(JoystickViewModel.WindowType.JOYSTICK) },
                onWindowDrag = onWindowDrag,
                onSelectRecord = { record -> viewModel.selectHistoryRecord(record, actionListener) },
                onSearch = { /* Search logic if needed */ }
            )
        }
        JoystickViewModel.WindowType.ROUTE_CONTROL -> {
            FloatingNavigationControlOverlay(
                mapView = mapView,
                isPaused = isPaused,
                speed = routeSpeed,
                progress = routeProgress,
                totalDistance = routeTotalDistance,
                onPauseResume = { 
                    val newState = !isPaused
                    viewModel.setRoutePauseState(newState)
                    actionListener.onRouteControl(if (newState) "pause" else "resume")
                },
                onStop = { actionListener.onRouteControl("stop") },
                onRestart = { actionListener.onRouteControl("restart") },
                onSeek = { progress -> actionListener.onRouteSeek(progress) },
                onSpeedChange = { speed ->
                    viewModel.setRouteSpeed(speed)
                    actionListener.onRouteSpeedChange(speed)
                },
                onWindowDrag = onWindowDrag
            )
        }
    }
}
