package com.uber.edge.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uber.edge.presentation.state.MapUiState

/**
 * FR-13: Developer diagnostics screen: fix rate, WS RTT, outbox depth, sampling profile, battery %
 */
@Composable
fun DiagnosticsOverlay(
    state: MapUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xEE1E293B), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("DIAGNOSTICS & TELEMETRY HUD", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sampling Mode:", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(state.samplingMode.name, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Puck Coordinates:", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(
                "%.5f, %.5f".format(state.driver.lat, state.driver.lng),
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Accuracy / Bearing:", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(
                "±%.1fm / %.1f°".format(state.driver.accuracyM, state.driver.bearing),
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Surge Cells (H3 Res 8):", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("${state.surge.cells.size} active", color = Color(0xFFFBBF24), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
