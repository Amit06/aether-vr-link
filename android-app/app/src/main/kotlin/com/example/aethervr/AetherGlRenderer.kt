package com.example.aethervr

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-Clarity Native OpenGL ES 2.0 Stereoscopic Side-by-Side (SBS) VR Renderer.
 * Engineered for maximum text readability and pixel density:
 * 1. Dynamic Aspect-Ratio Curved IMAX Screen (supports 21:9 Ultrawide 3440x1440 & 16:9 1920x1080)
 * 2. GLSL Contrast-Adaptive Unsharp Mask (CAS) Shader for crisp text & UI edges in VR lenses
 * 3. Smooth Gaze-Follow Panning so the screen pans toward your gaze at close viewing distances
 */
class AetherGlRenderer : GLSurfaceView.Renderer {

    private var surfaceWidth = 2400
    private var surfaceHeight = 1080

    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    private var desktopTextureId = 0
    private var hasValidTexture = false
    private var texWidth = 1920f
    private var texHeight = 1080f

    // Live Tunable Optics & Orientation State
    @Volatile var screenDistance: Float = 1.25f
    @Volatile var screenCurvature: Float = 0.28f
    @Volatile var ipdOffset: Float = 0.062f
    @Volatile var screenAspect: Float = 1.777f
    @Volatile var gazeFollowEnabled: Boolean = true
    @Volatile var headYawDeg: Float = 0.0f
    @Volatile var headPitchDeg: Float = 0.0f
    @Volatile var headRollDeg: Float = 0.0f
    @Volatile var dwellProgress: Float = 0.0f

    // Smoothed camera gaze-follow angles
    private var smoothPanYaw = 0f
    private var smoothPanPitch = 0f

    // Matrices
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Shader Programs
    private var screenProgram = 0
    private var gridProgram = 0

    // High-Density Curved Screen Mesh Buffers (48x24 grid for ultra-smooth IMAX curve)
    private val cols = 48
    private val rows = 24
    private lateinit var screenVertexBuffer: FloatBuffer
    private lateinit var screenTexBuffer: FloatBuffer
    private lateinit var screenIndexBuffer: ShortBuffer
    private var screenIndexCount = 0
    private var lastBuiltCurvature = -1f
    private var lastBuiltAspect = -1f

    // Spatial Cyber Grid Buffer
    private lateinit var gridVertexBuffer: FloatBuffer
    private var gridVertexCount = 0

    fun updateDesktopBitmap(bmp: Bitmap) {
        val old = pendingBitmap.getAndSet(bmp)
        if (old != null && old != bmp && !old.isRecycled) {
            old.recycle()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Pure black cinema void background (removed cyber grid floor lines & cyan border)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        screenProgram = createProgram(VERTEX_SHADER_TEX, FRAGMENT_SHADER_SHARP_TEX)
        gridProgram = createProgram(VERTEX_SHADER_COLOR, FRAGMENT_SHADER_COLOR)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        desktopTextureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        buildCurvedScreenMesh(screenCurvature, screenAspect)
        buildFloorGrid()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(2)
        surfaceHeight = height.coerceAtLeast(2)
        val eyeAspect = (surfaceWidth / 2f) / surfaceHeight.toFloat()
        // 48-degree vertical FOV maximizes pixels-per-degree (PPD) in phone VR optics
        Matrix.perspectiveM(projMatrix, 0, 48.0f, eyeAspect, 0.1f, 50.0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val bmp = pendingBitmap.getAndSet(null)
        if (bmp != null && !bmp.isRecycled) {
            texWidth = bmp.width.toFloat().coerceAtLeast(1f)
            texHeight = bmp.height.toFloat().coerceAtLeast(1f)
            val newAspect = (texWidth / texHeight).coerceIn(1.2f, 3.0f)
            screenAspect = newAspect

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            hasValidTexture = true
        }

        if (abs(screenCurvature - lastBuiltCurvature) > 0.02f || abs(screenAspect - lastBuiltAspect) > 0.03f) {
            buildCurvedScreenMesh(screenCurvature, screenAspect)
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val halfW = surfaceWidth / 2
        val halfIpd = ipdOffset * 0.5f

        // Smoothly interpolate gaze-follow camera pan so looking left/right brings that edge of the large monitor into the optical sweet spot!
        val targetYaw = if (gazeFollowEnabled) (headYawDeg * 0.42f).coerceIn(-22f, 22f) else 0f
        val targetPitch = if (gazeFollowEnabled) (headPitchDeg * 0.35f).coerceIn(-14f, 14f) else 0f
        smoothPanYaw += (targetYaw - smoothPanYaw) * 0.18f
        smoothPanPitch += (targetPitch - smoothPanPitch) * 0.18f

        // Left Eye Viewport
        GLES20.glViewport(0, 0, halfW, surfaceHeight)
        renderEyeScene(-halfIpd)

        // Right Eye Viewport
        GLES20.glViewport(halfW, 0, halfW, surfaceHeight)
        renderEyeScene(halfIpd)
    }

    private fun renderEyeScene(eyeOffsetX: Float) {
        // Converge both eyes onto the center of the virtual monitor at (0, 0, -screenDistance)
        // so the cursor and screen fuse into a single razor-sharp image without double vision!
        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeOffsetX, 0.0f, 0.0f,
            0.0f, 0.0f, -screenDistance,
            0.0f, 1.0f, 0.0f
        )

        Matrix.rotateM(viewMatrix, 0, smoothPanPitch, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, smoothPanYaw, 0f, 1f, 0f)

        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // Draw High-Resolution Curved IMAX Virtual Monitor (without distracting grid lines)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -screenDistance)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        drawCurvedScreen(mvpMatrix)
    }

    private fun buildCurvedScreenMesh(curvature: Float, aspect: Float) {
        lastBuiltCurvature = curvature
        lastBuiltAspect = aspect

        // Base height 1.32m; width scales with monitor aspect ratio (e.g., 2.35m for 16:9 or 3.15m for 21:9 ultrawide)
        val heightWorld = 1.32f
        val widthWorld = heightWorld * aspect.coerceIn(1.3f, 2.5f)

        val numVerts = (cols + 1) * (rows + 1)
        val verts = FloatArray(numVerts * 3)
        val uvs = FloatArray(numVerts * 2)

        var vIdx = 0
        var tIdx = 0
        val maxAngle = (curvature.coerceIn(0.01f, 0.95f)) * 1.25f
        val radius = widthWorld / maxAngle

        for (r in 0..rows) {
            val v = r.toFloat() / rows
            val y = (0.5f - v) * heightWorld
            for (c in 0..cols) {
                val u = c.toFloat() / cols
                val nx = u - 0.5f
                val x: Float
                val z: Float
                if (curvature < 0.03f) {
                    x = nx * widthWorld
                    z = 0f
                } else {
                    val theta = nx * maxAngle
                    x = radius * sin(theta)
                    z = radius * (1f - cos(theta))
                }
                verts[vIdx++] = x
                verts[vIdx++] = y
                verts[vIdx++] = z

                uvs[tIdx++] = u
                uvs[tIdx++] = v
            }
        }

        val indices = ShortArray(cols * rows * 6)
        var iIdx = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val topLeft = (r * (cols + 1) + c).toShort()
                val topRight = (topLeft + 1).toShort()
                val bottomLeft = ((r + 1) * (cols + 1) + c).toShort()
                val bottomRight = (bottomLeft + 1).toShort()

                indices[iIdx++] = topLeft
                indices[iIdx++] = bottomLeft
                indices[iIdx++] = topRight

                indices[iIdx++] = topRight
                indices[iIdx++] = bottomLeft
                indices[iIdx++] = bottomRight
            }
        }
        screenIndexCount = indices.size

        screenVertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        screenTexBuffer = ByteBuffer.allocateDirect(uvs.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(uvs); position(0) }
        screenIndexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
    }

