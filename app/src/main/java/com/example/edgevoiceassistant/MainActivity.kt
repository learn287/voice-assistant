package com.example.edgevoiceassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AssistantState {
    STANDBY,
    AWAKE
}

class MainActivity : AppCompatActivity() {

    private lateinit var engine: SpeechToIntentEngine
    private val audioCapture = AudioCapture()
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private var cameraId: String? = null
    private lateinit var prefs: SharedPreferences

    private lateinit var modeBadge: TextView
    private lateinit var statusText: TextView
    private lateinit var toggleListenButton: Button
    private lateinit var wakeWordButton: Button
    private lateinit var guideButton: Button
    private lateinit var remapButton: Button
    private lateinit var mappingSummaryText: TextView

    private var listeningJob: Job? = null
    private var awakeTimeoutJob: Job? = null
    private var currentState = AssistantState.STANDBY

    private val availableActions = arrayOf(
        "Torch ON",
        "Torch OFF",
        "Volume UP",
        "Volume DOWN",
        "Mute Audio",
        "Launch Camera",
        "Vibrate Phone",
        "Screen Brightness 100%",
        "Screen Brightness 50%",
        "Screen Dim 10%",
        "Open Phone Dialer",
        "Launch Google Search",
        "Open Wi-Fi Settings",
        "Open Bluetooth Settings",
        "Open Battery Saver Settings",
        "Open Airplane Mode Settings",
        "Check Battery Status",
        "Set 5-Min Timer",
        "Open System Alarms",
        "Media Play / Pause",
        "Media Next Track",
        "Draft Quick SMS"
    )

    private val actionDescriptions = arrayOf(
        "Turns the rear camera flashlight ON.",
        "Turns the rear camera flashlight OFF.",
        "Increases media playback volume.",
        "Decreases media playback volume.",
        "Sets media playback volume directly to zero.",
        "Opens the camera app for photo capture.",
        "Triggers a 350ms haptic vibration pulse.",
        "Sets display brightness to 100% maximum.",
        "Sets display brightness to 50% medium.",
        "Dims display brightness to 10% minimum.",
        "Launches the phone dialer keypad for making calls.",
        "Opens the web search browser / Google Search.",
        "Opens the quick Wi-Fi / connectivity settings panel.",
        "Opens the quick Bluetooth settings panel.",
        "Opens Battery Saver / Low Power settings.",
        "Opens Airplane Mode system toggle page.",
        "Reads and displays real-time battery % and charging state.",
        "Sets a 5-minute countdown timer in the clock app.",
        "Opens the clock app to view/manage alarms.",
        "Sends Play/Pause toggle event to active media apps.",
        "Sends Next Track skip event to active media apps.",
        "Opens SMS app with a pre-filled quick reply template."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("EdgeVoicePrefs", Context.MODE_PRIVATE)
        modeBadge = findViewById(R.id.modeBadge)
        statusText = findViewById(R.id.statusText)
        toggleListenButton = findViewById(R.id.toggleListenButton)
        wakeWordButton = findViewById(R.id.wakeWordButton)
        guideButton = findViewById(R.id.guideButton)
        remapButton = findViewById(R.id.remapButton)
        mappingSummaryText = findViewById(R.id.mappingSummaryText)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraId = cameraManager.cameraIdList.firstOrNull()

        engine = SpeechToIntentEngine(this)

        checkPermissions()
        updateMappingDisplay()

        toggleListenButton.setOnClickListener {
            if (audioCapture.isRecording) {
                stopAssistant()
            } else {
                startAssistant()
            }
        }

        wakeWordButton.setOnClickListener {
            showWakeWordSelectorDialog()
        }

        guideButton.setOnClickListener {
            showCommandsGuideDialog()
        }

        remapButton.setOnClickListener {
            showRemapDialog()
        }
    }

