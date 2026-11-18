package com.bartixxx.parked.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bartixxx.parked.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Live device heading in degrees (0 = north, 90 = east), adjusted for the current screen
 * rotation. Returns null when the device has no usable orientation sensor.
 */
@Composable
fun rememberDeviceAzimuth(): Float? {
    val context = LocalContext.current
    var azimuth by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(SensorManager::class.java)
            ?: return@DisposableEffect onDispose { }
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var hasGravity = false
        var hasGeomagnetic = false
        var lastPublished = 0L

        fun publishHeading() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPublished < UPDATE_INTERVAL_MS) return
            lastPublished = now
            SensorManager.getOrientation(rotationMatrix, orientation)
            val raw = Math.toDegrees(orientation[0].toDouble()).toFloat()
            azimuth = normalizeDegrees(raw + displayRotationDegrees(context))
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        publishHeading()
                    }

                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, gravity, 0, 3)
                        hasGravity = true
                        if (hasGravity && hasGeomagnetic) {
                            updateFromGravityAndMagnetic(rotationMatrix, gravity, geomagnetic)
                            publishHeading()
                        }
                    }

                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                        hasGeomagnetic = true
                        if (hasGravity && hasGeomagnetic) {
                            updateFromGravityAndMagnetic(rotationMatrix, gravity, geomagnetic)
                            publishHeading()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        when {
            rotationSensor != null ->
                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)

            accelerometer != null && magnetometer != null -> {
                sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
                sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
            }
        }

        onDispose { sensorManager.unregisterListener(listener) }
    }

    return azimuth
}

/** Circular compass dial. The needle points to [bearingDegrees]; the dial rotates with [azimuthDegrees]. */
@Composable
fun TargetCompass(
    azimuthDegrees: Float?,
    bearingDegrees: Float,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val error = MaterialTheme.colorScheme.error
    val heading = azimuthDegrees ?: 0f
    val direction = cardinalDirection(bearingDegrees)
    val roundedBearing = bearingDegrees.roundToInt()
    val description = stringResource(R.string.compass_description, direction, roundedBearing)

    Canvas(
        modifier = modifier.semantics {
            contentDescription = description
        },
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = surfaceVariant.copy(alpha = 0.30f), radius = radius)
        drawCircle(
            color = onSurface.copy(alpha = 0.10f),
            radius = radius,
            style = Stroke(width = 1.dp.toPx()),
        )

        rotate(degrees = -heading, pivot = center) {
            for (tick in 0 until TICK_COUNT) {
                val major = tick % (TICK_COUNT / 4) == 0
                val angle = Math.toRadians(tick * (360.0 / TICK_COUNT))
                val outerRadius = radius * 0.86f
                val length = if (major) radius * 0.15f else radius * 0.07f
                val start = Offset(
                    x = center.x + sin(angle).toFloat() * (outerRadius - length),
                    y = center.y - cos(angle).toFloat() * (outerRadius - length),
                )
                val end = Offset(
                    x = center.x + sin(angle).toFloat() * outerRadius,
                    y = center.y - cos(angle).toFloat() * outerRadius,
                )
                drawLine(
                    color = onSurface.copy(alpha = if (major) 0.55f else 0.22f),
                    start = start,
                    end = end,
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // North marker travels with the dial.
            drawCircle(
                color = error,
                radius = radius * 0.045f,
                center = Offset(center.x, center.y - radius * 0.94f),
            )
        }

        rotate(degrees = bearingDegrees - heading, pivot = center) {
            val needle = Path().apply {
                moveTo(center.x, center.y - radius * 0.62f)
                lineTo(center.x - radius * 0.14f, center.y + radius * 0.18f)
                lineTo(center.x, center.y + radius * 0.07f)
                lineTo(center.x + radius * 0.14f, center.y + radius * 0.18f)
                close()
            }
            drawPath(needle, color = primary)
        }

        drawCircle(color = primary.copy(alpha = 0.18f), radius = radius * 0.12f, center = center)
        drawCircle(color = primary, radius = radius * 0.055f, center = center)
    }
}

fun cardinalDirection(bearingDegrees: Float): String {
    val normalized = normalizeDegrees(bearingDegrees)
    val index = ((normalized + 22.5f) / 45f).toInt() % CARDINALS.size
    return CARDINALS[index]
}

private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

private fun updateFromGravityAndMagnetic(
    rotationMatrix: FloatArray,
    gravity: FloatArray,
    geomagnetic: FloatArray,
) {
    SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
}

@Suppress("DEPRECATION")
private fun displayRotationDegrees(context: Context): Float {
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation
    } else {
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
    }
    return when (rotation) {
        Surface.ROTATION_90 -> -90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> 90f
        else -> 0f
    }
}

private const val TICK_COUNT = 36
private const val UPDATE_INTERVAL_MS = 80L
private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
