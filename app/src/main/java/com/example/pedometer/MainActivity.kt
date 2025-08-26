package com.example.pedometer

import android.animation.ValueAnimator
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
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // --- UI Elements ---
    private lateinit var tvSpeed: TextView
    private lateinit var etHeight: EditText
    private lateinit var btnSaveHeight: Button
    private lateinit var etSpeedLimit: EditText
    private lateinit var btnSaveSpeedLimit: Button

    // --- SharedPreferences for storing settings ---
    private lateinit var sharedPreferences: SharedPreferences
    private val PREFS_NAME = "PedometerPrefs"
    private val KEY_HEIGHT = "userHeight"
    private val KEY_SPEED_LIMIT = "speedLimit"

    // --- 速度计算相关变量 ---
    private var userStrideLengthMeters = 0.762f // 默认值
    private var speedLimit = 0f // 速度上限，0表示不限制
    private var currentDisplayedSpeed = 0f // 当前UI显示的速度，用于动画

    // --- 停止检测计时器 ---
    private val stopHandler = Handler(Looper.getMainLooper())
    private lateinit var stopRunnable: Runnable

    // --- 加速度计步算法相关变量 ---
    private var lastStepTimeNs: Long = 0
    private val stepThreshold = 1.8f // 步数检测阈值，可能需要微调
    private var highPassFilter = floatArrayOf(0f, 0f, 0f)
    private var lastMagnitude: Float = 0f
    private var peakDetected = false

    private val ACTIVITY_RECOGNITION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 初始化UI
        tvSpeed = findViewById(R.id.tv_speed)
        etHeight = findViewById(R.id.et_height)
        btnSaveHeight = findViewById(R.id.btn_save_height)
        etSpeedLimit = findViewById(R.id.et_speed_limit)
        btnSaveSpeedLimit = findViewById(R.id.btn_save_speed_limit)

        loadHeightAndUpdateStrideLength()
        loadSpeedLimit()

        stopRunnable = Runnable { animateSpeedUpdate(0f) }

        btnSaveHeight.setOnClickListener { setupSaveHeightButton() }
        btnSaveSpeedLimit.setOnClickListener { setupSaveSpeedLimitButton() }

        checkActivityRecognitionPermission()

        // 初始化传感器
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            Toast.makeText(this, "此设备不支持加速度传感器", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupSaveHeightButton() {
        val heightStr = etHeight.text.toString()
        if (heightStr.isNotEmpty()) {
            try {
                val heightCm = heightStr.toFloat()
                saveHeight(heightCm)
                updateStrideLength(heightCm)
                Toast.makeText(this, "身高已保存: ${heightCm}cm", Toast.LENGTH_SHORT).show()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "请输入有效的身高数字", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "身高不能为空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSaveSpeedLimitButton() {
        val limitStr = etSpeedLimit.text.toString()
        if (limitStr.isNotEmpty()) {
            try {
                val limit = limitStr.toFloat()
                saveSpeedLimit(limit)
                Toast.makeText(this, "速度上限已保存: ${limit}m/s", Toast.LENGTH_SHORT).show()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "请输入有效的速度数字", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "速度上限不能为空", Toast.LENGTH_SHORT).show()
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
        userStrideLengthMeters = (heightCm / 100) * 0.45f
    }

    private fun loadSpeedLimit() {
        speedLimit = sharedPreferences.getFloat(KEY_SPEED_LIMIT, 0f)
        if (speedLimit > 0) {
            etSpeedLimit.setText(speedLimit.toString())
        }
    }

    private fun saveSpeedLimit(limit: Float) {
        speedLimit = limit
        with(sharedPreferences.edit()) {
            putFloat(KEY_SPEED_LIMIT, limit)
            apply()
        }
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
        accelerometer?.let {
            // 使用游戏延迟以获得更快的更新
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopHandler.removeCallbacks(stopRunnable)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                // 1. 移除重力影响：使用一个简单的高通滤波器
                val alpha = 0.8f
                highPassFilter[0] = alpha * highPassFilter[0] + (1 - alpha) * it.values[0]
                highPassFilter[1] = alpha * highPassFilter[1] + (1 - alpha) * it.values[1]
                highPassFilter[2] = alpha * highPassFilter[2] + (1 - alpha) * it.values[2]

                val linearAcceleration = floatArrayOf(
                    it.values[0] - highPassFilter[0],
                    it.values[1] - highPassFilter[1],
                    it.values[2] - highPassFilter[2]
                )

                // 2. 计算加速度向量的模（大小）
                val magnitude = sqrt(linearAcceleration[0] * linearAcceleration[0] + linearAcceleration[1] * linearAcceleration[1] + linearAcceleration[2] * linearAcceleration[2])

                // 3. 简单的峰值检测算法来识别步伐
                if (magnitude > stepThreshold && !peakDetected) {
                    peakDetected = true
                }
                if (magnitude < lastMagnitude && peakDetected) {
                    peakDetected = false
                    // 检测到一步！
                    val currentTimeNs = it.timestamp
                    if (lastStepTimeNs != 0L) {
                        val timeDeltaNs = currentTimeNs - lastStepTimeNs
                        val timeDeltaS = timeDeltaNs / 1_000_000_000.0f

                        // 避免因时间差过小导致速度异常大
                        if (timeDeltaS > 0.2) {
                            val speed = userStrideLengthMeters / timeDeltaS

                            // 速度告警检查
                            if (speedLimit > 0 && speed > speedLimit) {
                                Toast.makeText(this, "警告: 速度已超过上限 ${speedLimit}m/s", Toast.LENGTH_SHORT).show()
                            }

                            // 更新UI并重置停止计时器
                            animateSpeedUpdate(speed)
                            stopHandler.removeCallbacks(stopRunnable)
                            stopHandler.postDelayed(stopRunnable, 2000L) // 2秒无新步数则认为停止
                        }
                    }
                    lastStepTimeNs = currentTimeNs
                }
                lastMagnitude = magnitude
            }
        }
    }

    private fun animateSpeedUpdate(newSpeed: Float) {
        val animator = ValueAnimator.ofFloat(currentDisplayedSpeed, newSpeed)
        animator.duration = 500 // 动画持续时间缩短为0.5秒，以获得更灵敏的反馈
        animator.addUpdateListener {
            val animatedValue = it.animatedValue as Float
            currentDisplayedSpeed = animatedValue
            tvSpeed.text = String.format("%.2f", animatedValue)
        }
        animator.start()
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
