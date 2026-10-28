package com.uber.edge.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uber.edge.presentation.ui.ConnectionBanner
import com.uber.edge.presentation.ui.DiagnosticsOverlay
import com.uber.edge.presentation.ui.TripCard
import com.uber.edge.presentation.viewmodel.MapViewModel

class UberDriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Koin DI and local SQLDelight persistence
    }
}

class MainActivity : ComponentActivity() {

    // ViewModel instance (injected via Koin or manual constructor)
    private lateinit var viewModel: MapViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Observe state via StateFlow
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    // MapView host placeholder (GPU MapLibre layer)

                    // Top: Connection Banner
                    Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                        // ConnectionBanner(connection = uiState.connection)
                    }

                    // Bottom: Trip Card & Diagnostics HUD
                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        // TripCard(...)
                    }
                }
            }
        }
    }
}
