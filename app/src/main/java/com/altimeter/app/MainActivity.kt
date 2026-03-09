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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.altimeter.app.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "AltimeterApp"
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val GPS_TIMEOUT_MS = 10_000L          // GPS 10 秒无数据则启动网络降级
        private const val NETWORK_FALLBACK_INTERVAL = 3000L  // 网络定位刷新间隔
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
    private var lastLocationSource = ""   // 用于在 UI 标注定位来源

    // ===== 传感器（指南针）=====
    private lateinit var sensorManager: SensorManager
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    // ===== 高德逆地理编码 =====
    private val AMAP_KEY = "337e994b1e8a588f856f05c589f6c51b"
    private var lastGeocodeLat = 0.0
    private var lastGeocodeLng = 0.0
    private var lastGeocodeTime = 0L
    private val GEOCODE_MIN_INTERVAL = 5000L   // 最少 5 秒请求一次
    private val GEOCODE_MIN_DISTANCE = 100.0   // 移动超过 100 米才重新请求

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
                // 仅获得 COARSE 权限 → 直接用网络定位
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
        // GPS 高精度回调
        gpsLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    Log.d(TAG, "GPS fix: lat=${loc.latitude}, lng=${loc.longitude}, acc=${loc.accuracy}")
                    hasReceivedGpsFix = true
                    cancelGpsTimeout()

                    // 如果之前在用网络降级，收到 GPS 信号后切回
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
                    // GPS 不可用且从未获得过 GPS 定位 → 立即启动网络降级
                    Log.w(TAG, "GPS unavailable, starting network fallback immediately")
                    startNetworkFallback()
                }
            }
        }

        // 网络降级（WiFi/基站）回调
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
            // 没有 FINE 权限，尝试 COARSE
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

            // 获取上次已知位置作为初始数据
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

        // 设置 GPS 超时 → 10 秒后若无 GPS fix，启动网络降级
        scheduleGpsTimeout()
    }

    // =======================================================================
    //        Network Fallback (WiFi + Cell Tower via FusedProvider)
    // =======================================================================

    /**
     * 当 GPS 信号不可用时，使用 PRIORITY_BALANCED_POWER_ACCURACY
     * 该模式使用 WiFi 和蜂窝基站进行定位，不依赖 GPS 硬件。
     * 精度通常在 40~100 米左右。
     *
     * 参考: Google 官方文档
     * https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest
     * Priority.PRIORITY_BALANCED_POWER_ACCURACY 使用 WiFi 和基站定位
     */
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

        // 同时尝试原生 LocationManager 的 NETWORK_PROVIDER 作为双保险
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

    /**
     * 对于没有 Google Play Services 的设备（如华为 HMS 设备），
     * 使用 Android 原生 LocationManager 的 NETWORK_PROVIDER（WiFi/基站）
     * 和 GPS_PROVIDER 作为最终兜底方案。
     */
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

        // GPS Provider
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

        // Network Provider (WiFi + Cell)
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
                    // 仅在没有 GPS 数据时使用网络定位
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
    //                         Update UI
    // =======================================================================

    /**
     * 用真实定位数据更新所有 UI
     */
    private fun updateLocationUI(location: Location) {
        // 海拔
        if (location.hasAltitude()) {
            binding.gaugeView.altitude = location.altitude.toInt()
        }

        // 速度
        if (location.hasSpeed()) {
            binding.gaugeView.speed = (location.speed * 3.6).toInt()
        } else {
            binding.gaugeView.speed = 0
        }

        // 海拔精度 + 定位来源
        if (location.hasAccuracy()) {
            val verticalAccuracy = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters.toInt()
            } else {
                location.accuracy.toInt()
            }
            val sourceLabel = if (lastLocationSource.isNotEmpty()) " [$lastLocationSource]" else ""
            binding.tvAccuracy.text = "海拔精度:${verticalAccuracy}米$sourceLabel"
        } else {
            val sourceLabel = if (lastLocationSource.isNotEmpty()) " [$lastLocationSource]" else ""
            binding.tvAccuracy.text = "海拔精度:--$sourceLabel"
        }

        // 经纬度
        val lat = location.latitude
        val lng = location.longitude
        val latDir = if (lat >= 0) "北纬" else "南纬"
        val lngDir = if (lng >= 0) "东经" else "西经"
        binding.tvLatitude.text = "$latDir ${toDMS(lat)}"
        binding.tvLongitude.text = "$lngDir ${toDMS(lng)}"

        // 逆地理编码（带节流）
        requestReverseGeocode(lat, lng)
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

    // =======================================================================
    //                      高德逆地理编码
    // =======================================================================

    /**
     * 带节流的逆地理请求：距离 >100m 或时间 >5s 才发起新请求
     */
    private fun requestReverseGeocode(lat: Double, lng: Double) {
        val now = System.currentTimeMillis()
        val distance = distanceBetween(lat, lng, lastGeocodeLat, lastGeocodeLng)

        if (now - lastGeocodeTime < GEOCODE_MIN_INTERVAL && distance < GEOCODE_MIN_DISTANCE) {
            return  // 节流：没走远也没超时，跳过
        }

        lastGeocodeLat = lat
        lastGeocodeLng = lng
        lastGeocodeTime = now

        // 子线程请求网络
        Thread {
            try {
                // 高德 API 经纬度格式：lng,lat（经度在前）
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

                        // city 可能是 [] (空数组) 表示直辖市
                        val cityStr = if (city.isEmpty() || city == "[]") "" else city
                        val displayText = buildString {
                            if (province.isNotEmpty() && province != "[]") append(province)
                            if (cityStr.isNotEmpty() && cityStr != province) append(" $cityStr")
                            if (district.isNotEmpty() && district != "[]") append(" $district")
                            if (township.isNotEmpty() && township != "[]") append(" $township")
                        }.trim()

                        // 回主线程更新 UI
                        mainHandler.post {
                            binding.tvLocation.text = if (displayText.isNotEmpty()) displayText else "未知区域"
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocode failed", e)
                // 网络失败不影响其他功能
            }
        }.start()
    }

    /**
     * 简易距离计算（米），用于节流判断
     */
    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble()
    }

    // =======================================================================
    //                           指南针
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
