# PROMPT: Build Android Workplace Safety Pose Estimation App (Demo APK)

## Context & Goal
Build a **self-contained Android demo app** that uses on-device pose estimation to detect unsafe body postures in real time (bad lifting form, dangerous spinal bends, awkward positions). This is a proof-of-concept to demonstrate feasibility for a workplace safety product. The app must run fully on-device — no server calls.

---

## Tech Stack (do not deviate)

| Layer | Choice | Reason |
|---|---|---|
| Pose model | **MediaPipe Pose Landmarker** (via `com.google.mediapipe:tasks-vision`) | 33 keypoints, best accuracy for single person, native Android SDK |
| Camera | **CameraX** (`androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view`) | Modern lifecycle-aware camera API |
| Language | **Kotlin** | Standard for new Android projects |
| Min SDK | **26** (Android 8.0) | CameraX + MediaPipe requirement |
| Target SDK | **34** |  |
| Build | **Gradle (Kotlin DSL)** — `build.gradle.kts` |  |

**Do NOT use:** OpenCV, YOLO, custom TFLite pipelines, deprecated Camera1/Camera2 raw APIs, or server-side inference.

---

## Project Structure

```
app/
├── src/main/
│   ├── java/com/example/safetypose/
│   │   ├── MainActivity.kt
│   │   ├── PoseAnalyzer.kt          ← CameraX ImageAnalysis.Analyzer
│   │   ├── SafetyLogic.kt           ← angle math + rule engine
│   │   ├── OverlayView.kt           ← custom view for skeleton + HUD
│   │   └── FpsTracker.kt            ← rolling average FPS
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   └── values/colors.xml
│   └── AndroidManifest.xml
├── build.gradle.kts
└── gradle/libs.versions.toml
```

---

## Gradle Dependencies (`libs.versions.toml`)

```toml
[versions]
mediapipe = "0.10.14"
camerax = "1.3.4"
kotlin = "1.9.25"
agp = "8.3.2"

[libraries]
mediapipe-tasks-vision = { group = "com.google.mediapipe", name = "tasks-vision", version.ref = "mediapipe" }
camerax-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
```

---

## Screen Layout (`activity_main.xml`)

Single-screen app. No navigation, no fragments. Layout stacked vertically:

```
┌─────────────────────────────────────┐
│         STATUS BAR                  │
├─────────────────────────────────────┤
│                                     │
│      PreviewView (camera feed)      │
│      + OverlayView on top           │
│      (skeleton, angles, alerts)     │
│                                     │
│  Weight: flex, fills most screen    │
│                                     │
├─────────────────────────────────────┤
│  HUD STRIP (fixed 80dp height):     │
│  [FPS: 24]  [Spine: 38°]  [SAFE ✓] │
└─────────────────────────────────────┘
```

Use `ConstraintLayout` as root. `PreviewView` fills the top area. `OverlayView` overlays it with `match_parent`. Bottom strip is a `LinearLayout` with 3 `TextView`s.

---

## Core Implementation Details

### 1. `PoseAnalyzer.kt` — MediaPipe integration

```kotlin
class PoseAnalyzer(
    context: Context,
    private val onResult: (PoseLandmarkerResult, ImageProxy) -> Unit
) : ImageAnalysis.Analyzer {

    private val poseLandmarker: PoseLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_lite.task") // bundle in assets/
            .setDelegate(Delegate.GPU)                       // fallback to CPU if GPU fails
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.6f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, input -> onResult(result, /* pass imageProxy */) }
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
    }

    override fun analyze(imageProxy: ImageProxy) {
        // Convert ImageProxy to MediaPipe MPImage
        // Call poseLandmarker.detectAsync(mpImage, frameTimestamp)
        // IMPORTANT: always close imageProxy at the end
    }
}
```

Download the model file from:
`https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task`
Place it in `app/src/main/assets/pose_landmarker_lite.task`.

