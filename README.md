# Safety Pose

Android demo app for on-device workplace posture analysis using CameraX, MediaPipe Pose Landmarker, and optional YOLO26 pose inference through ExecuTorch or LiteRT/TFLite.

## Table of Contents
- [Overview](#overview)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Pose Backends](#pose-backends)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Build and Run](#build-and-run)
- [Export YOLO26 ExecuTorch Model](#export-yolo26-executorch-model)
- [How It Works](#how-it-works)
- [Troubleshooting](#troubleshooting)
- [Privacy](#privacy)

## Overview
Safety Pose is a single-screen Android demo that detects unsafe body posture in real time from the camera or from a selected video file. It runs fully on-device and does not send frames to a server.

## Features
- Live rear or front camera pose estimation
- Local video file analysis through the Android media picker
- Runtime model selector for MediaPipe or YOLO26 pose
- Skeleton overlay with posture warnings
- Spine, knee, and shoulder symmetry heuristics
- FPS display and safety state stabilization
- Portrait-only, no navigation flow, no account system

## Tech Stack
- Kotlin
- CameraX
- MediaPipe Pose Landmarker
- ExecuTorch Android runtime
- LiteRT/TFLite Android runtime
- Ultralytics YOLO26 pose model exported to `.pte` and `.tflite`
- Android View system with XML layouts
- Gradle Kotlin DSL

## Pose Backends
The app starts with MediaPipe because it supports the widest device set, including `armeabi-v7a`.

The YOLO26 option picks the best bundled runtime for the current device:
- ExecuTorch uses `app/src/main/assets/yolo26n-pose.pte` on `arm64-v8a` and `x86_64`.
- LiteRT/TFLite uses `app/src/main/assets/yolo26n-pose.tflite` on `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

This means the Nokia C30 `armeabi-v7a` split can run YOLO26 through LiteRT while newer 64-bit devices can still use the existing ExecuTorch path.

## Project Structure
```text
app/
  src/main/
    java/com/example/safetypose/
      MainActivity.kt
      PoseBackend.kt
      PoseAnalyzer.kt
      YoloPoseAnalyzer.kt
      YoloPoseDetector.kt
      VideoPoseProcessor.kt
      SafetyLogic.kt
      OverlayView.kt
      FpsTracker.kt
    assets/
      pose_landmarker_lite.task
      yolo26n-pose.pte
      yolo26n-pose.tflite
      yolo26n-pose-metadata.yaml
    res/
      layout/activity_main.xml
      values/
      values-night/
    AndroidManifest.xml
  build.gradle.kts
gradle/libs.versions.toml
tools/export_yolo26_pose_executorch.py
tools/export_yolo26_pose_litert.py
```

## Prerequisites
- Android Studio or a compatible Android build environment
- Android SDK with API 37.1 installed for compilation
- A physical Android device or emulator

Runtime targets in the app:
- `minSdk` 26
- `targetSdk` 34

## Setup
1. Open the project in Android Studio.
2. Sync Gradle.
3. Make sure the MediaPipe model asset exists at:
   `app/src/main/assets/pose_landmarker_lite.task`
4. For YOLO26, make sure the exported model assets exist:
   `app/src/main/assets/yolo26n-pose.pte`
   `app/src/main/assets/yolo26n-pose.tflite`
   `app/src/main/assets/yolo26n-pose-metadata.yaml`
5. Connect a device or start an emulator.

## Build and Run
Build the debug APK:
```bash
./gradlew assembleDebug
```

Install to a connected device:
```bash
./gradlew installDebug
```

Run unit tests:
```bash
./gradlew testDebugUnitTest
```

If Android Studio picks the wrong split APK for a connected device, use `installDebug` so Gradle selects the matching ABI split automatically.

## Export YOLO26 ExecuTorch Model
The helper scripts export `yolo26n-pose.pt` to ExecuTorch and LiteRT/TFLite, then stage the Android assets.

Export ExecuTorch:
```bash
MPLCONFIGDIR=/tmp/matplotlib-yolo-export \
  /home/tamnv/Downloads/python_exp/.venv/bin/python \
  tools/export_yolo26_pose_executorch.py \
  --model yolo26n-pose.pt \
  --assets-dir app/src/main/assets \
  --output-name yolo26n-pose
```

Export LiteRT/TFLite:
```bash
MPLCONFIGDIR=/tmp/matplotlib-yolo-litert-export \
  /home/tamnv/Downloads/python_exp/.venv/bin/python \
  tools/export_yolo26_pose_litert.py \
  --model yolo26n-pose.pt \
  --assets-dir app/src/main/assets \
  --output-name yolo26n-pose
```

Expected outputs:
```text
app/src/main/assets/yolo26n-pose.pte
app/src/main/assets/yolo26n-pose.tflite
app/src/main/assets/yolo26n-pose-metadata.yaml
```

## How It Works
### Live camera mode
`MainActivity` requests camera permission, binds `PreviewView` plus `ImageAnalysis`, and streams frames into the selected analyzer:
- `PoseAnalyzer` for MediaPipe
- `YoloPoseAnalyzer` for YOLO26 through ExecuTorch or LiteRT

### Video mode
The picker returns a local `content://` URI. `VideoPoseProcessor` copies or opens the video as a seekable source, decodes frames, normalizes them to `ARGB_8888`, and runs pose detection with the selected backend.

YOLO26 outputs COCO 17-keypoint pose data. `YoloPoseDetector` maps those points into the app's 33-landmark pose format so the same overlay and safety rules work for both backends.

### Safety evaluation
`SafetyLogic` calculates posture angles and classifies each frame as:
- `SAFE`
- `WARNING`
- `UNSAFE`

The UI only updates the displayed safety state after the same level persists for 8 consecutive frames to reduce flicker.

## Troubleshooting
- If a selected video fails to load, the file may be using an unsupported container or codec. MP4 with H.264 video is the safest format.
- If the app installs on one device but not another, check ABI support. The project ships ABI split debug APKs.
- If YOLO26 is unavailable, check that either `yolo26n-pose.pte` or `yolo26n-pose.tflite` is bundled.
- If camera permission is denied, use the permission screen to grant it in Settings or choose a video instead.
- If pose results look misaligned, verify the video rotation metadata and the selected camera mode.

## Privacy
- No network calls are made by the app.
- No user accounts, databases, or analytics are included.
- Camera frames and selected video content stay on-device.
