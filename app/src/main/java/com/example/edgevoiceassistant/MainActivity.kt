package com.example.edgevoiceassistant
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var engine: SpeechToIntentEngine
    private val audioCapture = AudioCapture()
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private var cameraId: String? = null
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraId = cameraManager.cameraIdList.firstOrNull()
        engine = SpeechToIntentEngine(this)
        checkPermissions()
        recordButton.setOnClickListener { startListeningSession() }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
    }

    private fun startListeningSession() {
        recordButton.isEnabled = false
        statusText.text = "Listening for 1.0 second..."
        lifecycleScope.launch {
            val audioBuffer = audioCapture.recordOneSecond()
            statusText.text = "Processing audio..."
            val (commandId, commandName) = engine.predict(audioBuffer)
            statusText.text = "Detected: '$commandName' (ID: $commandId)"
            recordButton.isEnabled = true
            executeHardwareIntent(commandId)
        }
    }

    private fun executeHardwareIntent(commandId: Int) {
        when (commandId) {
            0 -> toggleFlashlight(true)
            1 -> toggleFlashlight(false)
            2 -> adjustVolume(AudioManager.ADJUST_RAISE)
            3 -> adjustVolume(AudioManager.ADJUST_LOWER)
            4 -> muteVolume()
            5 -> launchCamera()
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

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun muteVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        Toast.makeText(this, "Audio Muted", Toast.LENGTH_SHORT).show()
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.close()
    }
}
