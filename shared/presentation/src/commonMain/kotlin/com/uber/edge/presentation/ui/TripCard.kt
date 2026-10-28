package com.uber.edge.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uber.edge.domain.model.TripStatus
import com.uber.edge.presentation.state.EtaSource
import com.uber.edge.presentation.state.TripUi

@Composable
fun TripCard(
    trip: TripUi?,
    onAction: (TripStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    if (trip == null) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Ready to Drive", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Go online to start receiving ride dispatch offers", fontSize = 14.sp, color = Color.Gray)
            }
        }
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = trip.status.name.replace("_", " "),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF2563EB)
                )

                val etaText = "${trip.etaSec / 60} min"
                val etaBadge = if (trip.etaSource == EtaSource.EDGE) "EDGE ETA" else "ETA"
                Text(
                    text = "$etaBadge: $etaText",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.Black
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Pickup", fontSize = 11.sp, color = Color.Gray)
            Text(trip.pickupAddress, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

            Spacer(modifier = Modifier.height(8.dp))

            Text("Dropoff", fontSize = 11.sp, color = Color.Gray)
            Text(trip.dropoffAddress, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons based on current state
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (trip.status) {
                    TripStatus.ONLINE_IDLE -> {
                        Button(
                            onClick = { onAction(TripStatus.EN_ROUTE_TO_PICKUP) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                        ) {
                            Text("Accept Offer")
                        }
                    }
                    TripStatus.EN_ROUTE_TO_PICKUP -> {
                        Button(
                            onClick = { onAction(TripStatus.ARRIVED_AT_PICKUP) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text("Arrived")
                        }
                    }
                    TripStatus.ARRIVED_AT_PICKUP -> {
                        Button(
                            onClick = { onAction(TripStatus.ON_TRIP) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Text("Start Trip")
                        }
                    }
                    TripStatus.ON_TRIP -> {
                        Button(
                            onClick = { onAction(TripStatus.COMPLETED) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Complete Dropoff")
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