### 2. `SafetyLogic.kt` — Angle math + Rule engine

Implement these functions:

```kotlin
object SafetyLogic {

    // MediaPipe landmark indices (use PoseLandmark.* constants)
    // LEFT_SHOULDER=11, RIGHT_SHOULDER=12
    // LEFT_HIP=23, RIGHT_HIP=24
    // LEFT_KNEE=25, RIGHT_KNEE=26
    // LEFT_ANKLE=27, RIGHT_ANKLE=28

    /**
     * Calculate angle at vertex B, formed by rays B→A and B→C.
     * Returns degrees 0-180.
     */
    fun angleBetween(a: PointF, b: PointF, c: PointF): Float

    /**
     * Midpoint of two landmarks.
     */
    fun midpoint(p1: NormalizedLandmark, p2: NormalizedLandmark): PointF

    /**
     * Spine forward flexion angle.
     * = angle at hip_midpoint between shoulder_midpoint and a vertical reference point
     *   directly below hip_midpoint (same X, Y+0.1).
     * Safe range: 0°–35°
     * Warning: 35°–50°
     * Unsafe: >50°
     */
    fun spineAngle(landmarks: List<NormalizedLandmark>): Float

    /**
     * Knee flexion angle (left knee).
     * = angle at LEFT_KNEE between LEFT_HIP and LEFT_ANKLE.
     * Normal standing: ~170°-180°
     * Good lifting squat: 90°-130°
     */
    fun kneeAngle(landmarks: List<NormalizedLandmark>): Float

    /**
     * Shoulder symmetry. True if one shoulder is >15° lower than the other.
     * Detects asymmetric carry/lift.
     */
    fun isAsymmetricShoulder(landmarks: List<NormalizedLandmark>): Boolean

    /**
     * Main safety evaluation. Returns a SafetyState.
     */
    fun evaluate(landmarks: List<NormalizedLandmark>): SafetyState
}

data class SafetyState(
    val spineAngle: Float,
    val kneeAngle: Float,
    val isAsymmetric: Boolean,
    val level: SafetyLevel,      // SAFE, WARNING, UNSAFE
    val reason: String           // e.g. "Spine bent 55° — straighten your back"
)

enum class SafetyLevel { SAFE, WARNING, UNSAFE }
```

**Safety rules (implement all three):**

| Rule | Threshold | Level |
|---|---|---|
| Spine angle | >50° | UNSAFE |
| Spine angle | 35°–50° | WARNING |
| Knee straight (>160°) while spine >35° | Combined | UNSAFE (lifting with back) |
| Asymmetric shoulder | >15° difference | WARNING |
| All within range | — | SAFE |

Use a **temporal buffer of 8 frames** — only update the displayed safety state if the same level persists for 8 consecutive frames. This prevents flickering from single-frame jitter.

### 3. `OverlayView.kt` — Canvas drawing

Custom `View` that draws on top of `PreviewView`. Must handle mirroring for front camera.

Draw:
- **Skeleton lines**: connect landmark pairs using `POSE_CONNECTIONS` constant (all 32 connections). Line color: white at 60% alpha. Stroke width: 3dp.
- **Landmark dots**: filled circles, radius 5dp, color: white.
- **Spine angle arc**: draw a yellow arc at the hip midpoint showing the spine bend angle.
- **Alert banner**: when UNSAFE, draw a semi-transparent red rectangle at top of view with text "⚠ UNSAFE POSTURE" in white 18sp bold.
- **WARNING state**: orange/amber banner instead.
- **SAFE state**: small green badge, do not cover the frame.

Coordinate mapping: MediaPipe returns normalized [0,1] coordinates. Scale them to view pixel coordinates. Remember to mirror X for front-facing camera: `mirroredX = 1 - normalizedX`.

### 4. `FpsTracker.kt`

Rolling average over last 30 frames. Update via `System.currentTimeMillis()` on each `analyze()` call. Expose `getCurrentFps(): Float`.

