package space.webkombinat.MobileWebCamera.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * カメラ → (OpenGL ES で回転) → エンコーダ Surface。
 * MediaCodec のエンコーダには回転機能が無いため、端末の向きに合わせてここで回す。
 *
 * カメラは [inputSurface] に [inputWidth]x[inputHeight]（センサー向き）で出力する。
 * 90/270° の場合、出力先 (エンコーダ) は縦横を入れ替えたサイズで作っておくこと。
 */
class GlRotator(inputWidth: Int, inputHeight: Int, private val output: Surface) {
    private val thread = HandlerThread("GlRotator").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var uMvp = 0
    private var uTex = 0
    private var aPos = 0
    private var aTex = 0
    private val texMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set

    /** 時計回りの回転 (0/90/180/270)。90↔0 のように縦横が変わる変更はエンコーダごと作り直す */
    @Volatile
    var rotation: Int = 0

    @Volatile
    private var released = false
    private var lastEmbedded = -1

    init {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                setupEgl()
                setupGl()
                surfaceTexture = SurfaceTexture(texId).apply {
                    setDefaultBufferSize(inputWidth, inputHeight)
                    setOnFrameAvailableListener({ drawFrame() }, handler)
                }
                inputSurface = Surface(surfaceTexture)
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure?.let {
            release()
            throw it
        }
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        val config = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun setupGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun drawFrame() {
        if (released) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            // 端末によってはカメラが SurfaceTexture の変換行列に回転を含めてくる
            // (例: Pixel 8 / Android 17 は時計回り 90°、HUAWEI / Android 9 は 0°)。
            // その分を差し引いて、センサー基準で [rotation] だけ回るようにする。
            val embedded = embeddedRotation(texMatrix)
            if (embedded != lastEmbedded) {
                lastEmbedded = embedded
                Log.i(TAG, "SurfaceTexture embedded rotation = $embedded°")
            }
            val draw = (rotation - embedded + 360) % 360
            Matrix.setRotateM(mvpMatrix, 0, -draw.toFloat(), 0f, 0f, 1f)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD_POS)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTexture.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            Log.w(TAG, "drawFrame failed", e)
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            if (::surfaceTexture.isInitialized) runCatching { surfaceTexture.release() }
            if (::inputSurface.isInitialized) runCatching { inputSurface.release() }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "GlRotator"

        /**
         * SurfaceTexture の変換行列に含まれる、表示上の時計回り回転 (0/90/180/270)。
         * 回転なしの行列は s 軸 = (1, 0)（t は上下反転）なので、s 軸の向きから求める。
         */
        fun embeddedRotation(m: FloatArray): Int {
            val deg = Math.toDegrees(kotlin.math.atan2(m[1].toDouble(), m[0].toDouble()))
            val quarter = Math.round(deg / 90.0).toInt()
            return ((-quarter * 90) % 360 + 360) % 360
        }
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTex;
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPos;
                vTex = (uTex * aTex).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        val QUAD_POS: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_TEX: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }

        fun createProgram(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }
    }
}
