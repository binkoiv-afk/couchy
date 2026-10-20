package app.stereoplay

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One depth map, rows bottom-up like a GL texture; 1 is nearest, 0 farthest. */
class DepthFrame(val width: Int, val height: Int, val data: FloatBuffer)

/**
 * Runs Depth Anything V2 on its own thread. The renderer hands it small RGBA frames
 * (read back from the GPU, rows bottom-up) whenever it is free, and picks up finished
 * depth maps on the next frame, so the video itself never waits for the network.
 */
class DepthEngine(private val context: Context) {
    enum class State { IDLE, LOADING, READY, FAILED }

    @Volatile var state = State.IDLE; private set
    @Volatile var error: String? = null; private set
    /** Time of the last inference, ms. */
    @Volatile var lastMs = 0L; private set
    /** Depth maps per second, smoothed. */
    @Volatile var fps = 0f; private set
    /** Called on the depth thread when a new map is ready. */
    @Volatile var onResult: (() -> Unit)? = null

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "depth") }
    private val busy = AtomicBoolean(false)
    private val result = AtomicReference<DepthFrame?>(null)
    private val reset = AtomicBoolean(true)

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName = ""
    @Volatile private var loaded: DepthModel? = null

    private var input: ByteBuffer? = null
    private var chw: FloatBuffer? = null
    private var lastDone = 0L

    // Temporal state, depth thread only.
    private var prev: FloatArray? = null
    private var prevW = 0
    private var prevH = 0
    private var lo = 0f
    private var hi = 1f
    private val luma = FloatArray(LUMA_W * LUMA_H)
    private var hasLuma = false

    fun load(model: DepthModel) {
        if (loaded == model && state != State.FAILED) return
        loaded = model
        busy.set(true)
        state = State.LOADING
        exec.execute {
            try {
                session?.close()
                session = null
                val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
                val file = modelFile(model.file)
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(min(4, max(1, Runtime.getRuntime().availableProcessors())))
                }
                val s = e.createSession(file.path, opts)
                session = s
                inputName = s.inputNames.first()
                error = null
                reset.set(true)
                state = if (loaded == model) State.READY else State.LOADING
            } catch (t: Throwable) {
                Log.e(TAG, "load", t)
                error = t.message ?: t.javaClass.simpleName
                state = State.FAILED
            } finally {
                busy.set(false)
            }
        }
    }

    /** True when a frame can be handed over now. */
    fun canAccept(): Boolean = state == State.READY && !busy.get()

    /** The buffer the renderer reads a frame into; only touch it when [canAccept] is true. */
    fun inputBuffer(width: Int, height: Int): ByteBuffer {
        val need = width * height * 4
        val b = input?.takeIf { it.capacity() == need }
            ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { input = it }
        b.clear()
        return b
    }

    fun submit(width: Int, height: Int) {
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try {
                infer(width, height)
            } catch (t: Throwable) {
                Log.e(TAG, "infer", t)
                error = t.message ?: t.javaClass.simpleName
            } finally {
                busy.set(false)
            }
        }
    }

    fun poll(): DepthFrame? = result.getAndSet(null)

    /** Forget the previous frames, after a seek or a new video. */
    fun resetTemporal() = reset.set(true)

    fun close() {
        exec.execute {
            session?.close()
            session = null
        }
        exec.shutdown()
    }

    private fun infer(w: Int, h: Int) {
        val s = session ?: return
        val e = env ?: return
        val src = input ?: return
        val n = w * h
        val buf = chw?.takeIf { it.capacity() == 3 * n }
            ?: ByteBuffer.allocateDirect(3 * n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { chw = it }

        // RGBA bottom-up -> normalised CHW top-down, and a coarse luminance grid for cut detection.
        val grid = FloatArray(LUMA_W * LUMA_H)
        val counts = IntArray(LUMA_W * LUMA_H)
        for (y in 0 until h) {
            val row = (h - 1 - y) * w
            val gy = y * LUMA_H / h
            for (x in 0 until w) {
                val i = (row + x) * 4
                val r = (src.get(i).toInt() and 0xff) / 255f
                val g = (src.get(i + 1).toInt() and 0xff) / 255f
                val b = (src.get(i + 2).toInt() and 0xff) / 255f
                val o = y * w + x
                buf.put(o, (r - 0.485f) / 0.229f)
                buf.put(n + o, (g - 0.456f) / 0.224f)
                buf.put(2 * n + o, (b - 0.406f) / 0.225f)
                val c = gy * LUMA_W + x * LUMA_W / w
                grid[c] += 0.299f * r + 0.587f * g + 0.114f * b
                counts[c]++
            }
        }
        var diff = 0f
        for (c in grid.indices) {
            grid[c] /= max(1, counts[c])
            diff += abs(grid[c] - luma[c])
        }
        diff /= grid.size
        val cut = reset.getAndSet(false) || !hasLuma || diff > CUT_THRESHOLD
        grid.copyInto(luma)
        hasLuma = true
        buf.rewind()

        val t0 = SystemClock.elapsedRealtime()
        OnnxTensor.createTensor(e, buf, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            s.run(mapOf(inputName to tensor)).use { out ->
                val t = out.get(0) as OnnxTensor
                val shape = t.info.shape
                val oh = shape[shape.size - 2].toInt()
                val ow = shape[shape.size - 1].toInt()
                val raw = t.floatBuffer
                val values = FloatArray(ow * oh)
                raw.get(values)
                publish(values, ow, oh, cut)
            }
        }
        val now = SystemClock.elapsedRealtime()
        lastMs = now - t0
        if (lastDone > 0) {
            val inst = 1000f / max(1L, now - lastDone)
            fps = if (fps == 0f) inst else fps * 0.8f + inst * 0.2f
        }
        lastDone = now
    }

    private fun publish(v: FloatArray, w: Int, h: Int, cut: Boolean) {
        // Depth Anything gives relative inverse depth: bigger is nearer. Its scale drifts from
        // frame to frame, so it is stretched to 0..1 by robust percentiles that change slowly.
        val step = max(1, v.size / 4096)
        val sample = FloatArray((v.size + step - 1) / step) { v[min(v.size - 1, it * step)] }
        sample.sort()
        val nlo = sample[(sample.size * 0.02f).toInt()]
        val nhi = sample[min(sample.size - 1, (sample.size * 0.98f).toInt())]
        val sameSize = prevW == w && prevH == h
        if (cut || !sameSize) {
            lo = nlo
            hi = nhi
        } else {
            lo += (nlo - lo) * 0.25f
            hi += (nhi - hi) * 0.25f
        }
        val range = max(hi - lo, 1e-4f)
        val p = prev?.takeIf { sameSize && !cut }
        val outArr = FloatArray(w * h)
        for (y in 0 until h) {
            val dst = (h - 1 - y) * w
            val srcRow = y * w
            for (x in 0 until w) {
                var d = ((v[srcRow + x] - lo) / range).coerceIn(0f, 1f)
                if (p != null) d = p[dst + x] * TEMPORAL + d * (1f - TEMPORAL)
                outArr[dst + x] = d
            }
        }
        prev = outArr
        prevW = w
        prevH = h
        val fb = ByteBuffer.allocateDirect(outArr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(outArr)
        fb.rewind()
        result.set(DepthFrame(w, h, fb))
        onResult?.invoke()
    }

    /** ONNX Runtime opens models from files, so the bundled one is copied out once per app version. */
    private fun modelFile(name: String): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val dst = File(dir, name)
        val stamp = File(dir, "$name.version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (dst.exists() && stamp.exists() && stamp.readText() == version) return dst
        val tmp = File(dir, "$name.tmp")
        context.assets.open("models/$name").use { input ->
            tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("Не удалось сохранить модель")
        stamp.writeText(version)
        return dst
    }

    private companion object {
        const val TAG = "DepthEngine"
        const val LUMA_W = 16
        const val LUMA_H = 9
        const val CUT_THRESHOLD = 0.12f
        /** Share of the previous map kept in each new one: steadier depth, a little lag. */
        const val TEMPORAL = 0.35f
    }
}
