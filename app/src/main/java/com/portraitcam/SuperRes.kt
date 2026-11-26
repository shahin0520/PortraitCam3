package com.portraitcam

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.min

// Real-ESRGAN (compact) x4 AI আপস্কেল, টাইল ধরে ধরে, GPU তে (না পারলে CPU তে)
class SuperRes(private val ctx: Context) {
    private var interp: Interpreter? = null
    private var gpu: GpuDelegate? = null
    private var loaded = ""
    @Volatile var usingGpu = false
    private val tile = 128
    private val pad = 8
    private val inBuf = ByteBuffer.allocateDirect(tile * tile * 3 * 4).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(tile * 4 * tile * 4 * 3 * 4).order(ByteOrder.nativeOrder())

    private fun mapModel(name: String): ByteBuffer {
        val fd = ctx.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    @Synchronized
    fun load(name: String, forceCpu: Boolean = false): Boolean {
        if (interp != null && loaded == name && (!forceCpu || !usingGpu)) return true
        close()
        if (!forceCpu) {
            try {
                val g = GpuDelegate()
                gpu = g
                interp = Interpreter(mapModel(name), Interpreter.Options().addDelegate(g))
                usingGpu = true
                loaded = name
                return true
            } catch (e: Throwable) {
                try { interp?.close() } catch (x: Throwable) { }
                try { gpu?.close() } catch (x: Throwable) { }
                interp = null
                gpu = null
            }
        }
        return try {
            interp = Interpreter(mapModel(name), Interpreter.Options().setNumThreads(4))
            usingGpu = false
            loaded = name
            true
        } catch (e: Throwable) {
            interp = null
            false
        }
    }

    @Synchronized
    fun close() {
        try { interp?.close() } catch (e: Throwable) { }
        try { gpu?.close() } catch (e: Throwable) { }
        interp = null
        gpu = null
        loaded = ""
        usingGpu = false
    }

    private fun runTile(): Boolean {
        return try {
            inBuf.rewind()
            outBuf.rewind()
            interp!!.run(inBuf, outBuf)
            true
        } catch (e: Throwable) {
            false
        }
    }

    // px: w*h RGB. রিটার্ন: (4w)*(4h) RGB, না পারলে বা খুব ধীর হলে null
    fun upscale(px: IntArray, w: Int, h: Int, model: String, maxSeconds: Int, progress: (Int, Int) -> Unit): IntArray? {
        if (!load(model)) return null
        val ow = w * 4
        val oh = h * 4
        val out = IntArray(ow * oh)
        val core = tile - 2 * pad
        val nx = (w + core - 1) / core
        val ny = (h + core - 1) / core
        val total = nx * ny
        val t0 = System.nanoTime()
        var done = 0
        val fOut = FloatArray(tile * 4 * tile * 4 * 3)
        val ot = tile * 4
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val x0 = tx * core
            val y0 = ty * core
            inBuf.rewind()
            var inSum = 0f
            for (yy in 0 until tile) {
                val sy = (y0 - pad + yy).coerceIn(0, h - 1)
                for (xx in 0 until tile) {
                    val sx = (x0 - pad + xx).coerceIn(0, w - 1)
                    val p = px[sy * w + sx]
                    val r = ((p shr 16) and 255) / 255f
                    val g = ((p shr 8) and 255) / 255f
                    val b = (p and 255) / 255f
                    inBuf.putFloat(r); inBuf.putFloat(g); inBuf.putFloat(b)
                    inSum += g
                }
            }
            var ok = runTile()
            if (ok) {
                outBuf.rewind()
                outBuf.asFloatBuffer().get(fOut)
                if (done == 0) {
                    // GPU তে ভুল/কালো ফল এলে ধরে CPU তে যাওয়া
                    var s = 0f
                    var i = 1
                    while (i < fOut.size) { s += fOut[i]; i += 3 * 97 }
                    val outMean = s / (fOut.size / (3 * 97)).coerceAtLeast(1)
                    val inMean = inSum / (tile * tile)
                    if (abs(outMean - inMean) > 0.2f || outMean.isNaN()) ok = false
                }
            }
            if (!ok) {
                if (usingGpu && load(model, true)) {
                    ok = runTile()
                    if (ok) { outBuf.rewind(); outBuf.asFloatBuffer().get(fOut) }
                }
                if (!ok) return null
            }
            val cw = min(core, w - x0)
            val ch = min(core, h - y0)
            for (oy in 0 until ch * 4) {
                val srcRow = (pad * 4 + oy) * ot + pad * 4
                val dstRow = (y0 * 4 + oy) * ow + x0 * 4
                for (ox in 0 until cw * 4) {
                    val si = (srcRow + ox) * 3
                    val r = (fOut[si] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (fOut[si + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (fOut[si + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    out[dstRow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            done++
            progress(done, total)
            val el = (System.nanoTime() - t0) / 1e9
            if (done >= 3) {
                val est = el / done * total
                if (est > maxSeconds) return null
            }
        }
        return out
    }
}
