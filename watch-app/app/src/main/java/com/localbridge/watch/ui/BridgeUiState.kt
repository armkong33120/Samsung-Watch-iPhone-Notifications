package com.localbridge.watch.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object BridgeUiState {
    var connectionLabel by mutableStateOf("Starting")
    var latestEvent by mutableStateOf("No events yet")
    var lastAction by mutableStateOf("No action sent")
    var heartRate by mutableStateOf("--")
    var steps by mutableStateOf("--")
}
