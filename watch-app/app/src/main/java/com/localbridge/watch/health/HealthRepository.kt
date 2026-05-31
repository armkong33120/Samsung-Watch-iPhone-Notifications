package com.localbridge.watch.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import com.localbridge.watch.ui.BridgeUiState
import org.json.JSONObject
import kotlin.math.roundToInt

class HealthRepository(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val healthServicesClient: HealthServicesClient = HealthServices.getClient(appContext)
    private var latestHeartRate: Double? = null
    private var latestSteps: Long? = null

    fun start() {
        healthServicesClient.toString()
        sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun currentPayload(): JSONObject = JSONObject()
        .put("heartRateBpm", latestHeartRate ?: JSONObject.NULL)
        .put("steps", latestSteps ?: JSONObject.NULL)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                latestHeartRate = event.values.firstOrNull()?.toDouble()
                BridgeUiState.heartRate = latestHeartRate?.roundToInt()?.toString() ?: "--"
            }
            Sensor.TYPE_STEP_COUNTER -> {
                latestSteps = event.values.firstOrNull()?.toLong()
                BridgeUiState.steps = latestSteps?.toString() ?: "--"
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
