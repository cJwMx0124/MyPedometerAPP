package com.example.pedometer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null

    // --- UI Elements ---
    private lateinit var tvStepCount: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var etHeight: EditText
    private lateinit var btnSaveHeight: Button

    // --- SharedPreferences for storing height ---
    private lateinit var sharedPreferences: SharedPreferences
    private val PREFS_NAME = "PedometerPrefs"
    private val KEY_HEIGHT = "userHeight"

    // --- 计步和速度计算相关变量 ---
    private var initialStepCount = -1
    private var sessionStepCount = 0
    private var startTime: Long = 0
    // 用户步长（单位：米），现在是动态计算的
    private var userStrideLengthMeters = 0.762f // 默认值

    private val ACTIVITY_RECOGNITION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 初始化 UI
        tvStepCount = findViewById(R.id.tv_step_count)
        tvSpeed = findViewById(R.id.tv_speed)
        etHeight = findViewById(R.id.et_height)
        btnSaveHeight = findViewById(R.id.btn_save_height)

        // 加载保存的身高并更新步长
        loadHeightAndUpdateStrideLength()

        // 设置保存按钮的点击事件
        btnSaveHeight.setOnClickListener {
            val heightStr = etHeight.text.toString()
            if (heightStr.isNotEmpty()) {
                try {
                    val heightCm = heightStr.toFloat()
                    saveHeight(heightCm)
                    updateStrideLength(heightCm)
                    Toast.makeText(this, "身高已保存: ${heightCm}cm", Toast.LENGTH_SHORT).show()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "身高不能为空", Toast.LENGTH_SHORT).show()
            }
        }

        // 检查权限
        checkActivityRecognitionPermission()

        // 初始化传感器
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepCounterSensor == null) {
            Toast.makeText(this, "此设备不支持计步器传感器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadHeightAndUpdateStrideLength() {
        val savedHeight = sharedPreferences.getFloat(KEY_HEIGHT, 0f)
        if (savedHeight > 0) {
            etHeight.setText(savedHeight.toString())
            updateStrideLength(savedHeight)
        }
    }

    private fun saveHeight(heightCm: Float) {
        with(sharedPreferences.edit()) {
            putFloat(KEY_HEIGHT, heightCm)
            apply()
        }
    }

    private fun updateStrideLength(heightCm: Float) {
        // 使用公式：步长 ≈ 身高 * 0.45
        // 将身高从cm转换为米
        userStrideLengthMeters = (heightCm / 100) * 0.45f
    }

    private fun checkActivityRecognitionPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), ACTIVITY_RECOGNITION_REQUEST_CODE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val totalSteps = it.values[0].toInt()

                if (initialStepCount == -1) {
                    initialStepCount = totalSteps
                    startTime = System.currentTimeMillis()
                }

                sessionStepCount = totalSteps - initialStepCount
                tvStepCount.text = sessionStepCount.toString()

                calculateSpeed()
            }
        }
    }

    private fun calculateSpeed() {
        if (sessionStepCount <= 0) return

        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0

        if (elapsedTime > 0) {
            val distance = sessionStepCount * userStrideLengthMeters
            val speed = distance / elapsedTime
            tvSpeed.text = String.format("%.2f", speed)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 可以忽略
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ACTIVITY_RECOGNITION_REQUEST_CODE) {
            if (!((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED))) {
                Toast.makeText(this, "需要活动识别权限才能计步", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
