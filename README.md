# Safety Pose

Android demo app for on-device workplace posture analysis using CameraX and MediaPipe Pose Landmarker.

## Table of Contents
- [Overview](#overview)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Build and Run](#build-and-run)
- [How It Works](#how-it-works)
- [Troubleshooting](#troubleshooting)
- [Privacy](#privacy)

## Overview
Safety Pose is a single-screen Android demo that detects unsafe body posture in real time from the camera or from a selected video file. It runs fully on-device and does not send frames to a server.

## Features
- Live rear or front camera pose estimation
- Local video file analysis through the Android media picker
- Skeleton overlay with posture warnings
- Spine, knee, and shoulder symmetry heuristics
- FPS display and safety state stabilization
- Portrait-only, no navigation flow, no account system

## Tech Stack
- Kotlin
- CameraX
- MediaPipe Pose Landmarker
- Android View system with XML layouts
- Gradle Kotlin DSL

## Project Structure
```text
app/
  src/main/
    java/com/example/safetypose/
      MainActivity.kt
      PoseAnalyzer.kt
      VideoPoseProcessor.kt
      SafetyLogic.kt
      OverlayView.kt
      FpsTracker.kt
    res/
      layout/activity_main.xml
      values/
      values-night/
    AndroidManifest.xml
  build.gradle.kts
gradle/libs.versions.toml
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
4. Connect a device or start an emulator.

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

## How It Works
### Live camera mode
`MainActivity` requests camera permission, binds `PreviewView` plus `ImageAnalysis`, and streams frames into `PoseAnalyzer`.

### Video mode
The picker returns a local `content://` URI. `VideoPoseProcessor` copies or opens the video as a seekable source, decodes frames, normalizes them to `ARGB_8888`, and runs pose detection with MediaPipe `RunningMode.VIDEO`.

### Safety evaluation
`SafetyLogic` calculates posture angles and classifies each frame as:
- `SAFE`
- `WARNING`
- `UNSAFE`

The UI only updates the displayed safety state after the same level persists for 8 consecutive frames to reduce flicker.

## Troubleshooting
- If a selected video fails to load, the file may be using an unsupported container or codec. MP4 with H.264 video is the safest format.
- If the app installs on one device but not another, check ABI support. The project ships ABI split debug APKs.
- If camera permission is denied, use the permission screen to grant it in Settings or choose a video instead.
- If pose results look misaligned, verify the video rotation metadata and the selected camera mode.

## Privacy
- No network calls are made by the app.
- No user accounts, databases, or analytics are included.
- Camera frames and selected video content stay on-device.
