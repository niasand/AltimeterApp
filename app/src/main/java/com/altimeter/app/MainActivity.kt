package com.altimeter.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.altimeter.app.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding

    // 位置
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var locationCallbackRegistered = false

    // 传感器（指南针）
    private lateinit var sensorManager: SensorManager
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 — 所有数据显示为等待状态
        binding.gaugeView.altitude = null
        binding.gaugeView.speed = null
        binding.tvAccuracy.text = "海拔精度:--"
        binding.tvLatitude.text = "北纬 --°--′--″"
        binding.tvLongitude.text = "东经 --°--′--″"

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        setupLocationCallback()
        checkPermissions()
    }

    // =========== 权限 ===========
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            // 权限被拒绝，提示用户
            binding.tvAccuracy.text = "需要位置权限才能获取海拔"
        }
    }

    // =========== 位置 ===========
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // 使用最新的一条位置
                result.lastLocation?.let { updateLocationUI(it) }
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            locationCallbackRegistered = true

            // 同时尝试获取 last known location 作为初始值
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    updateLocationUI(location)
                }
            }
        }
    }

    /**
     * 核心：用真实 GPS 数据更新所有 UI 元素
     */
    private fun updateLocationUI(location: Location) {
        // —— 海拔 ——
        if (location.hasAltitude()) {
            binding.gaugeView.altitude = location.altitude.toInt()
        }

        // —— 速度 ——
        if (location.hasSpeed()) {
            binding.gaugeView.speed = (location.speed * 3.6).toInt()  // m/s → km/h
        } else {
            binding.gaugeView.speed = 0
        }

        // —— 海拔精度 ——
        if (location.hasAccuracy()) {
            val verticalAccuracy = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters.toInt()
            } else {
                location.accuracy.toInt()
            }
            binding.tvAccuracy.text = "海拔精度:${verticalAccuracy}米"
        } else {
            binding.tvAccuracy.text = "海拔精度:--"
        }

        // —— 经纬度 ——
        val lat = location.latitude
        val lng = location.longitude
        val latDir = if (lat >= 0) "北纬" else "南纬"
        val lngDir = if (lng >= 0) "东经" else "西经"
        binding.tvLatitude.text = "$latDir ${toDMS(lat)}"
        binding.tvLongitude.text = "$lngDir ${toDMS(lng)}"
    }

    /**
     * 十进制度 → 度°分′秒″
     */
    private fun toDMS(decimal: Double): String {
        val absVal = abs(decimal)
        val deg = absVal.toInt()
        val minPart = (absVal - deg) * 60
        val min = minPart.toInt()
        val sec = ((minPart - min) * 60).toInt()
        return String.format(Locale.US, "%d°%d′%d″", deg, min, sec)
    }

    // =========== 指南针 ===========
    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // 恢复定位
        if (!locationCallbackRegistered) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (locationCallbackRegistered) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationCallbackRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                hasAccel = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
                hasMag = true
            }
        }
        if (hasAccel && hasMag) {
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                binding.gaugeView.compassDegree = azimuthDeg
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
