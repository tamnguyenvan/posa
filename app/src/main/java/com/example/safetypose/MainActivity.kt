package com.example.safetypose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var videoPreviewImage: ImageView
    private lateinit var overlayView: OverlayView
    private lateinit var permissionPanel: LinearLayout
    private lateinit var permissionMessage: TextView
    private lateinit var requestPermissionButton: Button
    private lateinit var permissionPickVideoButton: Button
    private lateinit var pickVideoButton: Button
    private lateinit var modelSelectorButton: Button
    private lateinit var videoStatusText: TextView
    private lateinit var switchCameraButton: Button
    private lateinit var fpsText: TextView
    private lateinit var spineText: TextView
    private lateinit var safetyText: TextView

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var poseAnalyzer: AutoCloseable? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var permissionDeniedOnce = false
    private var openSettingsForCamera = false
    private var inputMode = InputMode.CAMERA
    private var selectedPoseBackend = PoseBackend.MEDIAPIPE
    private var currentVideoUri: Uri? = null
    private var videoFuture: Future<*>? = null
    private var videoCancelSignal = AtomicBoolean(false)

    private val safetyStabilizer = SafetyLogic.SafetyStateStabilizer()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionPanel.visibility = View.GONE
            startCamera()
        } else {
            permissionDeniedOnce = true
            showCameraPermissionRecovery()
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(::processVideoUri)
    }

    private val openVideoDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::processVideoUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        cameraExecutor = Executors.newSingleThreadExecutor()
        selectInitialPoseBackend()
        updateHud(0f, null, hasPose = false)

        requestPermissionButton.setOnClickListener {
            if (openSettingsForCamera) {
                openAppSettings()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        val pickVideoClickListener = View.OnClickListener {
            launchVideoPicker()
        }
        pickVideoButton.setOnClickListener(pickVideoClickListener)
        permissionPickVideoButton.setOnClickListener(pickVideoClickListener)
        modelSelectorButton.setOnClickListener {
            togglePoseBackend()
        }

        switchCameraButton.setOnClickListener {
            if (inputMode == InputMode.VIDEO) {
                switchToCameraMode()
            } else {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                bindCameraUseCases()
            }
        }

        if (hasCameraPermission()) {
            permissionPanel.visibility = View.GONE
            startCamera()
        } else {
            showCameraPermissionPrompt()
        }
    }

    override fun onResume() {
        super.onResume()
        if (inputMode == InputMode.CAMERA && hasCameraPermission() && cameraProvider != null) {
            permissionPanel.visibility = View.GONE
            bindCameraUseCases()
        }
    }

    override fun onDestroy() {
        cancelVideoProcessing()
        poseAnalyzer?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        videoPreviewImage = findViewById(R.id.videoPreviewImage)
        overlayView = findViewById(R.id.overlayView)
        permissionPanel = findViewById(R.id.permissionPanel)
        permissionMessage = findViewById(R.id.permissionMessage)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        permissionPickVideoButton = findViewById(R.id.permissionPickVideoButton)
        pickVideoButton = findViewById(R.id.pickVideoButton)
        modelSelectorButton = findViewById(R.id.modelSelectorButton)
        videoStatusText = findViewById(R.id.videoStatusText)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        fpsText = findViewById(R.id.fpsText)
        spineText = findViewById(R.id.spineText)
        safetyText = findViewById(R.id.safetyText)
        updateModelSelectorText()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases() {
        if (inputMode != InputMode.CAMERA) return

        val provider = cameraProvider ?: return
        if (!hasCameraPermission()) {
            showCameraPermissionPrompt()
            return
        }

        val backend = resolveSelectedBackend()
        val analyzer = try {
            createCameraAnalyzer(backend)
        } catch (error: Throwable) {
            handleAnalyzerError(error.message ?: "Unable to initialize pose detector")
            return
        }
        poseAnalyzer?.close()
        poseAnalyzer = analyzer as AutoCloseable

        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(480, 640),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor, analyzer)
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            overlayView.updatePose(
                landmarks = emptyList(),
                safetyState = null,
                imageWidth = 0,
                imageHeight = 0,
                mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT
            )
            switchCameraButton.visibility = View.VISIBLE
            switchCameraButton.text = getString(R.string.switch_camera)
            pickVideoButton.visibility = View.VISIBLE
        } catch (error: Exception) {
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                lensFacing = CameraSelector.LENS_FACING_BACK
                bindCameraUseCases()
            } else {
                handleAnalyzerError(error.message ?: "Unable to bind camera")
            }
        }
    }

    private fun launchVideoPicker() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
            videoPickerLauncher.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    .build()
            )
        } else {
            openVideoDocumentLauncher.launch(arrayOf("video/*"))
        }
    }

    private fun processVideoUri(uri: Uri) {
        switchToVideoMode()
        currentVideoUri = uri
        val backend = resolveSelectedBackend()

        val cancelSignal = AtomicBoolean(false)
        videoCancelSignal = cancelSignal
        videoFuture = cameraExecutor.submit {
            VideoPoseProcessor.process(
                context = applicationContext,
                uri = uri,
                poseBackend = backend,
                isCancelled = { cancelSignal.get() || Thread.currentThread().isInterrupted },
                onFrame = ::handleVideoFrame,
                onComplete = {
                    runOnUiThread {
                        if (inputMode == InputMode.VIDEO && !cancelSignal.get()) {
                            videoStatusText.text = getString(R.string.video_ready)
                        }
                    }
                },
                onError = { message ->
                    runOnUiThread {
                        if (inputMode == InputMode.VIDEO && !cancelSignal.get()) {
                            videoStatusText.text = message
                            updateHud(0f, null, hasPose = false)
                        }
                    }
                }
            )
        }
    }

    private fun handleVideoFrame(frame: VideoFrameResult) {
        val frameState = evaluateFrameLandmarks(frame.landmarks)

        runOnUiThread {
            if (inputMode != InputMode.VIDEO) return@runOnUiThread

            videoPreviewImage.setImageBitmap(frame.bitmap)
            overlayView.updatePose(
                landmarks = frame.landmarks,
                safetyState = frameState.safetyState,
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                mirrorHorizontally = false
            )
            updateHud(frame.processingFps, frameState.safetyState, frameState.hasPose)
            videoStatusText.text = getString(R.string.video_processing, frame.progressPercent)
        }
    }

    private fun switchToVideoMode() {
        inputMode = InputMode.VIDEO
        cancelVideoProcessing()
        cameraProvider?.unbindAll()
        poseAnalyzer?.close()
        poseAnalyzer = null
        safetyStabilizer.reset()

        permissionPanel.visibility = View.GONE
        previewView.visibility = View.GONE
        videoPreviewImage.visibility = View.VISIBLE
        videoStatusText.visibility = View.VISIBLE
        videoStatusText.text = getString(R.string.video_processing, 0)
        switchCameraButton.visibility = View.VISIBLE
        switchCameraButton.text = getString(R.string.live_camera)
        pickVideoButton.visibility = View.VISIBLE
        overlayView.updatePose(
            landmarks = emptyList(),
            safetyState = null,
            imageWidth = 0,
            imageHeight = 0,
            mirrorHorizontally = false
        )
        updateHud(0f, null, hasPose = false)
    }

    private fun switchToCameraMode() {
        inputMode = InputMode.CAMERA
        currentVideoUri = null
        cancelVideoProcessing()
        safetyStabilizer.reset()

        videoPreviewImage.setImageDrawable(null)
        videoPreviewImage.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        videoStatusText.visibility = View.GONE
        switchCameraButton.text = getString(R.string.switch_camera)
        updateHud(0f, null, hasPose = false)

        if (hasCameraPermission()) {
            permissionPanel.visibility = View.GONE
            if (cameraProvider == null) {
                startCamera()
            } else {
                bindCameraUseCases()
            }
        } else {
            showCameraPermissionPrompt()
        }
    }

    private fun cancelVideoProcessing() {
        videoCancelSignal.set(true)
        videoFuture?.cancel(true)
        videoFuture = null
    }

    private fun handlePoseResult(frame: PoseFrameResult) {
        if (inputMode != InputMode.CAMERA) return

        handleCameraLandmarks(
            landmarks = frame.landmarks,
            imageWidth = frame.imageWidth,
            imageHeight = frame.imageHeight,
            fps = frame.fps
        )
    }

    private fun handleYoloPoseResult(frame: YoloPoseFrameResult) {
        if (inputMode != InputMode.CAMERA) return

        handleCameraLandmarks(
            landmarks = frame.landmarks,
            imageWidth = frame.imageWidth,
            imageHeight = frame.imageHeight,
            fps = frame.fps
        )
    }

    private fun handleCameraLandmarks(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int,
        fps: Float
    ) {
        val frameState = evaluateFrameLandmarks(landmarks)

        runOnUiThread {
            if (inputMode != InputMode.CAMERA) return@runOnUiThread

            overlayView.updatePose(
                landmarks = landmarks,
                safetyState = frameState.safetyState,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                mirrorHorizontally = lensFacing == CameraSelector.LENS_FACING_FRONT
            )
            updateHud(fps, frameState.safetyState, frameState.hasPose)
        }
    }

    private fun createCameraAnalyzer(backend: PoseBackend): ImageAnalysis.Analyzer {
        return when (backend) {
            PoseBackend.MEDIAPIPE -> PoseAnalyzer(
                context = this,
                onResult = ::handlePoseResult,
                onError = ::handleAnalyzerError
            )

            PoseBackend.YOLO26 -> YoloPoseAnalyzer(
                context = this,
                onResult = ::handleYoloPoseResult,
                onError = ::handleAnalyzerError
            )
        }
    }

    private fun togglePoseBackend() {
        val nextBackend = when (selectedPoseBackend) {
            PoseBackend.MEDIAPIPE -> PoseBackend.YOLO26
            PoseBackend.YOLO26 -> PoseBackend.MEDIAPIPE
        }

        if (nextBackend == PoseBackend.YOLO26) {
            val errorMessage = YoloPoseDetector.availabilityError(this)
            if (errorMessage != null) {
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                return
            }
        }

        selectedPoseBackend = nextBackend
        safetyStabilizer.reset()
        updateModelSelectorText()
        overlayView.updatePose(
            landmarks = emptyList(),
            safetyState = null,
            imageWidth = 0,
            imageHeight = 0,
            mirrorHorizontally = false
        )
        updateHud(0f, null, hasPose = false)

        when (inputMode) {
            InputMode.CAMERA -> bindCameraUseCases()
            InputMode.VIDEO -> currentVideoUri?.let(::processVideoUri)
        }
    }

    private fun resolveSelectedBackend(): PoseBackend {
        if (selectedPoseBackend == PoseBackend.YOLO26) {
            val errorMessage = YoloPoseDetector.availabilityError(this)
            if (errorMessage != null) {
                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                selectedPoseBackend = PoseBackend.MEDIAPIPE
                updateModelSelectorText()
            }
        }
        return selectedPoseBackend
    }

    private fun selectInitialPoseBackend() {
        if (
            selectedPoseBackend == PoseBackend.MEDIAPIPE &&
            !isMediaPipeRuntimeSupported() &&
            YoloPoseDetector.availabilityError(this) == null
        ) {
            selectedPoseBackend = PoseBackend.YOLO26
            updateModelSelectorText()
        }
    }

    private fun isMediaPipeRuntimeSupported(): Boolean {
        return Build.SUPPORTED_ABIS.any { abi -> abi in MEDIAPIPE_SUPPORTED_ABIS }
    }

    private fun updateModelSelectorText() {
        modelSelectorButton.text = when (selectedPoseBackend) {
            PoseBackend.MEDIAPIPE -> getString(R.string.model_mediapipe)
            PoseBackend.YOLO26 -> getString(R.string.model_yolo26)
        }
    }

    private fun evaluateFrameLandmarks(landmarks: List<NormalizedLandmark>): FramePoseState {
        val hasPose = landmarks.isNotEmpty() && SafetyLogic.hasVisibleRequiredLandmarks(landmarks)
        val safetyState = if (hasPose) {
            safetyStabilizer.update(SafetyLogic.evaluate(landmarks))
        } else {
            safetyStabilizer.reset()
            null
        }
        return FramePoseState(hasPose = hasPose, safetyState = safetyState)
    }

    private fun handleAnalyzerError(message: String) {
        runOnUiThread {
            if (selectedPoseBackend == PoseBackend.YOLO26) {
                selectedPoseBackend = PoseBackend.MEDIAPIPE
                updateModelSelectorText()
                Toast.makeText(this, getString(R.string.yolo26_fallback), Toast.LENGTH_LONG).show()
                if (inputMode == InputMode.CAMERA && hasCameraPermission()) {
                    bindCameraUseCases()
                }
                return@runOnUiThread
            }

            updateHud(0f, null, hasPose = false)
            permissionPanel.visibility = View.VISIBLE
            permissionMessage.text = message
            requestPermissionButton.visibility = View.GONE
            switchCameraButton.visibility = View.GONE
        }
    }

    private fun updateHud(fps: Float, safetyState: SafetyState?, hasPose: Boolean) {
        fpsText.text = hudText("FPS", if (fps > 0f) String.format(Locale.US, "%.0f", fps) else "--")
        val spineValue = if (hasPose && safetyState != null) {
            String.format(Locale.US, "%.0f\u00B0", safetyState.spineAngle)
        } else {
            "--"
        }
        spineText.text = hudText("Spine", spineValue)

        val safetyValue = when {
            !hasPose -> "No pose"
            safetyState != null -> safetyState.level.name
            else -> "Ready"
        }
        safetyText.text = hudText("Status", safetyValue)

        val statusColor = when (safetyState?.level) {
            SafetyLevel.UNSAFE -> color(R.color.safety_red)
            SafetyLevel.WARNING -> color(R.color.safety_amber)
            SafetyLevel.SAFE -> color(R.color.safety_green)
            null -> Color.WHITE
        }
        spineText.setTextColor(statusColor.takeIf { safetyState?.level != SafetyLevel.SAFE } ?: Color.WHITE)
        safetyText.setTextColor(statusColor)
        fpsText.setTextColor(Color.WHITE)
        safetyText.contentDescription = safetyState?.reason ?: safetyValue
    }

    private fun hudText(label: String, value: String): SpannableString {
        val text = "$label\n$value"
        return SpannableString(text).apply {
            setSpan(RelativeSizeSpan(0.75f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), label.length + 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun showCameraPermissionPrompt() {
        openSettingsForCamera = false
        permissionPanel.visibility = View.VISIBLE
        permissionMessage.text = getString(R.string.camera_permission_message)
        requestPermissionButton.visibility = View.VISIBLE
        requestPermissionButton.text = getString(R.string.grant_camera)
        switchCameraButton.visibility = View.GONE
        pickVideoButton.visibility = View.VISIBLE
    }

    private fun showCameraPermissionRecovery() {
        openSettingsForCamera = permissionDeniedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        permissionPanel.visibility = View.VISIBLE
        permissionMessage.text = if (openSettingsForCamera) {
            "Camera permission is blocked. Open app settings and enable Camera to run on-device pose detection."
        } else {
            "Camera permission was denied. The app cannot detect posture without the live camera preview."
        }
        requestPermissionButton.visibility = View.VISIBLE
        requestPermissionButton.text = if (openSettingsForCamera) {
            getString(R.string.open_settings)
        } else {
            getString(R.string.grant_camera)
        }
        switchCameraButton.visibility = View.GONE
        pickVideoButton.visibility = View.VISIBLE
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun color(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    private data class FramePoseState(
        val hasPose: Boolean,
        val safetyState: SafetyState?
    )

    private companion object {
        val MEDIAPIPE_SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86")
    }

    private enum class InputMode {
        CAMERA,
        VIDEO
    }
}
