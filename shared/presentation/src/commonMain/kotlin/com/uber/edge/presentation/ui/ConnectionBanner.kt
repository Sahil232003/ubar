package com.uber.edge.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uber.edge.presentation.state.ConnectionUiState

/**
 * FR-12: Connection status banner: Live / Reconnecting / Offline (n events pending)
 */
@Composable
fun ConnectionBanner(
    connection: ConnectionUiState,
    modifier: Modifier = Modifier
) {
    val (text, bgColor, textColor) = when (connection) {
        is ConnectionUiState.Live -> Triple(
            "● LIVE STREAMING",
            Color(0xFF10B981),
            Color.White
        )
        is ConnectionUiState.Reconnecting -> Triple(
            "↻ RECONNECTING (Attempt ${connection.attempt})",
            Color(0xFFF59E0B),
            Color.Black
        )
        is ConnectionUiState.Offline -> Triple(
            "▲ OFFLINE (${connection.pendingOps} events queued in outbox)",
            Color(0xFFEF4444),
            Color.White
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
