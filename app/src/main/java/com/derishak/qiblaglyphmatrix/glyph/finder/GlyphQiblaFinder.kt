package com.derishak.qiblaglyphmatrix.glyph.finder

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject
import com.nothing.ketchum.GlyphMatrixUtils
import com.derishak.qiblaglyphmatrix.R
import com.derishak.qiblaglyphmatrix.glyph.GlyphMatrixService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GlyphQiblaFinder : GlyphMatrixService("Glyph-Qibla-Finder") {
    private lateinit var bgScope: CoroutineScope
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private val accelReading = FloatArray(3)
    private val magnetReading = FloatArray(3)
    private var currentAzimuth: Float = 0f
    private var userLat: Double = 0.0
    private var userLon: Double = 0.0
    private var hasLocation = false


    val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelReading, 0, event.values.size)
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magnetReading, 0, event.values.size)
            }

            val rotationMatrix = FloatArray(9)
            val orientationAngles = FloatArray(3)

            if (SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    accelReading,
                    magnetReading
                )
            ) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                currentAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                currentAzimuth = (currentAzimuth + 360) % 360
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    @OptIn(FlowPreview::class)
    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.registerListener(
            sensorListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI
        )
        sensorManager.registerListener(
            sensorListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
            SensorManager.SENSOR_DELAY_UI
        )

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            500L,
            0.1f,
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    userLat = location.latitude
                    Log.d("QiblaFinder", "Latitude: $userLat")
                    userLon = location.longitude
                    Log.d("QiblaFinder", "Latitude: $userLon")
                    hasLocation = true
                }

                override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {}
                override fun onProviderEnabled(p0: String) {}
                override fun onProviderDisabled(p0: String) {}
            }
        )

        glyphMatrixManager.apply {
            val textObject = GlyphMatrixObject.Builder()
                .setText("QIBLA")
                .setPosition(1, 9)
                .build()

            val frame = GlyphMatrixFrame.Builder()
                .addTop(textObject)
                .build(applicationContext)

            setMatrixFrame(frame.render())
        }

        bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    }

    override fun performOnServiceDisconnected(context: Context) {
        sensorManager.unregisterListener(sensorListener)
        locationManager.removeUpdates {}
        super.performOnServiceDisconnected(context)
        bgScope.cancel()
    }

    override fun onTouchPointPressed() {
    }

    override fun onTouchPointReleased() {
        bgScope.launch {
            while (true) {
                delay(100L)
                if (hasLocation) drawArrow()
                else drawLocationMark()
            }
        }
    }

    fun drawArrow() {
        val qiblaAngle = getQiblaDirection(userLat, userLon)
        val rotationNeeded = (qiblaAngle - currentAzimuth + 360) % 360

        val iconObject = GlyphMatrixObject.Builder()
            .setImageSource(
                GlyphMatrixUtils.drawableToBitmap(
                    ContextCompat.getDrawable(
                        this as Context,
                        R.drawable.outline_arrow_upward_alt_24
                    )
                )
            )
            .setScale(100)
            .setOrientation(rotationNeeded.toInt())
            .setPosition(0, 0)
            .setReverse(false)
            .build()

        val frame = GlyphMatrixFrame.Builder()
            .addTop(iconObject)
            .build(applicationContext)

        glyphMatrixManager?.setMatrixFrame(frame.render())
    }

    fun drawLocationMark() {
        val iconObject = GlyphMatrixObject.Builder()
            .setImageSource(
                GlyphMatrixUtils.drawableToBitmap(
                    ContextCompat.getDrawable(
                        this as Context,
                        R.drawable.location_pin_svgrepo_com
                    )
                )
            )
            .setScale(80)
            .setOrientation(0)
            .setPosition(3, 3)
            .setReverse(false)
            .build()

        val frame = GlyphMatrixFrame.Builder()
            .addTop(iconObject)
            .build(applicationContext)

        glyphMatrixManager?.setMatrixFrame(frame.render())
    }

    fun getQiblaDirection(lat: Double, lon: Double): Double {
        val kaabaLat = Math.toRadians(21.422487)
        val kaabaLon = Math.toRadians(39.826206)

        val userLat = Math.toRadians(lat)
        val userLon = Math.toRadians(lon)

        val deltaLon = kaabaLon - userLon

        val y = sin(deltaLon) * cos(kaabaLat)
        val x = cos(userLat) * sin(kaabaLat) -
                sin(userLat) * cos(kaabaLat) * cos(deltaLon)

        val bearing = atan2(y, x)
        return Math.toDegrees(bearing).let { (it + 360) % 360 } // 0–360°
    }

}