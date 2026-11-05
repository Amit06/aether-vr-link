package com.example.aethervr

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val glRenderer = AetherGlRenderer()

    // Rotation Matrix & Orientation Buffers
    private val rotMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val quaternion = FloatArray(4)

    // Network & WebSocket State
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var vrWebSocket: WebSocket? = null
    private var streamWebSocket: WebSocket? = null
    private var isShuttingDown = false
    private var lastImuSendNs = 0L

    // UI Telemetry State (Observable by Compose Stereoscopic HUD)
    private var serverIp by mutableStateOf("127.0.0.1")
    private var serverPort by mutableStateOf(8780)
    private var isConnected by mutableStateOf(false)
    private var imuHz by mutableStateOf(0f)
    private var relYaw by mutableStateOf(0f)
    private var relPitch by mutableStateOf(0f)
    private var relRoll by mutableStateOf(0f)
    private var dwellProgress by mutableStateOf(0f)
    private var clickMode by mutableStateOf("LEFT")
    private var totalClicks by mutableStateOf(0)
    private var showConfigModal by mutableStateOf(false)
    private var nativeVrStatus by mutableStateOf("VR SERVICE ACTIVE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Wake screen over lockscreen & keep screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Initialize Hardware IMU Sensor (GameRotationVector = Gyro + Accel fusion, immune to magnetic interference)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // 3. Render Compose UI + OpenGL Surface first so DecorView is initialized
        setContent {
            AetherVrHeadsetScreen()
        }

        // 4. Engage Native Android VR Services & Immersive Fullscreen after DecorView is attached
        window.decorView.post {
            engageNativeAndroidVrServices()
        }

        // 5. Connect to PC Server (Supports both ADB Reverse 127.0.0.1 and LAN Wi-Fi IP)
        connectWebSockets()
    }

    private fun engageNativeAndroidVrServices() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isSustainedPerformanceModeSupported) {
                window.setSustainedPerformanceMode(true)
            }
            val vrComponent = ComponentName(this, AetherVrListenerService::class.java)
            setVrModeEnabled(true, vrComponent)
            nativeVrStatus = "NATIVE VR SERVICE + FAST IMU"
        } catch (e: Exception) {
            nativeVrStatus = "HARDWARE FAST IMU (120Hz)"
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun connectWebSockets() {
        if (isShuttingDown) return
        vrWebSocket?.cancel()
        streamWebSocket?.cancel()

        val baseUrl = "ws://$serverIp:$serverPort"

        // 1. High-Frequency IMU & Telemetry Channel
        val vrReq = Request.Builder().url("$baseUrl/ws/vr").build()
        vrWebSocket = okHttpClient.newWebSocket(vrReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    val type = obj.optString("type")
                    if (type == "telemetry") {
                        val dwell = obj.optDouble("dwell", 0.0).toFloat()
                        val clicked = obj.optBoolean("clicked", false)
                        dwellProgress = dwell
                        glRenderer.dwellProgress = dwell
                        relYaw = obj.optDouble("yaw", 0.0).toFloat()
                        relPitch = obj.optDouble("pitch", 0.0).toFloat()
                        relRoll = obj.optDouble("roll", 0.0).toFloat()
                        imuHz = obj.optDouble("hz", 0.0).toFloat()
                        clickMode = obj.optString("clickMode", "left").uppercase()
                        totalClicks = obj.optInt("totalClicks", 0)

                        glRenderer.screenDistance = obj.optDouble("dist", 1.25).toFloat()
                        glRenderer.screenCurvature = obj.optDouble("curv", 0.28).toFloat()
                        glRenderer.ipdOffset = obj.optDouble("ipd", 0.062).toFloat()
                        glRenderer.gazeFollowEnabled = obj.optBoolean("follow", true)

                        if (clicked) {
                            triggerHapticPulse()
                        }
                    } else if (type == "reconnect_stream") {
                        connectStreamOnly()
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                scheduleReconnect()
            }
        })

        // 2. Binary Desktop Frame Stream Channel
        val streamReq = Request.Builder().url("$baseUrl/ws/stream").build()
        streamWebSocket = okHttpClient.newWebSocket(streamReq, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val byteArray = bytes.toByteArray()
                val bmp = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                if (bmp != null) {
                    glRenderer.updateDesktopBitmap(bmp)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                window.decorView.postDelayed({
                    if (!isShuttingDown) {
                        connectStreamOnly()
                    }
                }, 1500)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                window.decorView.postDelayed({
                    if (!isShuttingDown) {
                        connectStreamOnly()
                    }
                }, 1500)
            }
        })
    }

    private fun connectStreamOnly() {
        if (isShuttingDown) return
        streamWebSocket?.cancel()
        val baseUrl = "ws://$serverIp:$serverPort"
        val streamReq = Request.Builder().url("$baseUrl/ws/stream").build()
        streamWebSocket = okHttpClient.newWebSocket(streamReq, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val byteArray = bytes.toByteArray()
                val bmp = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                if (bmp != null) {
                    glRenderer.updateDesktopBitmap(bmp)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                window.decorView.postDelayed({
                    if (!isShuttingDown) connectStreamOnly()
                }, 1500)
            }
        })
    }

    private fun scheduleReconnect() {
        window.decorView.postDelayed({
            if (!isConnected && !isShuttingDown) {
                if (serverIp == "192.168.1.5") {
                    serverIp = "127.0.0.1"
                } else if (serverIp == "127.0.0.1") {
                    serverIp = "192.168.1.5"
                }
                connectWebSockets()
            }
        }, 1200)
    }

    private fun triggerHapticPulse() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(28)
            }
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            // SENSOR_DELAY_FASTEST for ultra-low latency head tracking
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        isShuttingDown = true
        vrWebSocket?.cancel()
        streamWebSocket?.cancel()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_ROTATION_VECTOR
        ) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getQuaternionFromVector(quaternion, event.values)

            // Remap coordinate system for landscape phone mounted inside a VR headset visor:
            // Phone X-axis points right, Phone Y-axis points up/sideways depending on landscape orientation
            SensorManager.remapCoordinateSystem(
                rotMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )
            SensorManager.getOrientation(remappedMatrix, orientationAngles)

            val yawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

            glRenderer.headYawDeg = relYaw
            glRenderer.headPitchDeg = relPitch
            glRenderer.headRollDeg = relRoll

            // Stream at ~100Hz (every 10ms) to avoid saturating Wi-Fi queue while maintaining instant response
            val nowNs = System.nanoTime()
            if (nowNs - lastImuSendNs >= 9_500_000L && isConnected) {
                lastImuSendNs = nowNs
                val payload = JSONObject().apply {
                    put("type", "imu")
                    put("yaw", yawDeg)
                    put("pitch", pitchDeg)
                    put("roll", rollDeg)
                    put("q", JSONArray().apply {
                        put(quaternion[1])
                        put(quaternion[2])
                        put(quaternion[3])
                        put(quaternion[0])
                    })
                    put("device", "${Build.MODEL} (${nativeVrStatus})")
                }
                vrWebSocket?.send(payload.toString())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Hardware Volume Buttons work even when phone is sealed inside the VR visor!
    // Volume Up = Recenter Head Pose | Volume Down = Instant Manual Click
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                sendVrCommand("recenter")
                triggerHapticPulse()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                sendVrCommand("click")
                triggerHapticPulse()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun sendVrCommand(cmdType: String) {
        val obj = JSONObject().apply { put("type", cmdType) }
        vrWebSocket?.send(obj.toString())
    }

    // =========================================================================
    // COMPOSABLE STEREOSCOPIC SIDE-BY-SIDE HUD OVERLAY + OPENGLES SURFACE
    // =========================================================================

    @Composable
    private fun AetherVrHeadsetScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020409))
                .clickable {
                    // Single screen tap recenters head orientation
                    sendVrCommand("recenter")
                    triggerHapticPulse()
                }
        ) {
            // 1. Native OpenGL ES 2.0 Stereoscopic Curved IMAX Renderer
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        setRenderer(glRenderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Stereoscopic Side-by-Side (SBS) Spatial HUD Overlay (Left Eye + Right Eye)
            Row(modifier = Modifier.fillMaxSize()) {
                EyeHudOverlay(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                // Center Stereoscopic Divider Line
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(Color(0x4400F0FF))
                )
                EyeHudOverlay(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            // 3. Discreet Corner Gear Button to edit Server IP if needed
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color(0xAA0A1224), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x5500F0FF), RoundedCornerShape(8.dp))
                    .clickable { showConfigModal = !showConfigModal }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isConnected) "LINK: $serverIp" else "CONNECTING...",
                    color = if (isConnected) Color(0xFF00FFA3) else Color(0xFFFFB800),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showConfigModal) {
                ServerConnectionDialog(
                    currentIp = serverIp,
                    onDismiss = { showConfigModal = false },
                    onConnect = { newIp ->
                        serverIp = newIp.trim()
                        showConfigModal = false
                        connectWebSockets()
                    }
                )
            }
        }
    }

    @Composable
    private fun EyeHudOverlay(modifier: Modifier = Modifier) {
        Box(modifier = modifier.padding(vertical = 12.dp, horizontal = 16.dp)) {
            // Top Telemetry Pill inside each eye
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(Color(0xB8070D1B), RoundedCornerShape(50))
                    .border(1.dp, Color(0x5500F0FF), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (isConnected) Color(0xFF00FFA3) else Color(0xFFFFB800),
                            CircleShape
                        )
                )
                Text(
                    text = "YAW ${relYaw.roundToInt()}°  PITCH ${relPitch.roundToInt()}°  |  ${imuHz.roundToInt()}Hz",
                    color = Color(0xFF00F0FF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Center Stereoscopic Gaze Reticle + Dwell Progress Ring
            // Auto-hides when Dwell is 0 so you have an unobstructed cinema view!
            if (dwellProgress > 0.02f) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color(0x5500F0FF),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawArc(
                            color = Color(0xFF00FFA3),
                            startAngle = -90f,
                            sweepAngle = 360f * dwellProgress.coerceIn(0f, 1f),
                            useCenter = false,
                            style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                        drawCircle(
                            color = Color(0xFF00F0FF),
                            radius = 2.5.dp.toPx()
                        )
                    }
                }
            }

            // Bottom Dwell Status & Quick Controls Pill
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color(0xB8070D1B), RoundedCornerShape(50))
                    .border(1.dp, Color(0x4400FFA3), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "DWELL: ${(dwellProgress * 100).roundToInt()}%  |  MODE: $clickMode  |  VOL+ RECENTER",
                    color = Color(0xFFE2E8F0),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    @Composable
    private fun ServerConnectionDialog(
        currentIp: String,
        onDismiss: () -> Unit,
        onConnect: (String) -> Unit
    ) {
        var ipInput by remember { mutableStateOf(currentIp) }
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF0B1326),
            titleContentColor = Color(0xFF00F0FF),
            textContentColor = Color.White,
            title = { Text("AETHER-VR // PC SERVER LINK", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter PC LAN IP (or 127.0.0.1 for ADB tunnel):", fontSize = 12.sp)
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = { onConnect(ipInput) }) {
                    Text("CONNECT")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    sendVrCommand("toggle_mode")
                    onDismiss()
                }) {
                    Text("CYCLE CLICK MODE")
                }
            }
        )
    }
}