    private fun getActiveWakeWord(): String {
        val defaultWake = if (engine.commands.contains("hey")) "hey" else "go"
        return prefs.getString("active_wake_word", defaultWake) ?: defaultWake
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun startAssistant() {
        audioCapture.startRecording()
        currentState = AssistantState.STANDBY
        val wake = getActiveWakeWord()

        modeBadge.text = "Status: STANDBY (Say '$wake')"
        modeBadge.setTextColor(0xFF03DAC5.toInt())
        toggleListenButton.text = "Stop Assistant"
        toggleListenButton.setBackgroundColor(0xFFCF6679.toInt())
        statusText.text = "Standing by. Say '$wake' to wake up the assistant."

        listeningJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && audioCapture.isRecording) {
                val windowResult = audioCapture.readOverlappingWindow()
                if (windowResult != null) {
                    val (audioBuffer, rmsEnergy) = windowResult

                    // Silence threshold: Process if speech energy is present
                    if (rmsEnergy >= 0.012f) {
                        val prediction = engine.predict(audioBuffer)

                        if (prediction.confidence >= 0.70f) {
                            withContext(Dispatchers.Main) {
                                handleAcousticDetection(prediction)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleAcousticDetection(prediction: PredictionResult) {
        val activeWake = getActiveWakeWord()

        when (currentState) {
            AssistantState.STANDBY -> {
                if (prediction.commandName.equals(activeWake, ignoreCase = true)) {
                    wakeUpAssistant()
                }
            }
            AssistantState.AWAKE -> {
                if (!prediction.commandName.equals(activeWake, ignoreCase = true)) {
                    executeActionForKeyword(prediction)
                    returnToStandby()
                }
            }
        }
    }

    private fun wakeUpAssistant() {
        currentState = AssistantState.AWAKE
        modeBadge.text = "Status: 🟢 AWAKE (Listening for Command)"
        modeBadge.setTextColor(0xFF00FF66.toInt())
        statusText.text = "Awake! Speak a command now..."

        triggerShortHaptic()

        awakeTimeoutJob?.cancel()
        awakeTimeoutJob = lifecycleScope.launch {
            delay(3500)
            if (currentState == AssistantState.AWAKE) {
                statusText.text = "Timeout: No command heard. Returning to standby."
                returnToStandby()
            }
        }
    }

    private fun returnToStandby() {
        awakeTimeoutJob?.cancel()
        currentState = AssistantState.STANDBY
        val wake = getActiveWakeWord()
        modeBadge.text = "Status: STANDBY (Say '$wake')"
        modeBadge.setTextColor(0xFF03DAC5.toInt())
    }

    private fun stopAssistant() {
        listeningJob?.cancel()
        awakeTimeoutJob?.cancel()
        audioCapture.stopRecording()
        currentState = AssistantState.STANDBY

        modeBadge.text = "Status: Idle"
        modeBadge.setTextColor(0xFF03DAC5.toInt())
        toggleListenButton.text = "Start Assistant"
        toggleListenButton.setBackgroundColor(0xFF03DAC5.toInt())
        statusText.text = "Assistant stopped. Tap to start."
    }

    private fun executeActionForKeyword(prediction: PredictionResult) {
        val boundActionIndex = getActionForKeyword(prediction.commandName)
        val actionName = availableActions.getOrElse(boundActionIndex) { "Unknown" }

        statusText.text = "Detected: '${prediction.commandName}' (${(prediction.confidence * 100).toInt()}%)\nAction: $actionName"

        when (boundActionIndex) {
            0 -> toggleFlashlight(true)
            1 -> toggleFlashlight(false)
            2 -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            3 -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            4 -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                Toast.makeText(this, "Muted", Toast.LENGTH_SHORT).show()
            }
            5 -> launchCamera()
            6 -> triggerVibration()
            7 -> setScreenBrightness(1.0f)
            8 -> setScreenBrightness(0.5f)
            9 -> setScreenBrightness(0.1f)
            10 -> openDialer()
            11 -> openGoogleSearch()
            12 -> openWifiSettings()
            13 -> openBluetoothSettings()
            14 -> openBatterySaverSettings()
            15 -> openAirplaneModeSettings()
            16 -> checkBatteryStatus()
            17 -> setFiveMinTimer()
            18 -> openAlarms()
            19 -> toggleMediaPlayback()
            20 -> skipMediaNext()
            21 -> draftQuickSms()
        }
    }

    private fun getActionForKeyword(keyword: String): Int {
        val defaultIdx = when (keyword) {
            "on" -> 0
            "off" -> 1
            "up" -> 2
            "down" -> 3
            "stop" -> 4
            "go" -> 5
            else -> 0
        }
        return prefs.getInt("bind_$keyword", defaultIdx)
    }

    private fun showWakeWordSelectorDialog() {
        val choices = engine.commands.toTypedArray()
        val current = getActiveWakeWord()
        val currentIdx = choices.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select Wake Word")
            .setSingleChoiceItems(choices, currentIdx) { dialog, which ->
                val selected = choices[which]
                prefs.edit().putString("active_wake_word", selected).apply()
                updateMappingDisplay()
                dialog.dismiss()
                Toast.makeText(this, "Wake Word set to '$selected'", Toast.LENGTH_SHORT).show()
                if (audioCapture.isRecording) {
                    returnToStandby()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommandsGuideDialog() {
        val messageBuilder = StringBuilder()
        val wake = getActiveWakeWord()

        messageBuilder.append("ACTIVE WAKE TRIGGER:\n")
        messageBuilder.append("🗣️ Say \"$wake\" to activate listening.\n\n")

        messageBuilder.append("COMMAND BINDINGS:\n")
        for (cmd in engine.commands) {
            if (cmd.equals(wake, ignoreCase = true)) continue
            val actionIdx = getActionForKeyword(cmd)
            val actionName = availableActions.getOrElse(actionIdx) { "Unknown" }
            messageBuilder.append("• \"$cmd\" ➔ $actionName\n")
        }

        messageBuilder.append("\nALL AVAILABLE ACTIONS (${availableActions.size}):\n")
        for (i in availableActions.indices) {
            messageBuilder.append("• ${availableActions[i]}: ${actionDescriptions[i]}\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Commands & Features Guide")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("Got It", null)
            .setNeutralButton("Remap Actions") { _, _ ->
                showRemapDialog()
            }
            .show()
    }

    private fun showRemapDialog() {
        val wake = getActiveWakeWord()
        val commandsToRemap = engine.commands.filter { !it.equals(wake, ignoreCase = true) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Command to Remap")
            .setItems(commandsToRemap) { _, idx ->
                val selectedCmd = commandsToRemap[idx]
                AlertDialog.Builder(this)
                    .setTitle("Assign Action for '$selectedCmd'")
                    .setSingleChoiceItems(availableActions, getActionForKeyword(selectedCmd)) { dialog, actionIdx ->
                        prefs.edit().putInt("bind_$selectedCmd", actionIdx).apply()
                        updateMappingDisplay()
                        dialog.dismiss()
                        Toast.makeText(this, "'$selectedCmd' remapped", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateMappingDisplay() {
        val sb = StringBuilder()
        val wake = getActiveWakeWord()
        sb.append("⚡ Wake Word: \"$wake\" (Wakes assistant)\n\n")
        for (cmd in engine.commands) {
            if (cmd.equals(wake, ignoreCase = true)) continue
            val actionIdx = getActionForKeyword(cmd)
            sb.append("• \"$cmd\" ➔ ${availableActions.getOrElse(actionIdx) { "Unknown" }}\n")
        }
        mappingSummaryText.text = sb.toString()
    }

    private fun triggerShortHaptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(120)
        }
    }

    private fun toggleFlashlight(turnOn: Boolean) {
        cameraId?.let {
            try {
                cameraManager.setTorchMode(it, turnOn)
                Toast.makeText(this, "Torch: ${if (turnOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Torch Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(350)
        }
        Toast.makeText(this, "Vibrated", Toast.LENGTH_SHORT).show()
    }

    private fun setScreenBrightness(brightnessValue: Float) {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = brightnessValue
        window.attributes = layoutParams
        Toast.makeText(this, "Brightness: ${(brightnessValue * 100).toInt()}%", Toast.LENGTH_SHORT).show()
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    private fun openDialer() {
        val dialIntent = Intent(Intent.ACTION_DIAL)
        startActivity(dialIntent)
    }

    private fun openGoogleSearch() {
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH)
        startActivity(searchIntent)
    }

    private fun openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
                startActivity(panelIntent)
            } else {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun openBluetoothSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_BLUETOOTH)
                startActivity(panelIntent)
            } else {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun openBatterySaverSettings() {
        startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }

    private fun openAirplaneModeSettings() {
        startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    }

    private fun setFiveMinTimer() {
        val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, 300)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Edge Assistant Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        if (timerIntent.resolveActivity(packageManager) != null) {
            startActivity(timerIntent)
        } else {
            Toast.makeText(this, "No Clock App found to set timer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAlarms() {
        val alarmIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        if (alarmIntent.resolveActivity(packageManager) != null) {
            startActivity(alarmIntent)
        }
    }

    private fun toggleMediaPlayback() {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        Toast.makeText(this, "Media: Play/Pause Toggled", Toast.LENGTH_SHORT).show()
    }

    private fun skipMediaNext() {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
        Toast.makeText(this, "Media: Skipped to Next", Toast.LENGTH_SHORT).show()
    }

    private fun draftQuickSms() {
        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", "Hey, I'm using Edge Voice Assistant right now!")
        }
        if (smsIntent.resolveActivity(packageManager) != null) {
            startActivity(smsIntent)
        }
    }

    private fun checkBatteryStatus() {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = registerReceiver(null, batteryFilter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        val infoMsg = "Battery: $pct% ${if (isCharging) "(Charging ⚡)" else "(On Battery)"}"
        statusText.text = infoMsg
        Toast.makeText(this, infoMsg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAssistant()
        engine.close()
    }
}
