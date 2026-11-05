# AETHER-VR // Spatial Head-Tracking & Virtual Desktop Engine

A high-production **Native Android VR Headset + PC Spatial Control Engine** that turns an Android phone mounted in a VR headset (even with a dark visor blocking the camera) into:
1. **120Hz Hardware IMU Head-Tracking OS Mouse (`TYPE_GAME_ROTATION_VECTOR`)** with a **1-Euro Adaptive Filter** and **Hands-Free Dwell-to-Click**.
2. **Stereoscopic Side-by-Side (SBS) OpenGL ES 2.0 Curved IMAX Virtual Desktop** streaming your PC monitors (including a dedicated **3rd Virtual Extended Monitor** via the Parsec Virtual Display Adapter IOCTL interface).
3. **PC Spatial Command Center Dashboard** (`http://localhost:8780/`) with a live Three.js 3D headset pose visualizer, gaze radar, dwell sandbox, and real-time optics/sensitivity tuning.

---

## Architecture

- **`android-app/`** — Native Kotlin + Jetpack Compose + OpenGL ES 2.0 Android VR client:
  - [`AetherVrListenerService.kt`](android-app/app/src/main/kotlin/com/example/aethervr/AetherVrListenerService.kt): Native Android `android.service.vr.VrListenerService` + `setSustainedPerformanceMode(true)`.
  - [`AetherGlRenderer.kt`](android-app/app/src/main/kotlin/com/example/aethervr/AetherGlRenderer.kt): Stereoscopic SBS OpenGL ES 2.0 curved IMAX virtual monitor renderer + spatial floor grid.
  - [`MainActivity.kt`](android-app/app/src/main/kotlin/com/example/aethervr/MainActivity.kt): `SENSOR_DELAY_FASTEST` hardware IMU fusion (`TYPE_GAME_ROTATION_VECTOR`), dual-eye HUD telemetry, volume-button hardware shortcuts (`Vol+` Recenter, `Vol-` Click), and haptic feedback on dwell-click.
- **`pc-server/`** — FastAPI + WebSockets + Windows Native `SendInput` & Parsec VDD Controller:
  - [`server.py`](pc-server/server.py): 1-Euro Filter head-mouse mapper, dwell-to-click state machine, multi-monitor `OpenInputDesktop` screen streamer, and real-time telemetry API.
  - [`parsec_vdd.py`](pc-server/parsec_vdd.py): Native Win32 `DeviceIoControl` controller for `Root\Parsec\VDA` (`VDD_IOCTL_ADD`, `VDD_IOCTL_UPDATE`, `VDD_IOCTL_REMOVE`) to dynamically plug in a real 3rd `1920x1080` extended Windows monitor.
  - [`static/index.html`](pc-server/static/index.html): High-production PC Command Center UI.

---

## Quick Start

### 1. Start the PC Server
```powershell
cd pc-server
pip install fastapi uvicorn websockets mss pillow pywin32 psutil
python server.py
```
Open **http://localhost:8780/** in your PC browser to view the **AetherVR Command Center**.

### 2. Build & Run the Native Android VR App
```powershell
cd android-app
.\gradlew.bat assembleDebug
adb reverse tcp:8780 tcp:8780
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.aethervr/.MainActivity
```

### 3. Controls Inside the VR Visor
- **Move Head (Yaw / Pitch)**: Smoothly controls the Windows OS mouse cursor.
- **Hold Gaze Still (~0.75s)**: Fills the emerald dwell ring and triggers a mouse click (with phone haptic pulse).
- **Volume Up / Screen Tap / `Space` on PC**: Recenters head orientation.
- **Volume Down**: Instant manual mouse click.
