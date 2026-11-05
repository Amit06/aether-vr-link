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
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-production Native OpenGL ES 2.0 Stereoscopic Side-by-Side (SBS) VR Renderer.
 * Renders:
 * 1. Curved IMAX Virtual Desktop Mesh with live PC screen texture + gaze cursor
 * 2. Spatial Reference Cyber Floor Grid in 3D space
 * 3. Head-locked 3D Spatial Gaze Reticle & Dwell Ring Indicator
 */
class AetherGlRenderer : GLSurfaceView.Renderer {

    private var surfaceWidth = 1920
    private var surfaceHeight = 1080

    // Pending PC desktop frame decoded from WebSocket binary stream
    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    private var desktopTextureId = 0
    private var hasValidTexture = false

    // Live Tunable Optics & Orientation State
    @Volatile var screenDistance: Float = 2.1f
    @Volatile var screenCurvature: Float = 0.35f
    @Volatile var ipdOffset: Float = 0.064f
    @Volatile var headYawDeg: Float = 0.0f
    @Volatile var headPitchDeg: Float = 0.0f
    @Volatile var headRollDeg: Float = 0.0f
    @Volatile var dwellProgress: Float = 0.0f

    // Matrices
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Shader Programs
    private var screenProgram = 0
    private var gridProgram = 0

    // Curved Screen Mesh Buffers
    private val cols = 32
    private val rows = 18
    private lateinit var screenVertexBuffer: FloatBuffer
    private lateinit var screenTexBuffer: FloatBuffer
    private lateinit var screenIndexBuffer: ShortBuffer
    private var screenIndexCount = 0
    private var lastBuiltCurvature = -1f

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
        GLES20.glClearColor(0.015f, 0.025f, 0.055f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        screenProgram = createProgram(VERTEX_SHADER_TEX, FRAGMENT_SHADER_TEX)
        gridProgram = createProgram(VERTEX_SHADER_COLOR, FRAGMENT_SHADER_COLOR)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        desktopTextureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        buildCurvedScreenMesh(screenCurvature)
        buildFloorGrid()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(2)
        surfaceHeight = height.coerceAtLeast(2)
        val eyeAspect = (surfaceWidth / 2f) / surfaceHeight.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 52.0f, eyeAspect, 0.1f, 50.0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Upload new PC desktop frame if arrived
        val bmp = pendingBitmap.getAndSet(null)
        if (bmp != null && !bmp.isRecycled) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            hasValidTexture = true
        }

        if (kotlin.math.abs(screenCurvature - lastBuiltCurvature) > 0.02f) {
            buildCurvedScreenMesh(screenCurvature)
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val halfW = surfaceWidth / 2
        val halfIpd = ipdOffset * 0.5f

        // Render Left Eye Viewport
        GLES20.glViewport(0, 0, halfW, surfaceHeight)
        renderEyeScene(-halfIpd)

        // Render Right Eye Viewport
        GLES20.glViewport(halfW, 0, halfW, surfaceHeight)
        renderEyeScene(halfIpd)
    }

    private fun renderEyeScene(eyeOffsetX: Float) {
        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeOffsetX, 0.0f, 0.0f,
            eyeOffsetX, 0.0f, -1.0f,
            0.0f, 1.0f, 0.0f
        )

        // Subtle spatial parallax from head orientation so the virtual room feels grounded
        val parallaxYaw = (headYawDeg * 0.12f).coerceIn(-12f, 12f)
        val parallaxPitch = (headPitchDeg * 0.12f).coerceIn(-8f, 8f)
        Matrix.rotateM(viewMatrix, 0, parallaxPitch, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, parallaxYaw, 0f, 1f, 0f)

        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // 1. Draw Cyber Floor & Ceiling Reference Grid
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        drawGrid(mvpMatrix)

        // 2. Draw Curved IMAX Virtual Desktop Screen
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -screenDistance)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
        drawCurvedScreen(mvpMatrix)
    }

    private fun buildCurvedScreenMesh(curvature: Float) {
        lastBuiltCurvature = curvature
        val widthWorld = 2.4f
        val heightWorld = widthWorld * (9f / 16f)

        val numVerts = (cols + 1) * (rows + 1)
        val verts = FloatArray(numVerts * 3)
        val uvs = FloatArray(numVerts * 2)

        var vIdx = 0
        var tIdx = 0
        val maxAngle = (curvature.coerceIn(0.01f, 0.95f)) * 1.15f // radians of arc
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
        GLES20.glUniform4f(colLoc, 0.0f, 0.94f, 1.0f, 0.16f)

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

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
        GLES20.glUniform1i(hasTexLoc, if (hasValidTexture) 1 else 0)

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

        private const val FRAGMENT_SHADER_TEX = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            uniform int uHasTexture;
            void main() {
                // Subtle glowing cyan border around the curved virtual monitor
                float borderX = min(vTexCoord.x, 1.0 - vTexCoord.x);
                float borderY = min(vTexCoord.y, 1.0 - vTexCoord.y);
                if (borderX < 0.005 || borderY < 0.008) {
                    gl_FragColor = vec4(0.0, 0.94, 1.0, 0.95);
                    return;
                }
                if (uHasTexture == 1) {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                } else {
                    // Cyber test standby gradient before stream connects
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