    private fun buildFloorGrid() {
        val lines = ArrayList<Float>()
        val yFloor = -1.15f
        val step = 0.6f
        for (i in -8..8) {
            val x = i * step
            lines.add(x); lines.add(yFloor); lines.add(-0.5f)
            lines.add(x); lines.add(yFloor); lines.add(-7.5f)
        }
        for (j in 1..12) {
            val z = -j * step
            lines.add(-4.8f); lines.add(yFloor); lines.add(z)
            lines.add(4.8f); lines.add(yFloor); lines.add(z)
        }
        val arr = lines.toFloatArray()
        gridVertexCount = arr.size / 3
        gridVertexBuffer = ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(arr); position(0) }
    }

    private fun drawGrid(mvp: FloatArray) {
        GLES20.glUseProgram(gridProgram)
        val posLoc = GLES20.glGetAttribLocation(gridProgram, "aPosition")
        val mvpLoc = GLES20.glGetUniformLocation(gridProgram, "uMVPMatrix")
        val colLoc = GLES20.glGetUniformLocation(gridProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
        GLES20.glUniform4f(colLoc, 0.0f, 0.94f, 1.0f, 0.14f)

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, gridVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertexCount)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun drawCurvedScreen(mvp: FloatArray) {
        GLES20.glUseProgram(screenProgram)
        val posLoc = GLES20.glGetAttribLocation(screenProgram, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(screenProgram, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(screenProgram, "uMVPMatrix")
        val hasTexLoc = GLES20.glGetUniformLocation(screenProgram, "uHasTexture")
        val texelLoc = GLES20.glGetUniformLocation(screenProgram, "uTexelSize")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
        GLES20.glUniform1i(hasTexLoc, if (hasValidTexture) 1 else 0)
        GLES20.glUniform2f(texelLoc, 1.0f / texWidth, 1.0f / texHeight)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopTextureId)

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, screenVertexBuffer)

        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, screenTexBuffer)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, screenIndexCount, GLES20.GL_UNSIGNED_SHORT, screenIndexBuffer)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun createProgram(vtxSrc: String, fragSrc: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vtxSrc)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER_TEX = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // Contrast-Adaptive Unsharp Mask (CAS) Fragment Shader for crisp text & icons inside VR optics
        private const val FRAGMENT_SHADER_SHARP_TEX = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            uniform int uHasTexture;
            uniform vec2 uTexelSize;
            void main() {
                if (uHasTexture == 1) {
                    vec3 center = texture2D(sTexture, vTexCoord).rgb;
                    vec3 north  = texture2D(sTexture, vTexCoord + vec2(0.0, -uTexelSize.y)).rgb;
                    vec3 south  = texture2D(sTexture, vTexCoord + vec2(0.0,  uTexelSize.y)).rgb;
                    vec3 west   = texture2D(sTexture, vTexCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                    vec3 east   = texture2D(sTexture, vTexCoord + vec2( uTexelSize.x, 0.0)).rgb;

                    // Unsharp mask edge enhancement (strength = 0.38)
                    vec3 blur = 0.25 * (north + south + west + east);
                    vec3 sharp = clamp(center + (center - blur) * 0.42, 0.0, 1.0);
                    gl_FragColor = vec4(sharp, 1.0);
                } else {
                    vec3 standby = mix(vec3(0.03, 0.07, 0.16), vec3(0.01, 0.02, 0.05), vTexCoord.y);
                    gl_FragColor = vec4(standby, 0.95);
                }
            }
        """

        private const val VERTEX_SHADER_COLOR = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
            }
        """

        private const val FRAGMENT_SHADER_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
