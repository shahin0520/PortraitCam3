package com.portraitcam

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// ছবি দেখে দূরত্ব (ডেপথ) আন্দাজ করা (MiDaS small), ডেপথ সেন্সর ছাড়াই
class DepthEst(private val ctx: Context) {
    private var interp: Interpreter? = null
    private val n = 256
    private val inBuf = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
    private val outBuf = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())

    private fun mapModel(name: String): ByteBuffer {
        val fd = ctx.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    // রিটার্ন: 256x256, মান ০..১ (১ = সবচেয়ে কাছে)। না পারলে null
    @Synchronized
    fun estimate(bmp: Bitmap): FloatArray? {
        return try {
            if (interp == null) {
                interp = Interpreter(mapModel("depth.tflite"), Interpreter.Options().setNumThreads(4))
            }
            val sb = Bitmap.createScaledBitmap(bmp, n, n, true)
            val px = IntArray(n * n)
            sb.getPixels(px, 0, n, 0, 0, n, n)
            if (sb !== bmp) sb.recycle()
            inBuf.rewind()
            val m0 = 0.485f
            val m1 = 0.456f
            val m2 = 0.406f
            val s0 = 0.229f
            val s1 = 0.224f
            val s2 = 0.225f
            for (p in px) {
                inBuf.putFloat((((p shr 16) and 255) / 255f - m0) / s0)
                inBuf.putFloat((((p shr 8) and 255) / 255f - m1) / s1)
                inBuf.putFloat(((p and 255) / 255f - m2) / s2)
            }
            inBuf.rewind()
            outBuf.rewind()
            interp!!.run(inBuf, outBuf)
            outBuf.rewind()
            val d = FloatArray(n * n)
            outBuf.asFloatBuffer().get(d)
            val sorted = d.copyOf()
            sorted.sort()
            val lo = sorted[(sorted.size * 0.02f).toInt()]
            val hi = sorted[(sorted.size * 0.98f).toInt()]
            val span = hi - lo + 1e-6f
            for (i in d.indices) d[i] = ((d[i] - lo) / span).coerceIn(0f, 1f)
            d
        } catch (e: Throwable) {
            null
        }
    }
}
