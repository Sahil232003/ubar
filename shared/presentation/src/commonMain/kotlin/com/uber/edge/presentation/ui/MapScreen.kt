package com.uber.edge.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.uber.edge.presentation.map.GeoJsonBuilders
import com.uber.edge.presentation.map.PuckInterpolator
import com.uber.edge.presentation.state.PuckUi
import com.uber.edge.presentation.viewmodel.MapViewModel

/**
 * Authoritative MapScreen in Compose Multiplatform.
 * Adheres to Section 11.3 layout flattening and repaint boundary rules.
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    val interpolator = remember { PuckInterpolator(defaultStepDurationMs = 1000L) }
    var interpolatedPuck by remember { mutableStateOf(uiState.driver) }

    // Update interpolator target whenever new driver fix arrives
    LaunchedEffect(uiState.driver) {
        interpolator.updateTarget(uiState.driver, uiState.driver.interpolateFromMs)
    }

    // 60 fps Display-Refresh animation loop
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTimeMs ->
                interpolatedPuck = interpolator.evaluate(frameTimeMs)
            }
        }
    }

    // Shallow container overlay (depth <= 5)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // Dark road backdrop for map
    ) {
        // 1. Native Map Layer Placeholder (MapLibre GPU Surface)
        Box(modifier = Modifier.fillMaxSize()) {
            // Native View Host (AndroidView / UIKitView) consumes:
            // - GeoJsonBuilders.buildPuckGeoJson(interpolatedPuck)
            // - GeoJsonBuilders.buildRouteGeoJson(uiState.route)
            // - GeoJsonBuilders.buildSurgeGeoJson(uiState.surge)
        }

        // 2. Top Banner: Connection Status
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            ConnectionBanner(connection = uiState.connection)
        }

        // 3. Bottom Layer: Trip Card & Diagnostics HUD
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            TripCard(
                trip = uiState.trip,
                onAction = { newStatus -> viewModel.transitionTrip(newStatus) }
            )
        }

        // 4. Developer Diagnostics HUD (Top Right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 16.dp)
        ) {
            DiagnosticsOverlay(
                state = uiState.copy(driver = interpolatedPuck)
            )
        }
    }
}