---

## MainActivity.kt — Wiring it together

```kotlin
class MainActivity : AppCompatActivity() {

    // 1. Check CAMERA permission at runtime (REQUEST_CODE pattern or ActivityResultLauncher)
    // 2. Set up CameraX ProcessCameraProvider
    // 3. Build ImageAnalysis use case:
    //    - Resolution: 480x640 (portrait) — enough for pose, saves compute
    //    - BackpressureStrategy: KEEP_LATEST
    //    - Executor: Executors.newSingleThreadExecutor()
    // 4. Attach PoseAnalyzer to ImageAnalysis
    // 5. Bind Preview + ImageAnalysis to lifecycleOwner
    // 6. On each result callback (runs on analysis thread):
    //    - Compute SafetyState
    //    - Update FpsTracker
    //    - Post UI updates to main thread via runOnUiThread { }
    //      → update OverlayView with new landmarks + safety state
    //      → update HUD TextViews
    // 7. Default camera: BACK (rear) camera
    //    Add a FAB or button to switch to FRONT camera (toggle CameraSelector)
}
```

---

## UI / UX Requirements

- **Portrait orientation only** (`android:screenOrientation="portrait"`)
- **Keep screen on** while app is in foreground (`WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`)
- **Dark background** (`#000000`) so skeleton overlay is visible
- HUD strip colors:
  - FPS text: `#FFFFFF`
  - Spine angle text: white normally, amber if WARNING, red if UNSAFE
  - Safety badge: green `#4CAF50` (SAFE), amber `#FF9800` (WARNING), red `#F44336` (UNSAFE)
- Font sizes: HUD labels 12sp, HUD values 16sp bold

---

## AndroidManifest.xml requirements

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />

<!-- Inside <activity>: -->
android:screenOrientation="portrait"
android:theme="@style/Theme.AppCompat.NoActionBar"
```

---

## Performance targets (must achieve on mid-range device, e.g. Pixel 4a / Samsung A53)

| Metric | Target |
|---|---|
| Inference FPS | ≥ 20 fps sustained |
| Model size | pose_landmarker_lite.task ≈ 4.7 MB |
| APK size | < 30 MB total |
| Startup to first pose | < 3 seconds |
| RAM usage | < 200 MB |

If GPU delegate is unavailable, fall back to CPU automatically (catch `RuntimeException` during init, retry with `Delegate.CPU`).

---

## What NOT to build

- No user accounts, login, or Firebase
- No data persistence or local database
- No network calls of any kind
- No video recording or gallery picker
- No multi-person tracking (single person only)
- No settings screen (hardcode all thresholds)

---

## Deliverables

1. **Full source code** — all files listed in Project Structure above
2. **`build.gradle.kts`** and **`libs.versions.toml`** — complete, compilable
3. **`AndroidManifest.xml`** — complete
4. **Brief comment in `SafetyLogic.kt`** explaining each threshold value and why it was chosen
5. The project should build with `./gradlew assembleDebug` without errors

---

## Potential Issues to Handle

- **MediaPipe GPU delegate crash** on some devices → catch and fall back to CPU
- **Camera rotation** → use `imageProxy.imageInfo.rotationDegrees` to rotate MPImage before inference
- **Landmark visibility** → skip drawing/evaluating landmarks with `visibility < 0.5`
- **First few frames** → MediaPipe may return empty results during warmup; guard against null/empty landmark list

---

## Quick Validation Checklist (for you to self-verify before finishing)

- [ ] App compiles and runs without crashes
- [ ] Camera preview visible on launch
- [ ] Skeleton lines drawn over detected person
- [ ] Spine angle value updates in real time in HUD
- [ ] Safety banner changes color/text based on posture
- [ ] FPS displayed and ≥ 15 on debug build
- [ ] No network permission requested
- [ ] Works with both front and rear camera
