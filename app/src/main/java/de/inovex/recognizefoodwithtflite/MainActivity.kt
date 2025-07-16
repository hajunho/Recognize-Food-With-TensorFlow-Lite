package de.inovex.recognizefoodwithtflite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.inovex.recognizefoodwithtflite.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Executors

// Listener for the result of the ImageAnalyzer
typealias RecognitionListener = (recognition: Recognition) -> Unit

// 1. Implement TextToSpeech.OnInitListener to know when the TTS engine is ready
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // CameraX
    private lateinit var preview: Preview // Preview use case, fast, responsive view of the camera
    private lateinit var imageAnalyzer: ImageAnalysis // Analysis use case, for running ML code
    private lateinit var camera: Camera
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var binding: ActivityMainBinding

    // ViewModel, where the recognition results will be stored and updated
    private val recognitionListViewModel: RecognitionViewModel by viewModels()

    // 2. Add variables for TextToSpeech and state management
    private lateinit var tts: TextToSpeech
    private var lastRecognizedLabel: String = ""
    private var lastRecognitionTime: Long = 0L
    private var hasSpoken: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_RecognizeFoodWithTFLite)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.lifecycleOwner = this
        binding.viewmodel = recognitionListViewModel

        // 3. Initialize the TextToSpeech engine
        tts = TextToSpeech(this, this)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    /**
     * Check all permissions are granted - use for Camera permission in this example.
     */
    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * This gets called after the Camera permission pop up is shown.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.permission_deny_text), Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
    }

    /**
     * Start the Camera which involves initializing and binding camera use cases
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder().build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysisUseCase: ImageAnalysis ->
                    analysisUseCase.setAnalyzer(
                        cameraExecutor, ImageAnalyzer(this) { recognition ->
                            // 8. Call the new handler on the main thread
                            runOnUiThread {
                                handleRecognition(recognition)
                            }
                        }
                    )
                }

            val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)
            } catch (exc: Exception) {
                Log.e(getString(R.string.app_name), "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // 4. Implement the onInit function for the TTS listener
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set US English as language for TTS
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            }
        } else {
            Log.e("TTS", "Initialization Failed!")
        }
    }

    // 5. Shut down the TTS engine when the activity is destroyed to prevent memory leaks
    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        cameraExecutor.shutdown()
    }

    // 6. Add a function to handle the speech output
    private fun speak(text: String) {
        // QUEUE_FLUSH: Clears the speech queue and plays the new text immediately.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    /**
     * 7. This is the main logic to process new recognitions from the ImageAnalyzer
     */
    private fun handleRecognition(recognition: Recognition) {
        // Update the ViewModel to show the latest recognition on the UI
        recognitionListViewModel.updateData(recognition)

        // If the new recognition is different from the last one, reset our timer and state
        if (lastRecognizedLabel != recognition.label) {
            lastRecognizedLabel = recognition.label
            lastRecognitionTime = System.currentTimeMillis()
            hasSpoken = false // Reset speech flag for the new item
            return // Exit function after resetting
        }

        // If the recognition is the same and we haven't spoken it yet
        if (!hasSpoken) {
            val currentTime = System.currentTimeMillis()
            val duration = currentTime - lastRecognitionTime

            // Check if the recognition has been stable for 2 seconds (2000 ms)
            if (duration >= 2000) {
                // Speak the name of the recognized food
                speak(recognition.label)
                hasSpoken = true // Set the flag to true so we don't speak it again
            }
        }
    }


    companion object {
        const val WIDTH = 224
        const val HEIGHT = 224
        const val REQUEST_CODE_PERMISSIONS = 123
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}