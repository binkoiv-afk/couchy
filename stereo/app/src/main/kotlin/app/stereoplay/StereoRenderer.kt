package app.stereoplay

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Takes decoded video frames from a SurfaceTexture and draws them as a stereo pair.
 * Per frame: shrink the frame for the depth network (and hand it over when the network is
 * free), upsample the latest depth map guided by the full frame, then draw each eye.
 */
class StereoRenderer(
    private val engine: DepthEngine,
    private val onSurface: (Surface) -> Unit,
    private val requestRender: () -> Unit,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var params = StereoParams()
    /** Display size of the video (pixel aspect applied). */
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    /** Frames drawn per second, smoothed. */
    @Volatile var fps = 0f; private set

    private var surfaceTexture: SurfaceTexture? = null
    private val frameReady = AtomicBoolean(false)
    private val st = FloatArray(16)
    private var oes = 0
    private var quad = 0
    private var smallProg = 0
    private var upProg = 0
    private var outProg = 0
    private var viewW = 0
    private var viewH = 0

    private val small = Target()
    private val full = Target()
    private var lowTex = 0
    private var lowW = 0
    private var lowH = 0
    private var hasDepth = false
    private var lastFrame = 0L
    private val dropDepth = AtomicBoolean(false)

    /** Stop showing the previous video's depth until the new one has its own. */
    fun resetDepth() = dropDepth.set(true)

    private class Target {
        var fbo = 0
        var tex = 0
        var w = 0
        var h = 0
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        small.w = 0; full.w = 0; lowW = 0; hasDepth = false
        oes = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
        surfaceTexture?.release()
        val texture = SurfaceTexture(oes).also { it.setOnFrameAvailableListener(this) }
        surfaceTexture = texture
        onSurface(Surface(texture))
        Matrix.setIdentityM(st, 0)

        smallProg = program(Shaders.VERTEX, Shaders.SMALL)
        upProg = program(Shaders.VERTEX, Shaders.UPSAMPLE)
        outProg = program(Shaders.VERTEX, Shaders.OUTPUT)

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(verts).rewind()
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quad = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES30.GL_STATIC_DRAW)

        lowTex = genTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onFrameAvailable(texture: SurfaceTexture) {
        frameReady.set(true)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture ?: return
        var newFrame = false
        if (frameReady.getAndSet(false)) {
            try {
                texture.updateTexImage()
                texture.getTransformMatrix(st)
                newFrame = true
                countFrame()
            } catch (t: Throwable) {
                Log.w(TAG, "updateTexImage", t)
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val vw = videoWidth
        val vh = videoHeight
        if (vw <= 0 || vh <= 0) return
        val p = params
        val aspect = vw.toFloat() / vh

        // Network input: short side from the chosen detail, both sides multiples of 14 (ViT patches).
        val short = p.detail.shortSide
        val (mw, mh) = if (aspect >= 1f) {
            patch(short * min(aspect, 2.4f)) to short
        } else {
            short to patch(short / max(aspect, 1f / 2.4f))
        }
        if (ensureTarget(small, mw, mh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)) newFrame = true
        val fw = min(vw, 1280)
        val fh = max(1, (fw / aspect).roundToInt())
        ensureTarget(full, fw, fh, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE)

        if (dropDepth.getAndSet(false)) {
            hasDepth = false
            engine.poll()
        }
        engine.poll()?.let { uploadDepth(it) }

        if (newFrame && p.needsDepth) {
            drawSmall()
            if (engine.canAccept()) {
                val buf = engine.inputBuffer(mw, mh)
                GLES30.glReadPixels(0, 0, mw, mh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                engine.submit(mw, mh)
            }
        }
        if (hasDepth && p.needsDepth) drawUpsample()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        drawOutput(p, aspect)
    }

    private fun drawSmall() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, small.fbo)
        GLES30.glViewport(0, 0, small.w, small.h)
        GLES30.glUseProgram(smallProg)
        bindVideo(smallProg)
        GLES30.glUniform2f(loc(smallProg, "uTexel"), 1f / small.w, 1f / small.h)
        drawQuad(smallProg)
    }

    private fun drawUpsample() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, full.fbo)
        GLES30.glViewport(0, 0, full.w, full.h)
        GLES30.glUseProgram(upProg)
        bindVideo(upProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lowTex)
        GLES30.glUniform1i(loc(upProg, "uDepth"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, small.tex)
        GLES30.glUniform1i(loc(upProg, "uSmall"), 2)
        GLES30.glUniform2f(loc(upProg, "uLow"), lowW.toFloat(), lowH.toFloat())
        GLES30.glUniform1f(loc(upProg, "uColorSigma"), 40f)
        drawQuad(upProg)
    }

    private fun drawOutput(p: StereoParams, aspect: Float) {
        GLES30.glUseProgram(outProg)
        bindVideo(outProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, full.tex)
        GLES30.glUniform1i(loc(outProg, "uDepthFull"), 1)
        val depthReady = hasDepth && p.needsDepth
        val half = if (depthReady) p.strength * 0.5f else 0f
        GLES30.glUniform1f(loc(outProg, "uHalf"), half)
        GLES30.glUniform1f(loc(outProg, "uConv"), 1f - p.popout)
        val left = if (p.swapEyes) -1f else 1f

        fun eye(x: Int, y: Int, w: Int, h: Int, mode: Int, sign: Float) {
            if (w <= 0 || h <= 0) return
            GLES30.glViewport(x, y, w, h)
            GLES30.glUniform1i(loc(outProg, "uMode"), mode)
            GLES30.glUniform1f(loc(outProg, "uEye"), sign)
            // About one search step per 1.5 output pixels across the shift range.
            val steps = ceil(2f * half * w / 1.5f).toInt().coerceIn(8, 64)
            GLES30.glUniform1i(loc(outProg, "uSteps"), steps)
            drawQuad(outProg)
        }

        when (p.layout) {
            Layout.VR -> {
                val hw = viewW / 2
                val l = fit(aspect, 0, 0, hw, viewH)
                val r = fit(aspect, hw, 0, viewW - hw, viewH)
                eye(l[0], l[1], l[2], l[3], 0, left)
                eye(r[0], r[1], r[2], r[3], 0, -left)
            }
            Layout.SBS_HALF -> {
                val f = fit(aspect, 0, 0, viewW, viewH)
                val hw = f[2] / 2
                eye(f[0], f[1], hw, f[3], 0, left)
                eye(f[0] + hw, f[1], f[2] - hw, f[3], 0, -left)
            }
            Layout.TAB -> {
                val f = fit(aspect, 0, 0, viewW, viewH)
                val hh = f[3] / 2
                eye(f[0], f[1] + f[3] - hh, f[2], hh, 0, left)
                eye(f[0], f[1], f[2], f[3] - hh, 0, -left)
            }
            Layout.ANAGLYPH -> {
                val f = fit(aspect, 0, 0, viewW, viewH)
                eye(f[0], f[1], f[2], f[3], 1, left)
            }
            Layout.FLAT -> {
                val f = fit(aspect, 0, 0, viewW, viewH)
                eye(f[0], f[1], f[2], f[3], 2, left)
            }
            Layout.DEPTH -> {
                val f = fit(aspect, 0, 0, viewW, viewH)
                eye(f[0], f[1], f[2], f[3], if (hasDepth) 3 else 2, left)
            }
        }
    }

    private fun uploadDepth(d: DepthFrame) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lowTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, d.width, d.height, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, d.data,
        )
        lowW = d.width
        lowH = d.height
        hasDepth = true
    }

    private fun bindVideo(prog: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GLES30.glUniform1i(loc(prog, "uVideo"), 0)
        GLES30.glUniformMatrix4fv(loc(prog, "uSt"), 1, false, st, 0)
    }

    private fun drawQuad(prog: Int) {
        val a = GLES30.glGetAttribLocation(prog, "aPos")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glEnableVertexAttribArray(a)
        GLES30.glVertexAttribPointer(a, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** (Re)creates a render target when its size changes; true if it did. */
    private fun ensureTarget(t: Target, w: Int, h: Int, internal: Int, format: Int, type: Int): Boolean {
        if (t.w == w && t.h == h && t.fbo != 0) return false
        if (t.fbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            t.fbo = ids[0]
            t.tex = genTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t.tex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        t.w = w
        t.h = h
        return true
    }

    private fun countFrame() {
        val now = SystemClock.elapsedRealtime()
        if (lastFrame > 0) {
            val inst = 1000f / max(1L, now - lastFrame)
            fps = if (fps == 0f) inst else fps * 0.9f + inst * 0.1f
        }
        lastFrame = now
    }

    private val locations = HashMap<Long, Int>()

    private fun loc(prog: Int, name: String): Int {
        val key = (prog.toLong() shl 32) or (name.hashCode().toLong() and 0xffffffffL)
        return locations.getOrPut(key) { GLES30.glGetUniformLocation(prog, name) }
    }

    private fun genTexture(target: Int, filter: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(target, ids[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun program(vs: String, fs: String): Int {
        locations.clear()
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private companion object {
        const val TAG = "StereoRenderer"

        fun patch(v: Float): Int = max(14, (v / 14f).roundToInt() * 14)

        /** The largest rectangle of the given aspect centred in the region: x, y, w, h. */
        fun fit(aspect: Float, x: Int, y: Int, w: Int, h: Int): IntArray {
            var fw = w
            var fh = (w / aspect).roundToInt()
            if (fh > h) {
                fh = h
                fw = (h * aspect).roundToInt()
            }
            return intArrayOf(x + (w - fw) / 2, y + (h - fh) / 2, fw, fh)
        }
    }
}
