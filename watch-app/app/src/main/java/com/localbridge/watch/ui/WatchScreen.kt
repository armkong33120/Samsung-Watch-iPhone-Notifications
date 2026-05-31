package com.localbridge.watch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.localbridge.watch.notifications.WatchNotificationRenderer

@Composable
fun WatchScreen() {
    val context = LocalContext.current
    val renderer = WatchNotificationRenderer(context)

    MaterialTheme {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    BridgeUiState.latestEvent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.body2
                )
            }

            item {
                Button(
                    onClick = {
                        val event = NotificationEvent(
                            title = "Local Test",
                            body = "Screen wake & Sound test ${System.currentTimeMillis() % 1000}",
                            source = "Watch App"
                        )
                        renderer.post(event, "test_${System.currentTimeMillis()}")
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Text("Test Alert", style = MaterialTheme.typography.button)
                }
            }

            item {
                Text(
                    "Status: ${BridgeUiState.connectionLabel}",
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
