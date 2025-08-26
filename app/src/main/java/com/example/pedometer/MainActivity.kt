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

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepCounterSensor: Sensor? = null

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

    // --- 计步和速度计算相关变量 ---
    private var initialStepCount = -1
    private var sessionStepCount = 0
    private var startTime: Long = 0
    private var userStrideLengthMeters = 0.762f // 默认值
    private var speedLimit = 0f // 速度上限，0表示不限制
    private var currentDisplayedSpeed = 0f // 当前UI显示的速度，用于动画

    // --- 停止检测计时器 ---
    private val stopHandler = Handler(Looper.getMainLooper())
    private lateinit var stopRunnable: Runnable

    private val ACTIVITY_RECOGNITION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 初始化 UI
        tvSpeed = findViewById(R.id.tv_speed)
        etHeight = findViewById(R.id.et_height)
        btnSaveHeight = findViewById(R.id.btn_save_height)
        etSpeedLimit = findViewById(R.id.et_speed_limit)
        btnSaveSpeedLimit = findViewById(R.id.btn_save_speed_limit)

        // 加载已保存的设置
        loadHeightAndUpdateStrideLength()
        loadSpeedLimit()

        // 初始化停止检测的Runnable
        stopRunnable = Runnable { animateSpeedUpdate(0f) }

        // 设置身高保存按钮的点击事件
        btnSaveHeight.setOnClickListener {
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

        // 设置速度上限保存按钮的点击事件
        btnSaveSpeedLimit.setOnClickListener {
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
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        // 移除回调，防止应用在后台时执行
        stopHandler.removeCallbacks(stopRunnable)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                // 每次检测到步数，都先移除旧的“停止”回调
                stopHandler.removeCallbacks(stopRunnable)

                val totalSteps = it.values[0].toInt()

                if (initialStepCount == -1) {
                    initialStepCount = totalSteps
                    startTime = System.currentTimeMillis()
                }

                sessionStepCount = totalSteps - initialStepCount
                calculateSpeed()

                // 设置一个新的3秒倒计时，如果3秒后没有新步数，则认为已停止
                stopHandler.postDelayed(stopRunnable, 3000L)
            }
        }
    }

    private fun calculateSpeed() {
        if (sessionStepCount <= 0) return

        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0

        if (elapsedTime > 1) { // 至少经过1秒才计算，避免初始速度过大
            val distance = sessionStepCount * userStrideLengthMeters
            val newSpeed = (distance / elapsedTime).toFloat()

            // 速度告警检查
            if (speedLimit > 0 && newSpeed > speedLimit) {
                Toast.makeText(this, "警告: 速度已超过上限 ${speedLimit}m/s", Toast.LENGTH_SHORT).show()
            }

            // 使用动画平滑更新速度显示
            animateSpeedUpdate(newSpeed)
        }
    }

    private fun animateSpeedUpdate(newSpeed: Float) {
        val animator = ValueAnimator.ofFloat(currentDisplayedSpeed, newSpeed)
        animator.duration = 1500 // 动画持续时间，1.5秒
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
