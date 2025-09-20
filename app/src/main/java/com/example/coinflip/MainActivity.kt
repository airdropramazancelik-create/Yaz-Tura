package com.example.coinflip

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.media.MediaPlayer
import android.os.*
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.example.coinflip.databinding.ActivityMainBinding
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import android.hardware.SensorManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var isAnimating = false

    private lateinit var headsBitmap: Bitmap
    private lateinit var tailsBitmap: Bitmap

    private var lastResult: String = ""
    private val history = ArrayDeque<String>()

    // Stats
    private var total = 0
    private var heads = 0
    private var tails = 0
    private var streak = 0
    private var streakSide = ""

    // Settings
    private lateinit var prefs: SharedPreferences
    private var enableSound = true
    private var enableVibration = true
    private var enableShake = true
    private var speedPercent = 100 // 50-150 maps to 0.8x-1.2x

    // Media
    private var mediaPlayer: MediaPlayer? = null

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var lastAccel = 0f
    private var currentAccel = 0f
    private var accel = 0f
    private var lastShakeTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        prefs = getSharedPreferences("coinflip_prefs", Context.MODE_PRIVATE)
        loadSettings()
        loadStats()

        headsBitmap = createCoinBitmap(getString(R.string.heads).first().uppercase())
        tailsBitmap = createCoinBitmap(getString(R.string.tails).first().uppercase())
        binding.coinView.setImageBitmap(headsBitmap)

        binding.flipButton.setOnClickListener { if (!isAnimating) tossCoin() }
        binding.rootLayout.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && !isAnimating) tossCoin()
            true
        }
        binding.themeButton.setOnClickListener {
            val current = AppCompatDelegate.getDefaultNightMode()
            if (current == AppCompatDelegate.MODE_NIGHT_YES) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                Toast.makeText(this, getString(R.string.theme_toast_light), Toast.LENGTH_SHORT).show()
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                Toast.makeText(this, getString(R.string.theme_toast_dark), Toast.LENGTH_SHORT).show()
            }
        }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelSensor ->
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        updateStatsText()
    }

    private fun loadSettings() {
        enableSound = prefs.getBoolean("sound", true)
        enableVibration = prefs.getBoolean("vibration", true)
        enableShake = prefs.getBoolean("shake", true)
        speedPercent = prefs.getInt("speed", 100)
        speedPercent = speedPercent.coerceIn(50, 150)
    }

    private fun saveSettings() {
        prefs.edit().putBoolean("sound", enableSound)
            .putBoolean("vibration", enableVibration)
            .putBoolean("shake", enableShake)
            .putInt("speed", speedPercent)
            .apply()
    }

    private fun loadStats() {
        total = prefs.getInt("total", 0)
        heads = prefs.getInt("heads", 0)
        tails = prefs.getInt("tails", 0)
        streak = prefs.getInt("streak", 0)
        streakSide = prefs.getString("streakSide", "") ?: ""
    }

    private fun saveStats() {
        prefs.edit().putInt("total", total)
            .putInt("heads", heads)
            .putInt("tails", tails)
            .putInt("streak", streak)
            .putString("streakSide", streakSide)
            .apply()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.settings_dialog, null)
        val swSound = view.findViewById<Switch>(R.id.switch_sound)
        val swVib = view.findViewById<Switch>(R.id.switch_vibrate)
        val swShake = view.findViewById<Switch>(R.id.switch_shake)
        val seek = view.findViewById<SeekBar>(R.id.seek_speed)
        val txt = view.findViewById<TextView>(R.id.text_speed_value)

        swSound.isChecked = enableSound
        swVib.isChecked = enableVibration
        swShake.isChecked = enableShake
        seek.progress = speedPercent
        txt.text = "${speedPercent}%"

        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                speedPercent = progress.coerceIn(50, 150)
                txt.text = "${speedPercent}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("OK") { d, _ ->
                enableSound = swSound.isChecked
                enableVibration = swVib.isChecked
                enableShake = swShake.isChecked
                saveSettings()
                d.dismiss()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun createCoinBitmap(letter: String): Bitmap {
        val density = resources.displayMetrics.density
        val px = max(220, (220 * density).roundToInt())
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // coin base
        p.style = Paint.Style.FILL
        p.color = 0xFFFFD54F.toInt()
        c.drawCircle(px/2f, px/2f, px/2f - 6f, p)

        // ring
        p.style = Paint.Style.STROKE
        p.strokeWidth = 10f
        p.color = 0xFFB8860B.toInt()
        c.drawCircle(px/2f, px/2f, px/2f - 12f, p)

        // letter
        p.style = Paint.Style.FILL
        p.color = 0xFF222222.toInt()
        p.textSize = px / 2.8f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        c.drawText(letter, px/2f, px/2f + p.textSize/3f, p)

        return bmp
    }

    private fun playHit() {
        if (!enableSound) return
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, R.raw.coin)
        mediaPlayer?.start()
    }

    private fun vibrate(ms: Long) {
        if (!enableVibration) return
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(ms)
        }
    }

    private fun tossCoin() {
        isAnimating = true
        binding.resultText.text = ""
        vibrate(10)

        val startY = binding.coinView.y
        val floorY = startY
        val jumpHeight = resources.displayMetrics.heightPixels * 0.35f
        val baseDuration = 1200L
        val duration = (baseDuration * (100.0 / speedPercent.coerceAtLeast(1))).toLong().coerceIn(800L, 1800L)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction
                val base = floorY - jumpHeight * kotlin.math.sin(PI.toFloat() * t)
                var y = base
                if (t > 0.95f) {
                    val tb = (t - 0.95f) / 0.05f
                    val amp = jumpHeight * 0.08f * (1f - tb)
                    y = floorY - amp * kotlin.math.cos(PI.toFloat() * tb)
                }
                binding.coinView.y = y
            }
            addListener(onEnd = {
                playHit()
                vibrate(20)
                val isHeads = Random.nextBoolean()
                val result = if (isHeads) getString(R.string.heads) else getString(R.string.tails)
                binding.coinView.setImageBitmap(if (isHeads) headsBitmap else tailsBitmap)
                onResult(result)
                binding.coinView.rotationY = 0f
                isAnimating = false
            })
        }

        val spin = ValueAnimator.ofFloat(0f, 1440f).apply {
            duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val rot = a.animatedValue as Float
                binding.coinView.rotationY = rot % 360f
                val phase = (rot % 360f)
                binding.coinView.setImageBitmap(if (phase < 180f) headsBitmap else tailsBitmap)
            }
        }

        animator.start()
        spin.start()
    }

    private fun onResult(text: String) {
        lastResult = text
        binding.resultText.text = getString(R.string.result_prefix, text)
        history.addFirst(text)
        while (history.size > 12) history.removeLast()
        binding.historyText.text = history.joinToString(" • ")

        // stats
        total += 1
        if (text == getString(R.string.heads)) {
            heads += 1
            if (streakSide == "H") streak += 1 else { streak = 1; streakSide = "H" }
        } else {
            tails += 1
            if (streakSide == "T") streak += 1 else { streak = 1; streakSide = "T" }
        }
        saveStats()
        updateStatsText()
    }

    private fun updateStatsText() {
        val sideText = if (streakSide == "H") getString(R.string.streak_heads) else if (streakSide == "T") getString(R.string.streak_tails) else ""
        binding.statsText.text = getString(R.string.stat_line, total, heads, tails, streak, sideText)
    }

    // Sensor handling for shake
    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (!enableShake || isAnimating) return
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            lastAccel = currentAccel
            currentAccel = kotlin.math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()
            val delta = currentAccel - lastAccel
            accel = accel * 0.9f + delta
            val now = System.currentTimeMillis()
            if (accel > 12 && now - lastShakeTime > 800) { // threshold + debounce
                lastShakeTime = now
                tossCoin()
            }
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelSensor ->
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
