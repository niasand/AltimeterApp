package com.altimeter.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.altimeter.app.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "AltimeterApp"
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val GPS_TIMEOUT_MS = 10_000L
        private const val NETWORK_FALLBACK_INTERVAL = 3000L
    }

    private lateinit var binding: ActivityMainBinding

    // ===== FusedLocationProvider (Google Play Services) =====
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var gpsLocationCallback: LocationCallback
    private lateinit var networkLocationCallback: LocationCallback
    private var gpsCallbackRegistered = false
    private var networkCallbackRegistered = false

    // ===== 原生 LocationManager (fallback for non-GMS devices) =====
    private var nativeLocationManager: LocationManager? = null
    private var nativeNetworkListener: LocationListener? = null
    private var nativeGpsListener: LocationListener? = null
    private var nativeListenerRegistered = false

    // ===== 定位状态 =====
    private var hasReceivedGpsFix = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gpsTimeoutRunnable: Runnable? = null
    private var isUsingNetworkFallback = false
    private var lastLocationSource = ""

    // ===== 传感器（指南针 + 气压计）=====
    private lateinit var sensorManager: SensorManager
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    // ===== 气压计海拔 =====
    private var pressureSensor: Sensor? = null
    private var hasBarometer = false
    private var currentPressureHpa: Float = 0f          // 当前气压（hPa）
    private var hasPressureReading = false
    private var calibratedSeaLevelPressure: Float = 0f  // 校准后的海平面气压
    private var isBarometerCalibrated = false            // 是否已用 GPS 海拔校准过
    private var lastBaroAltitude: Int? = null            // 最新的气压计海拔
    private var altitudeSource = ""                      // "barometer" / "gps"

    // ===== 高德逆地理编码 =====
    private val AMAP_KEY = "337e994b1e8a588f856f05c589f6c51b"
    private var lastGeocodeLat = 0.0
    private var lastGeocodeLng = 0.0
    private var lastGeocodeTime = 0L
    private val GEOCODE_MIN_INTERVAL = 5000L
    private val GEOCODE_MIN_DISTANCE = 100.0

    // =======================================================================
    //                            Lifecycle
    // =======================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸 — 使用 WindowCompat API (替代已废弃的 systemUiVisibility)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 — 等待状态
        binding.gaugeView.altitude = null
        binding.gaugeView.speed = null
        binding.tvAccuracy.text = "海拔精度:--"
        binding.tvLatitude.text = "北纬 --°--′--″"
        binding.tvLongitude.text = "东经 --°--′--″"
        binding.tvLocation.text = "定位中..."

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        nativeLocationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // 检测气压传感器
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBarometer = pressureSensor != null
        if (hasBarometer) {
            Log.i(TAG, "Barometer sensor detected: ${pressureSensor!!.name}")
        } else {
            Log.i(TAG, "No barometer sensor available, using GPS altitude only")
        }

        setupLocationCallbacks()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 指南针传感器
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // 气压传感器
        if (hasBarometer) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI)
        }
        // 恢复定位
        if (!gpsCallbackRegistered) {
            startGpsLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopAllLocationUpdates()
    }

    // =======================================================================
    //                            Permissions
    // =======================================================================

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
            startGpsLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startGpsLocationUpdates()
            } else if (grantResults.size > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Only COARSE location granted, using network fallback")
                startNetworkFallback()
            } else {
                binding.tvAccuracy.text = "需要位置权限才能获取海拔"
                binding.tvLocation.text = "未授予位置权限"
            }
        }
    }

    // =======================================================================
    //                     Location Callbacks Setup
    // =======================================================================

    private fun setupLocationCallbacks() {
        gpsLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    Log.d(TAG, "GPS fix: lat=${loc.latitude}, lng=${loc.longitude}, acc=${loc.accuracy}")
                    hasReceivedGpsFix = true
                    cancelGpsTimeout()

                    if (isUsingNetworkFallback) {
                        Log.i(TAG, "GPS signal recovered, stopping network fallback")
                        stopNetworkFallback()
                        isUsingNetworkFallback = false
                    }

                    lastLocationSource = "GPS"
                    updateLocationUI(loc)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "GPS availability: ${availability.isLocationAvailable}")
                if (!availability.isLocationAvailable && !hasReceivedGpsFix) {
                    Log.w(TAG, "GPS unavailable, starting network fallback immediately")
                    startNetworkFallback()
                }
            }
        }

        networkLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    Log.d(TAG, "Network fix: lat=${loc.latitude}, lng=${loc.longitude}, acc=${loc.accuracy}")
                    lastLocationSource = "WiFi/基站"
                    updateLocationUI(loc)
                }
            }
        }
    }

    // =======================================================================
    //              GPS Location (PRIORITY_HIGH_ACCURACY)
    // =======================================================================

    private fun startGpsLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startNetworkFallback()
            }
            return
        }

        val gpsRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(gpsRequest, gpsLocationCallback, Looper.getMainLooper())
            gpsCallbackRegistered = true
            Log.i(TAG, "GPS location updates started (HIGH_ACCURACY)")

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && !hasReceivedGpsFix) {
                    lastLocationSource = "缓存"
                    updateLocationUI(location)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "FusedLocationProvider failed, falling back to native LocationManager", e)
            startNativeLocationFallback()
        }

        scheduleGpsTimeout()
    }

    // =======================================================================
    //        Network Fallback (WiFi + Cell Tower via FusedProvider)
    // =======================================================================

    private fun startNetworkFallback() {
        if (networkCallbackRegistered) return
        isUsingNetworkFallback = true

        Log.i(TAG, "Starting network fallback (BALANCED_POWER_ACCURACY - WiFi/Cell)")
        binding.tvAccuracy.text = "GPS信号弱，使用WiFi/基站定位..."

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val networkRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, NETWORK_FALLBACK_INTERVAL
        )
            .setMinUpdateIntervalMillis(2000L)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                networkRequest, networkLocationCallback, Looper.getMainLooper()
            )
            networkCallbackRegistered = true
            Log.i(TAG, "Network fallback location updates started")
        } catch (e: Exception) {
            Log.e(TAG, "FusedLocationProvider network fallback failed, trying native", e)
            startNativeLocationFallback()
        }

        startNativeNetworkProvider()
    }

    private fun stopNetworkFallback() {
        if (networkCallbackRegistered) {
            fusedLocationClient.removeLocationUpdates(networkLocationCallback)
            networkCallbackRegistered = false
            Log.i(TAG, "Network fallback stopped")
        }
        stopNativeNetworkProvider()
    }

    // =======================================================================
    //      Native LocationManager Fallback (for non-GMS devices)
    // =======================================================================

    private fun startNativeLocationFallback() {
        val lm = nativeLocationManager ?: return
        if (nativeListenerRegistered) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        Log.i(TAG, "Starting native LocationManager fallback")

        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            nativeGpsListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(TAG, "Native GPS fix: ${location.latitude}, ${location.longitude}")
                    hasReceivedGpsFix = true
                    lastLocationSource = "GPS(native)"
                    updateLocationUI(location)
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "Native GPS provider disabled")
                }
                @Deprecated("Deprecated in API")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f,
                    nativeGpsListener!!, Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Native GPS provider request failed", e)
            }
        }

        startNativeNetworkProvider()
        nativeListenerRegistered = true
    }

    private fun startNativeNetworkProvider() {
        val lm = nativeLocationManager ?: return
        if (nativeNetworkListener != null) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            nativeNetworkListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!hasReceivedGpsFix || isUsingNetworkFallback) {
                        Log.d(TAG, "Native Network fix: ${location.latitude}, ${location.longitude}")
                        lastLocationSource = "WiFi/基站(native)"
                        updateLocationUI(location)
                    }
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in API")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, NETWORK_FALLBACK_INTERVAL, 0f,
                    nativeNetworkListener!!, Looper.getMainLooper()
                )
                Log.i(TAG, "Native NETWORK_PROVIDER started")
            } catch (e: Exception) {
                Log.e(TAG, "Native network provider request failed", e)
            }
        } else {
            Log.w(TAG, "NETWORK_PROVIDER not available on this device")
        }
    }

    private fun stopNativeNetworkProvider() {
        val lm = nativeLocationManager ?: return
        nativeNetworkListener?.let {
            lm.removeUpdates(it)
            nativeNetworkListener = null
            Log.i(TAG, "Native NETWORK_PROVIDER stopped")
        }
    }

    private fun stopNativeLocationFallback() {
        val lm = nativeLocationManager ?: return
        nativeGpsListener?.let {
            lm.removeUpdates(it)
            nativeGpsListener = null
        }
        stopNativeNetworkProvider()
        nativeListenerRegistered = false
    }

    // =======================================================================
    //                    GPS Timeout Scheduling
    // =======================================================================

    private fun scheduleGpsTimeout() {
        cancelGpsTimeout()
        gpsTimeoutRunnable = Runnable {
            if (!hasReceivedGpsFix) {
                Log.w(TAG, "GPS timeout after ${GPS_TIMEOUT_MS}ms, activating network fallback")
                startNetworkFallback()
            }
        }
        mainHandler.postDelayed(gpsTimeoutRunnable!!, GPS_TIMEOUT_MS)
    }

    private fun cancelGpsTimeout() {
        gpsTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        gpsTimeoutRunnable = null
    }

    // =======================================================================
    //                    Stop All Location Updates
    // =======================================================================

    private fun stopAllLocationUpdates() {
        cancelGpsTimeout()
        if (gpsCallbackRegistered) {
            fusedLocationClient.removeLocationUpdates(gpsLocationCallback)
            gpsCallbackRegistered = false
        }
        stopNetworkFallback()
        stopNativeLocationFallback()
    }

    // =======================================================================
    //                    气压计海拔计算
    // =======================================================================

    /**
     * 使用气压传感器计算海拔。
     *
     * 原理：基于气压高度公式 (hypsometric formula)
     *   altitude = 44330 * (1 - (p / p0) ^ (1/5.255))
     *
     * Android 官方提供 SensorManager.getAltitude(p0, p) 方法，
     * 其中 p0 = 海平面气压 (hPa), p = 当前气压 (hPa)。
     *
     * 校准策略：
     * 1. 未校准时：使用标准大气压 1013.25 hPa 作为 p0 —— 绝对海拔有偏差，但变化趋势准确
     * 2. GPS 校准后：根据首次 GPS 海拔反推当地真实 p0，后续用校准值 —— 精度可达 ±1m
     *
     * 参考: Android SensorManager.getAltitude 官方文档
     * https://developer.android.com/reference/android/hardware/SensorManager#getAltitude(float,%20float)
     */
    private fun computeBarometricAltitude(): Int? {
        if (!hasPressureReading || currentPressureHpa <= 0f) return null

        val seaLevelPressure = if (isBarometerCalibrated) {
            calibratedSeaLevelPressure
        } else {
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE
        }

        val altitude = SensorManager.getAltitude(seaLevelPressure, currentPressureHpa)
        return altitude.toInt()
    }

    /**
     * 用 GPS 海拔校准气压计的海平面气压。
     *
     * 反推公式（由 hypsometric formula 反解 p0）：
     *   p0 = p / (1 - altitude / 44330) ^ 5.255
     *
     * 仅在以下条件满足时执行校准：
     * - 有气压传感器
     * - GPS 提供了有效海拔
     * - GPS 精度 < 20m（确保 GPS 海拔质量）
     * - 尚未校准过（只校准一次，避免 GPS 波动干扰）
     */
    private fun calibrateBarometerWithGps(gpsAltitude: Double, gpsAccuracy: Float) {
        if (!hasBarometer || !hasPressureReading) return
        if (isBarometerCalibrated) return
        if (gpsAccuracy > 20f) return  // GPS 精度太差，不适合校准

        // 反推海平面气压: p0 = p / (1 - h/44330)^5.255
        val ratio = 1.0 - gpsAltitude / 44330.0
        if (ratio <= 0) return  // 海拔超出合理范围

        val p0 = currentPressureHpa / ratio.pow(5.255).toFloat()

        // 合理性检查：海平面气压通常在 870~1084 hPa 之间
        if (p0 in 870f..1084f) {
            calibratedSeaLevelPressure = p0
            isBarometerCalibrated = true
            Log.i(TAG, "Barometer calibrated with GPS altitude %.1fm → sea level pressure %.2f hPa".format(gpsAltitude, p0))
        } else {
            Log.w(TAG, "Barometer calibration rejected: computed p0=%.2f hPa out of range".format(p0))
        }
    }

    // =======================================================================
    //                         Update UI
    // =======================================================================

    private fun updateLocationUI(location: Location) {
        // 用 GPS 海拔校准气压计（首次）
        if (location.hasAltitude() && location.hasAccuracy()) {
            calibrateBarometerWithGps(location.altitude, location.accuracy)
        }

        // 海拔：优先使用气压计（精度更高、刷新更快）
        val baroAlt = computeBarometricAltitude()
        if (baroAlt != null && hasBarometer) {
            binding.gaugeView.altitude = baroAlt
            lastBaroAltitude = baroAlt
            altitudeSource = "气压计"
        } else if (location.hasAltitude()) {
            binding.gaugeView.altitude = location.altitude.toInt()
            altitudeSource = "GPS"
        }

        // 速度
        if (location.hasSpeed()) {
            binding.gaugeView.speed = (location.speed * 3.6).toInt()
        } else {
            binding.gaugeView.speed = 0
        }

        // 海拔精度 + 来源
        val accuracyText = buildString {
            append("海拔精度:")
            if (altitudeSource == "气压计") {
                // 气压计海拔精度约 ±1m（校准后）或 ±10m（未校准）
                val baroAccuracy = if (isBarometerCalibrated) "±1" else "±10"
                append("${baroAccuracy}米")
            } else if (location.hasAccuracy()) {
                val verticalAccuracy = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
                    location.verticalAccuracyMeters.toInt()
                } else {
                    location.accuracy.toInt()
                }
                append("${verticalAccuracy}米")
            } else {
                append("--")
            }
            append(" [${lastLocationSource}")
            if (altitudeSource.isNotEmpty()) append("+${altitudeSource}")
            append("]")
        }
        binding.tvAccuracy.text = accuracyText

        // 经纬度
        val lat = location.latitude
        val lng = location.longitude
        val latDir = if (lat >= 0) "北纬" else "南纬"
        val lngDir = if (lng >= 0) "东经" else "西经"
        binding.tvLatitude.text = "$latDir ${toDMS(lat)}"
        binding.tvLongitude.text = "$lngDir ${toDMS(lng)}"

        // 逆地理编码
        requestReverseGeocode(lat, lng)
    }

    /**
     * 气压传感器数据变化时，独立更新海拔 UI（不依赖 GPS 回调频率）
     * 气压传感器通常每秒可更新数十次，但我们只需 UI 刷新级别
     */
    private fun updateBaroAltitudeOnly() {
        val baroAlt = computeBarometricAltitude() ?: return
        lastBaroAltitude = baroAlt
        binding.gaugeView.altitude = baroAlt
        altitudeSource = "气压计"
    }

    private fun toDMS(decimal: Double): String {
        val absVal = abs(decimal)
        val deg = absVal.toInt()
        val minPart = (absVal - deg) * 60
        val min = minPart.toInt()
        val sec = ((minPart - min) * 60).toInt()
        return String.format(Locale.US, "%d°%d′%d″", deg, min, sec)
    }

    // =======================================================================
    //                      高德逆地理编码
    // =======================================================================

    private fun requestReverseGeocode(lat: Double, lng: Double) {
        val now = System.currentTimeMillis()
        val distance = distanceBetween(lat, lng, lastGeocodeLat, lastGeocodeLng)

        if (now - lastGeocodeTime < GEOCODE_MIN_INTERVAL && distance < GEOCODE_MIN_DISTANCE) {
            return
        }

        lastGeocodeLat = lat
        lastGeocodeLng = lng
        lastGeocodeTime = now

        Thread {
            try {
                val location = String.format(Locale.US, "%.6f,%.6f", lng, lat)
                val urlStr = "https://restapi.amap.com/v3/geocode/regeo?" +
                    "key=$AMAP_KEY&location=$location&extensions=base&output=JSON"

                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    val response = reader.readText()
                    reader.close()

                    val json = JSONObject(response)
                    if (json.optString("status") == "1") {
                        val regeocode = json.getJSONObject("regeocode")
                        val addressComponent = regeocode.getJSONObject("addressComponent")

                        val province = addressComponent.optString("province", "")
                        val city = addressComponent.optString("city", "")
                        val district = addressComponent.optString("district", "")
                        val township = addressComponent.optString("township", "")

                        val cityStr = if (city.isEmpty() || city == "[]") "" else city
                        val displayText = buildString {
                            if (province.isNotEmpty() && province != "[]") append(province)
                            if (cityStr.isNotEmpty() && cityStr != province) append(" $cityStr")
                            if (district.isNotEmpty() && district != "[]") append(" $district")
                            if (township.isNotEmpty() && township != "[]") append(" $township")
                        }.trim()

                        mainHandler.post {
                            binding.tvLocation.text = if (displayText.isNotEmpty()) displayText else "未知区域"
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocode failed", e)
            }
        }.start()
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble()
    }

    // =======================================================================
    //                           传感器回调
    // =======================================================================

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
            Sensor.TYPE_PRESSURE -> {
                // 气压传感器：values[0] = 大气压 (hPa / mbar)
                currentPressureHpa = event.values[0]
                hasPressureReading = true
                // 独立更新气压海拔（不需要等 GPS 回调）
                updateBaroAltitudeOnly()
            }
        }
        // 指南针计算
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
