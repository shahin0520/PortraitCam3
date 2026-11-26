package com.portraitcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class Shot(val jpeg: ByteArray, val rot: Int)

class Params(
    val front: Boolean,
    val zoom: Float,
    val softZoom: Boolean,
    val merge: Int,          // 0 = এক ফ্রেম, 1 = গড় (নয়েজ কমানো), 2 = HDR
    val hdrSingle: Boolean,
    val night: Boolean,
    val portrait: Boolean,
    val blur: Int,
    val beauty: Int,
    val filter: Int,
    val ai: Int,
    val bright: Int,
    val detail: Int,
    val tele: Boolean,
    val nrBoost: Float,
    val dark: Boolean,
    val native: Boolean
)

class Merged(val px: IntArray, val w: Int, val h: Int) {
    var hdrOk: Boolean = false
    var scale: Float = 1f     // সুপার-রেজোলিউশনে ছবি কতগুণ বড় হয়ে এসেছে
    var used: Int = 1         // কয়টা ফ্রেম কাজে লেগেছে
}

object Pipeline {

    @Volatile var note: String = ""

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    ) { rr -> Thread(rr).apply { isDaemon = true } }

    // কাজ কয়েকটা অংশে ভাগ করে সবগুলো প্রসেসরে একসাথে চালানো (প্রসেসিং অনেক দ্রুত হয়)
    private fun parallelBands(n: Int, minChunk: Int = 64, body: (Int, Int) -> Unit) {
        val nt = min(Runtime.getRuntime().availableProcessors().coerceIn(2, 8), max(1, n / minChunk))
        if (nt <= 1) {
            body(0, n)
            return
        }
        val chunk = (n + nt - 1) / nt
        val tasks = ArrayList<java.util.concurrent.Callable<Unit>>()
        var st = 0
        while (st < n) {
            val a = st
            val b = min(n, st + chunk)
            tasks.add(java.util.concurrent.Callable { body(a, b) })
            st += chunk
        }
        for (f in pool.invokeAll(tasks)) f.get()
    }
    @Volatile var diag: String = ""

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )
    }
    private val subjectSeg by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        )
    }

    // অ্যাপ খোলার সময় একবার চালিয়ে মডেল নামিয়ে রাখা
    fun warmUp() {
        try {
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            Tasks.await(subjectSeg.process(InputImage.fromBitmap(b, 0)), 60, TimeUnit.SECONDS)
        } catch (e: Throwable) { }
    }

    // দ্বিতীয় মডেল: ছবির মূল বিষয় (subject) আলাদা করে। না পেলে null
    private fun subjectConf(src: Bitmap, mw: Int, mh: Int): FloatArray? {
        return try {
            val s = 768f / max(src.width, src.height)
            val iw = max(16, (src.width * s).toInt())
            val ih = max(16, (src.height * s).toInt())
            val small = Bitmap.createScaledBitmap(src, iw, ih, true)
            val res = Tasks.await(subjectSeg.process(InputImage.fromBitmap(small, 0)), 20, TimeUnit.SECONDS)
            small.recycle()
            val fb = res.foregroundConfidenceMask ?: return null
            fb.rewind()
            if (fb.remaining() < iw * ih) return null
            val out = FloatArray(mw * mh)
            for (y in 0 until mh) {
                val sy = min(ih - 1, y * ih / mh)
                for (x in 0 until mw) {
                    val sx = min(iw - 1, x * iw / mw)
                    out[y * mw + x] = fb.get(sy * iw + sx)
                }
            }
            out
        } catch (e: Throwable) {
            null
        }
    }

    private val faceDet by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        )
    }

    fun run(shots: List<Shot>, p: Params, sr: SuperRes?, depthEst: DepthEst?, progress: (String) -> Unit): Pair<Bitmap, Boolean> {
        val t0 = System.currentTimeMillis()
        progress("ফ্রেম মেলানো হচ্ছে…")
        // সবচেয়ে ধারালো ফ্রেম (বড় JPEG) আগে, ঝাপসা ফ্রেম বাদ
        var shotsX = shots
        if ((p.merge == 1 || p.merge >= 3) && shots.size >= 3) {
            val ordered = shots.sortedByDescending { it.jpeg.size }
            val keep = ordered.filter { it.jpeg.size >= ordered[0].jpeg.size * (if (p.merge >= 3) 0.75 else 0.85) }
            shotsX = if (keep.size >= 2) keep else ordered.take(2)
        }
        val first = shotsX[0]
        val dec0 = BitmapRegionDecoder.newInstance(first.jpeg, 0, first.jpeg.size, false)
        val sw0 = dec0.width
        val sh0 = dec0.height
        dec0.recycle()
        val z = if (p.softZoom) p.zoom else 1f
        val cw = max(32, (sw0 / z).toInt() and 1.inv())
        val ch = max(32, (sh0 / z).toInt() and 1.inv())
        val x0 = ((sw0 - cw) / 2) and 1.inv()
        val y0 = ((sh0 - ch) / 2) and 1.inv()
        val rect = Rect(x0, y0, x0 + cw, y0 + ch)
        var sample = 1
        while ((cw / sample).toLong() * (ch / sample) > 16_000_000L) sample *= 2

        val ghost = if (p.dark) 160 else 100
        val m = merge(shotsX, rect, sample, p.merge, ghost)
        if (sample == 1 && m.scale == 1f) deblock(m.px, m.w, m.h, rect.left, rect.top)
        var msum = 0L
        var mcnt = 0
        var si = 0
        while (si < m.px.size) { msum += lum(m.px[si]); mcnt++; si += 37 }
        val meanL = if (mcnt > 0) (msum / mcnt).toInt() else 120
        val kb = brighten(m.px, m.w, m.h, p.bright, meanL)
        val zz = z
        val up = if (zz > 1.01f) min(zz, max(5f, 1600f / max(m.w, m.h))) else 1f
        val hiZoom = zz > 3.5f && p.ai != 0 && sr != null
        val useAi = p.ai == 2 && sr != null && up > 1.01f && m.scale == 1f && !hiZoom
        val sigma0 = estimateSigma(m.px, m.w, m.h)
        var sigma = sigma0 * (1f + p.nrBoost * 2f).coerceIn(0.5f, 1.8f)
        if (useAi) sigma *= 0.6f
        if (p.native) sigma = min(sigma, 3f)
        progress("নয়েজ কমানো হচ্ছে…")
        denoise(m.px, m.w, m.h, sigma)
        diag = String.format(java.util.Locale.US, "নয়েজ σ=%.1f→%.1f | ফ্রেম %d | গড় আলো %d | ডিনয়েজ %s",
            sigma0, sigma, m.used.coerceAtLeast(shotsX.size.coerceAtMost(if (m.scale > 1f) 0 else 99)), meanL, if (sigma >= 1.8f) "হ্যাঁ" else "না")
        var px0 = m.px
        var mw = m.w
        var mh = m.h
        var aiDone = false
        var rotatedEarly = false
        note = ""
        // জুম-পোর্ট্রেট: আগে সোজা করে নিই, যাতে মানুষের অংশ চিনে শুধু সেখানে AI চালানো যায়
        if (up > 1.01f && useAi && p.portrait && mw.toLong() * mh <= 6_000_000L) {
            val tb = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            tb.setPixels(px0, 0, mw, 0, 0, mw, mh)
            val mxr = Matrix()
            mxr.postRotate(first.rot.toFloat())
            if (p.front) mxr.postScale(-1f, 1f)
            val rb = Bitmap.createBitmap(tb, 0, 0, mw, mh, mxr, true)
            if (rb !== tb) tb.recycle()
            mw = rb.width
            mh = rb.height
            px0 = IntArray(mw * mh)
            rb.getPixels(px0, 0, mw, 0, 0, mw, mh)
            rb.recycle()
            rotatedEarly = true
        }
        val upLeft = (if (m.scale > 1f) min(up, m.scale) else up) / m.scale
        if (hiZoom) {
            // সুপার জুম (৩.৫x থেকে ১০০x): ছবি মিলিয়ে সুপার-রেজোলিউশন, তারপর AI দিয়ে ধাপে ধাপে বড় করা
            var cx = px0
            var cw2 = mw
            var ch2 = mh
            var passes = 0
            while (max(cw2, ch2) < 1500 && passes < 3) {
                val rr = aiUpscale(cx, cw2, ch2, 2, sr!!, 120, progress) ?: break
                cx = rr.px
                cw2 = rr.w
                ch2 = rr.h
                passes++
            }
            if (passes == 0 && m.scale == 1f) {
                progress("ছবি বড় করা হচ্ছে…")
                val nw = max(2, (mw * up).toInt() and 1.inv())
                val nh = max(2, (mh * up).toInt() and 1.inv())
                cx = lanczos(cx, mw, mh, nw, nh)
                cw2 = nw
                ch2 = nh
            }
            if (max(cw2, ch2) > 4000) {
                val sc = 4000f / max(cw2, ch2)
                val nw = max(2, (cw2 * sc).toInt() and 1.inv())
                val nh = max(2, (ch2 * sc).toInt() and 1.inv())
                cx = lanczos(cx, cw2, ch2, nw, nh)
                cw2 = nw
                ch2 = nh
            }
            px0 = cx
            mw = cw2
            mh = ch2
            aiDone = passes > 0
            note = "সুপার জুম ${String.format(java.util.Locale.US, "%.0f", zz)}x (${m.used}টা ছবি মিলিয়ে" + (if (passes > 0) ", AI × $passes" else "") + ")"
        } else if (upLeft > 1.04f) {
            val nw = max(2, (mw * upLeft).toInt() and 1.inv())
            val nh = max(2, (mh * upLeft).toInt() and 1.inv())
            if (useAi) {
                val box = if (rotatedEarly) personBox(px0, mw, mh) else null
                val r = if (box != null) aiSubject(px0, mw, mh, box, nw, nh, p.ai, sr!!, progress)
                else aiUpscale(px0, mw, mh, p.ai, sr!!, 90, progress)
                if (r != null) {
                    px0 = r.px
                    if (abs(r.w - nw) > 8 || abs(r.h - nh) > 8) {
                        progress("ছবির মাপ ঠিক করা হচ্ছে…")
                        px0 = lanczos(px0, r.w, r.h, nw, nh)
                        mw = nw
                        mh = nh
                    } else {
                        mw = r.w
                        mh = r.h
                    }
                    aiDone = true
                }
            }
            if (!aiDone) {
                progress("ছবি বড় করা হচ্ছে…")
                px0 = lanczos(px0, mw, mh, nw, nh)
                mw = nw
                mh = nh
            }
        } else if (m.scale > 1f) {
            note = "সুপার-রেজোলিউশন জুম হয়েছে (${m.used}টা ছবি মিলিয়ে)"
        } else if (p.ai > 0 && up <= 1.01f) {
            note = "জুম (১.০৫x-এর বেশি) করলে সুপার-রেজোলিউশন/AI চলে"
        }
        var bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px0, 0, mw, 0, 0, mw, mh)
        px0 = IntArray(0)
        if (!rotatedEarly) {
            val mx = Matrix()
            mx.postRotate(first.rot.toFloat())
            if (p.front) mx.postScale(-1f, 1f)
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        progress("প্রসেস হচ্ছে…")
        val w = bmp.width
        val h = bmp.height
        val mask = if (p.portrait) personMask(bmp) else null
        val noPerson = p.portrait && mask == null
        val dmap = if (p.portrait && mask != null && depthEst != null) depthEst.estimate(bmp) else null
        var px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val hdrSingle2 = p.hdrSingle || (p.merge == 2 && !m.hdrOk)
        if (!p.native) tonemap(px, w, h, hdrSingle2, p.night)
        val dk = p.detail / 100f
        val core = (0.8f * sigma + 2f).toInt().coerceIn(2, 10)
        var amt = if (p.native) 0.5f else 0.4f + dk * 0.8f
        amt = if (aiDone) amt * 0.5f else amt * (1f + 0.08f * (z - 1f)).coerceAtMost(1.3f) * (if (m.scale > 1f) 1.25f else 1f)
        val ck = if (p.native) dk * 0.15f else dk * 0.3f * (if (aiDone) 0.6f else 1f)
        progress("ডিটেইলস বাড়ানো হচ্ছে…")
        clarity(px, w, h, ck)
        sharpen2(px, w, h, amt, core)
        var out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        px = IntArray(0)
        if (mask != null) {
            progress("ব্যাকগ্রাউন্ড ব্লার…")
            val bgScale = if (p.tele && p.softZoom && z > 1.05f) (1f + 0.10f * (z - 1f)).coerceAtMost(1.25f) else 1f
            out = composite(out, mask, p.blur, bgScale, dmap)
            note = note + (if (dmap != null) " | ডেপথ-ব্লার" else " | সাধারণ ব্লার")
        }
        if (p.beauty > 0) {
            progress("ফেস স্মুদ…")
            beautify(out, p.beauty)
        }
        if (p.filter > 0) applyFilter(out, p.filter)
        diag = diag + String.format(java.util.Locale.US, " | সময় %.0fs", (System.currentTimeMillis() - t0) / 1000f)
        return Pair(out, noPerson)
    }

    // ---------- ফ্রেম মেলানো ----------

    private fun lum(p: Int): Int =
        ((((p shr 16) and 255) * 77) + (((p shr 8) and 255) * 150) + ((p and 255) * 29)) shr 8

    private fun decode(s: Shot, rect: Rect, sample: Int): Merged {
        val dec = BitmapRegionDecoder.newInstance(s.jpeg, 0, s.jpeg.size, false)
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        val b = dec.decodeRegion(rect, o)
        dec.recycle()
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        b.recycle()
        return Merged(px, w, h)
    }

    private class Grad(val g: IntArray, val w: Int, val h: Int)

    private fun gradSmall(px: IntArray, w: Int, h: Int, f: Int): Grad {
        val sw = w / f
        val sh = h / f
        val l = IntArray(sw * sh)
        val hf = f / 2
        for (y in 0 until sh) for (x in 0 until sw) {
            val bx = x * f
            val by = y * f
            val a = lum(px[by * w + bx]) + lum(px[by * w + bx + hf]) +
                lum(px[(by + hf) * w + bx]) + lum(px[(by + hf) * w + bx + hf])
            l[y * sw + x] = a shr 2
        }
        val g = IntArray(sw * sh)
        var sum = 0L
        for (y in 0 until sh - 1) for (x in 0 until sw - 1) {
            val v = abs(l[y * sw + x + 1] - l[y * sw + x]) + abs(l[(y + 1) * sw + x] - l[y * sw + x])
            g[y * sw + x] = v
            sum += v
        }
        val mean = max(1L, sum / (sw * sh))
        for (i in g.indices) g[i] = (g[i] * 64L / mean).toInt()
        return Grad(g, sw, sh)
    }

    private fun findShift(a: Grad, b: Grad, r: Int, cx: Int, cy: Int): IntArray {
        val sw = a.w
        val sh = a.h
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        var total = 0.0
        var cands = 0
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            val ys = max(0, -dy)
            val ye = min(sh - 1, sh - 1 - dy)
            val xs = max(0, -dx)
            val xe = min(sw - 1, sw - 1 - dx)
            if (ye <= ys || xe <= xs) continue
            var s = 0L
            var n = 0
            var y = ys
            while (y <= ye) {
                var x = xs
                val ra = y * sw
                val rb = (y + dy) * sw + dx
                while (x <= xe) {
                    s += abs(a.g[ra + x] - b.g[rb + x])
                    n++
                    x += 2
                }
                y += 2
            }
            if (n == 0) continue
            val c = s * 1000L / n
            total += c.toDouble()
            cands++
            if (c < best) { best = c; bx = dx; by = dy }
        }
        val avg = if (cands > 0) total / cands else 1.0
        val conf = if (avg > 0.0) (best * 1000.0 / avg).toInt() else 1000
        return intArrayOf(bx, by, conf)
    }

    private class Bil(val w: Int, val h: Int, val gw: Int, val gh: Int) {
        val x0 = IntArray(w)
        val fx = FloatArray(w)
        var ra = 0
        var rb = 0
        var fy = 0f

        init {
            for (x in 0 until w) {
                val g = x * (gw - 1f) / (w - 1)
                val i = min(g.toInt(), gw - 2)
                x0[x] = i
                fx[x] = g - i
            }
        }

        fun row(y: Int) {
            val g = y * (gh - 1f) / (h - 1)
            val j = min(g.toInt(), gh - 2)
            fy = g - j
            ra = j * gw
            rb = (j + 1) * gw
        }

        fun at(grid: FloatArray, x: Int): Float {
            val i = x0[x]
            val f = fx[x]
            val a = grid[ra + i] * (1f - f) + grid[ra + i + 1] * f
            val b = grid[rb + i] * (1f - f) + grid[rb + i + 1] * f
            return a * (1f - fy) + b * fy
        }
    }

    private fun lumaGrid(px: IntArray, w: Int, h: Int, gw: Int, gh: Int, dx: Int, dy: Int): FloatArray {
        var lg = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x0 = gx * w / gw
            val x1 = max(x0 + 1, (gx + 1) * w / gw)
            val y0 = gy * h / gh
            val y1 = max(y0 + 1, (gy + 1) * h / gh)
            val sx = max(1, (x1 - x0) / 4)
            val sy = max(1, (y1 - y0) / 4)
            var sum = 0f
            var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                val yy = (y + dy).coerceIn(0, h - 1)
                while (x < x1) {
                    sum += lum(px[yy * w + (x + dx).coerceIn(0, w - 1)])
                    n++
                    x += sx
                }
                y += sy
            }
            lg[gy * gw + gx] = if (n > 0) sum / n else 128f
        }
        val t = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var s = 0f
            var n = 0
            for (dy2 in -1..1) for (dx2 in -1..1) {
                val yy = gy + dy2
                val xx = gx + dx2
                if (yy in 0 until gh && xx in 0 until gw) { s += lg[yy * gw + xx]; n++ }
            }
            t[gy * gw + gx] = s / n
        }
        lg = t
        return lg
    }

    private fun expWeights(px: IntArray, w: Int, h: Int, gw: Int, gh: Int, dx: Int, dy: Int): FloatArray {
        val lg = lumaGrid(px, w, h, gw, gh, dx, dy)
        for (i in lg.indices) {
            val d = (lg[i] - 125f) / 60f
            lg[i] = exp(-(d * d)) + 0.03f
        }
        return lg
    }

    private fun merge(shots: List<Shot>, rect: Rect, sample: Int, mode: Int, ghost: Int): Merged {
        if (mode == 2) return mergeHdr(shots, rect, sample)
        if (mode == 3) return superResolve(shots, rect, sample, 0f)
        if (mode == 4) return superResolve(shots, rect, sample, 1f)
        val ref = decode(shots[0], rect, sample)
        if (shots.size == 1 || mode == 0) return ref
        val w = ref.w
        val h = ref.h
        val out = ref.px
        val cf = if (min(w, h) >= 600) 8 else 4
        val ff = max(2, cf / 2)
        val rg = gradSmall(out, w, h, cf)
        val rg2 = gradSmall(out, w, h, ff)
        val gw = 64
        val gh = max(2, gw * h / w)
        val sumGrid: FloatArray? = if (mode == 2) expWeights(out, w, h, gw, gh, 0, 0) else null
        val bil = Bil(w, h, gw, gh)
        var count = 1
        for (k in 1 until shots.size) {
            val cur = try { decode(shots[k], rect, sample) } catch (e: Throwable) { continue }
            if (cur.w != w || cur.h != h) continue
            val cg = gradSmall(cur.px, w, h, cf)
            val s1 = findShift(rg, cg, 16, 0, 0)
            val ratio = cf / ff
            val cg2 = gradSmall(cur.px, w, h, ff)
            val s2 = findShift(rg2, cg2, 3, s1[0] * ratio, s1[1] * ratio)
            val dx = s2[0] * ff
            val dy = s2[1] * ff
            val cp = cur.px
            if (mode == 1) {
                for (y in 0 until h) {
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val ro = y * w
                    val rc = sy * w
                    for (x in 0 until w) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val p = cp[rc + sx]
                        val o = out[ro + x]
                        val r0 = (o shr 16) and 255
                        val g0 = (o shr 8) and 255
                        val b0 = o and 255
                        val r1 = (p shr 16) and 255
                        val g1 = (p shr 8) and 255
                        val b1 = p and 255
                        val dd = abs(r1 - r0) + abs(g1 - g0) + abs(b1 - b0)
                        if (dd >= ghost) continue
                        val wgt = if (dd * 5 < ghost * 2) 1f else (ghost - dd) / (ghost * 0.6f)
                        val t = wgt / (count + wgt)
                        val nr = (r0 + (r1 - r0) * t + 0.5f).toInt().coerceIn(0, 255)
                        val ng = (g0 + (g1 - g0) * t + 0.5f).toInt().coerceIn(0, 255)
                        val nb = (b0 + (b1 - b0) * t + 0.5f).toInt().coerceIn(0, 255)
                        out[ro + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
            } else {
                val wg = expWeights(cp, w, h, gw, gh, dx, dy)
                val sg = sumGrid!!
                for (y in 0 until h) {
                    bil.row(y)
                    val sy = (y + dy).coerceIn(0, h - 1)
                    val ro = y * w
                    val rc = sy * w
                    for (x in 0 until w) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val p = cp[rc + sx]
                        val o = out[ro + x]
                        val wk = bil.at(wg, x)
                        val ws = bil.at(sg, x)
                        val t = wk / (ws + wk)
                        val r0 = (o shr 16) and 255
                        val g0 = (o shr 8) and 255
                        val b0 = o and 255
                        val nr = (r0 + (((p shr 16) and 255) - r0) * t).toInt().coerceIn(0, 255)
                        val ng = (g0 + (((p shr 8) and 255) - g0) * t).toInt().coerceIn(0, 255)
                        val nb = (b0 + ((p and 255) - b0) * t).toInt().coerceIn(0, 255)
                        out[ro + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
                for (i in sg.indices) sg[i] += wg[i]
            }
            count++
        }
        return ref
    }

    // ---------- টোন, রং, ডিটেইলস ----------

    private fun tonemap(px: IntArray, w: Int, h: Int, hdr: Boolean, night: Boolean) {
        val gw = 48
        val gh = max(2, gw * h / w)
        var gain = FloatArray(gw * gh) { 1f }
        if (hdr || night) {
            val lg = lumaGrid(px, w, h, gw, gh, 0, 0)
            gain = FloatArray(gw * gh)
            for (i in gain.indices) {
                gain[i] = if (night) (135f / max(lg[i], 25f)).pow(0.5f).coerceIn(1.0f, 2.0f)
                else (125f / max(lg[i], 30f)).pow(0.35f).coerceIn(0.9f, 1.5f)
            }
        }
        val lut = IntArray(256) { i ->
            val t = i / 255f
            val s = t * t * (3f - 2f * t)
            ((t * 0.8f + s * 0.2f) * 255f).toInt().coerceIn(0, 255)
        }
        val gainF = gain
        parallelBands(h, 32) { y0, y1 ->
        val bil = Bil(w, h, gw, gh)
        for (y in y0 until y1) {
            bil.row(y)
            val row = y * w
            for (x in 0 until w) {
                val g = bil.at(gainF, x)
                val p = px[row + x]
                var r = lut[(((p shr 16) and 255) * g).toInt().coerceIn(0, 255)]
                var gr = lut[(((p shr 8) and 255) * g).toInt().coerceIn(0, 255)]
                var bl = lut[((p and 255) * g).toInt().coerceIn(0, 255)]
                val l = (r * 77 + gr * 150 + bl * 29) shr 8
                r = (l + (((r - l) * 276) shr 8)).coerceIn(0, 255)
                gr = (l + (((gr - l) * 276) shr 8)).coerceIn(0, 255)
                bl = (l + (((bl - l) * 276) shr 8)).coerceIn(0, 255)
                px[row + x] = (0xFF shl 24) or (r shl 16) or (gr shl 8) or bl
            }
        }
        }
    }

    private fun sharpen(px: IntArray, w: Int, h: Int, amt: Float) {
        val k = (amt * 256f).toInt()
        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        System.arraycopy(px, 0, prev, 0, w)
        System.arraycopy(px, 0, cur, 0, w)
        for (y in 0 until h) {
            val ny = if (y + 1 < h) y + 1 else y
            System.arraycopy(px, ny * w, next, 0, w)
            val base = y * w
            for (x in 1 until w - 1) {
                val c = cur[x]
                val l = cur[x - 1]
                val r = cur[x + 1]
                val u = prev[x]
                val d = next[x]
                val nr = sh((c shr 16) and 255, (((l shr 16) and 255) + ((r shr 16) and 255) + ((u shr 16) and 255) + ((d shr 16) and 255)) shr 2, k)
                val ng = sh((c shr 8) and 255, (((l shr 8) and 255) + ((r shr 8) and 255) + ((u shr 8) and 255) + ((d shr 8) and 255)) shr 2, k)
                val nb = sh(c and 255, ((l and 255) + (r and 255) + (u and 255) + (d and 255)) shr 2, k)
                px[base + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            val t = prev
            prev = cur
            cur = next
            next = t
        }
    }

    // নয়েজ না বাড়িয়ে শার্প (soft coring)
    private fun sh(v: Int, avg: Int, k: Int): Int {
        val d = v - avg
        val ad = abs(d)
        if (ad <= 4) return v
        val dd = min(((ad - 4) * k) shr 9, 36)
        return (if (d > 0) v + dd else v - dd).coerceIn(0, 255)
    }

    // ---------- পোর্ট্রেট ----------

    // মানুষ আলাদা করা: ML মাস্ক → ছোট এলোমেলো দাগ বাদ → আসল ছবির কিনারা ধরে মাস্ক ধারালো
    private fun personMask(src: Bitmap): Bitmap? {
        return try {
            val s = 512f / max(src.width, src.height)
            val small = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
            val m = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
            small.recycle()
            val buf = m.buffer
            val mw = m.width
            val mh = m.height
            val conf = FloatArray(mw * mh)
            for (i in conf.indices) conf[i] = buf.float
            val subj = subjectConf(src, mw, mh)
            if (subj != null) {
                var cs = 0f
                var ci = 0f
                for (i in conf.indices) { cs += conf[i]; ci += min(conf[i], subj[i]) }
                // দুই মডেলের মিল নিলে চেয়ার/জালের মতো ভুল অংশ বাদ যায়। বেশি কেটে গেলে বাদ
                if (ci >= 0.5f * cs) for (i in conf.indices) conf[i] = min(conf[i], subj[i])
            }
            cleanMask(conf, mw, mh)
            var sum = 0f
            for (v in conf) sum += v
            if (sum / (mw * mh) < 0.02f) return null

            val gs = 1280f / max(src.width, src.height)
            val gw = max(16, (src.width * gs).toInt())
            val gh = max(16, (src.height * gs).toInt())
            val pArr = FloatArray(gw * gh)
            for (y in 0 until gh) {
                val fy = y * (mh - 1f) / (gh - 1)
                val y0 = min(fy.toInt(), mh - 2)
                val ty = fy - y0
                for (x in 0 until gw) {
                    val fx = x * (mw - 1f) / (gw - 1)
                    val x0 = min(fx.toInt(), mw - 2)
                    val tx = fx - x0
                    val a1 = conf[y0 * mw + x0] * (1f - tx) + conf[y0 * mw + x0 + 1] * tx
                    val b1 = conf[(y0 + 1) * mw + x0] * (1f - tx) + conf[(y0 + 1) * mw + x0 + 1] * tx
                    pArr[y * gw + x] = a1 * (1f - ty) + b1 * ty
                }
            }
            val gb = Bitmap.createScaledBitmap(src, gw, gh, true)
            val gp = IntArray(gw * gh)
            gb.getPixels(gp, 0, gw, 0, 0, gw, gh)
            gb.recycle()
            val guide = FloatArray(gw * gh) { lum(gp[it]) / 255f }
            val q = guidedFilter(guide, pArr, gw, gh, 8, 0.001f)
            val out = IntArray(gw * gh)
            for (i in out.indices) {
                val t = smooth01((q[i] - 0.5f) / 0.25f)
                out[i] = (t * 255f).toInt() shl 24
            }
            val mb = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            mb.setPixels(out, 0, gw, 0, 0, gw, gh)
            mb
        } catch (e: Throwable) {
            null
        }
    }

    private fun cleanMask(conf: FloatArray, w: Int, h: Int) {
        val n = w * h
        val b = BooleanArray(n) { conf[it] > 0.5f }
        val label = IntArray(n)
        val q = IntArray(n)
        val areas = ArrayList<Int>()
        areas.add(0)
        var nl = 0
        for (st in 0 until n) {
            if (!b[st] || label[st] != 0) continue
            nl++
            var head = 0
            var tail = 0
            q[tail++] = st
            label[st] = nl
            while (head < tail) {
                val i = q[head++]
                val x = i % w
                val y = i / w
                if (x > 0 && b[i - 1] && label[i - 1] == 0) { label[i - 1] = nl; q[tail++] = i - 1 }
                if (x < w - 1 && b[i + 1] && label[i + 1] == 0) { label[i + 1] = nl; q[tail++] = i + 1 }
                if (y > 0 && b[i - w] && label[i - w] == 0) { label[i - w] = nl; q[tail++] = i - w }
                if (y < h - 1 && b[i + w] && label[i + w] == 0) { label[i + w] = nl; q[tail++] = i + w }
            }
            areas.add(tail)
        }
        if (nl == 0) return
        var maxA = 0
        for (a in areas) maxA = max(maxA, a)
        val minA = max((maxA * 0.12f).toInt(), (n * 0.004f).toInt())
        var cur = BooleanArray(n) { label[it] != 0 && areas[label[it]] >= minA }
        repeat(3) {
            val nx = cur.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (!cur[i] && (cur[i - 1] || cur[i + 1] || cur[i - w] || cur[i + w])) nx[i] = true
            }
            cur = nx
        }
        for (i in 0 until n) if (!cur[i]) conf[i] = 0f

        // মানুষের ভেতরের ছোট ফাঁক ভরাট
        val b2 = BooleanArray(n) { conf[it] > 0.5f }
        val lab2 = IntArray(n)
        var l2 = 0
        for (st in 0 until n) {
            if (b2[st] || lab2[st] != 0) continue
            l2++
            var head = 0
            var tail = 0
            var touch = false
            q[tail++] = st
            lab2[st] = l2
            while (head < tail) {
                val i = q[head++]
                val x = i % w
                val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touch = true
                if (x > 0 && !b2[i - 1] && lab2[i - 1] == 0) { lab2[i - 1] = l2; q[tail++] = i - 1 }
                if (x < w - 1 && !b2[i + 1] && lab2[i + 1] == 0) { lab2[i + 1] = l2; q[tail++] = i + 1 }
                if (y > 0 && !b2[i - w] && lab2[i - w] == 0) { lab2[i - w] = l2; q[tail++] = i - w }
                if (y < h - 1 && !b2[i + w] && lab2[i + w] == 0) { lab2[i + w] = l2; q[tail++] = i + w }
            }
            if (!touch && tail < (n * 0.02f).toInt()) {
                for (k in 0 until tail) conf[q[k]] = 1f
            }
        }
    }

    private fun boxF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val c = a.copyOf()
        blurF(c, w, h, r, 1)
        return c
    }

    // Guided filter: মাস্কের কিনারা আসল ছবির কিনারায় বসিয়ে দেয়
    private fun guidedFilter(ig: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val mI = boxF(ig, w, h, r)
        val mP = boxF(p, w, h, r)
        val cI = boxF(FloatArray(n) { ig[it] * ig[it] }, w, h, r)
        val cIp = boxF(FloatArray(n) { ig[it] * p[it] }, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val v = cI[i] - mI[i] * mI[i]
            val c = cIp[i] - mI[i] * mP[i]
            a[i] = c / (v + eps)
            b[i] = mP[i] - a[i] * mI[i]
        }
        val mA = boxF(a, w, h, r)
        val mB = boxF(b, w, h, r)
        return FloatArray(n) { (mA[it] * ig[it] + mB[it]).coerceIn(0f, 1f) }
    }

    private fun composite(base: Bitmap, mask: Bitmap, level: Int, bgScale: Float, depth: FloatArray?): Bitmap {
        val w = base.width
        val h = base.height
        val bg = if (depth != null) depthBackground(base, mask, level, depth) else blurredBackground(base, mask, level)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val full = Rect(0, 0, w, h)
        val fp = Paint(Paint.FILTER_BITMAP_FLAG)
        if (bgScale > 1.01f) {
            // টেলিফটো লুক: ব্যাকগ্রাউন্ড মানুষের কেন্দ্র ধরে একটু বড় (কাছে টানা), যেমন টেলিফটো লেন্সে হয়
            var cx = 0.5f
            var cy = 0.5f
            val mw = mask.width
            val mh = mask.height
            val mpx = IntArray(mw * mh)
            mask.getPixels(mpx, 0, mw, 0, 0, mw, mh)
            var sx = 0.0
            var sy = 0.0
            var sa = 0.0
            var yy = 0
            while (yy < mh) {
                var xx = 0
                while (xx < mw) {
                    val a = (mpx[yy * mw + xx] ushr 24).toDouble()
                    sx += a * xx
                    sy += a * yy
                    sa += a
                    xx += 6
                }
                yy += 6
            }
            if (sa > 0) {
                cx = (sx / sa / mw).toFloat()
                cy = (sy / sa / mh).toFloat()
            }
            c.save()
            c.translate(cx * w, cy * h)
            c.scale(bgScale, bgScale)
            c.translate(-cx * w, -cy * h)
            c.drawBitmap(bg, null, full, fp)
            c.restore()
        } else {
            c.drawBitmap(bg, null, full, fp)
        }
        val bc = Canvas(base)
        val mp = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        bc.drawBitmap(mask, null, full, mp)
        c.drawBitmap(base, 0f, 0f, null)
        base.recycle()
        return out
    }

    // মানুষের কিনারার রঙ যাতে ব্যাকগ্রাউন্ডে না ঢোকে, তাই মাস্ক একটু বাড়িয়ে নিই
    private fun dilatedWeights(mtp: IntArray, w: Int, h: Int, r: Int): IntArray {
        val a = IntArray(w * h) { mtp[it] ushr 24 }
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (dx in -r..r) {
                val v = a[y * w + (x + dx).coerceIn(0, w - 1)]
                if (v > m) m = v
            }
            tmp[y * w + x] = m
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (dy in -r..r) {
                val v = tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                if (v > m) m = v
            }
            out[y * w + x] = 255 - min(255, m * 8)
        }
        return out
    }

    // মানুষের জায়গাটা আশেপাশের আসল ব্যাকগ্রাউন্ডের রঙে ভরাট (push-pull), মানুষের রঙ কখনো ব্যাকগ্রাউন্ডে ঢোকে না
    private class Lv(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val a = FloatArray(w * h)
    }

    private fun fillBg(px: IntArray, wt: IntArray, w: Int, h: Int): IntArray {
        val l0 = Lv(w, h)
        var mr = 0f
        var mg = 0f
        var mb = 0f
        for (i in 0 until w * h) {
            val p = px[i]
            l0.a[i] = wt[i] / 255f
            l0.r[i] = ((p shr 16) and 255).toFloat()
            l0.g[i] = ((p shr 8) and 255).toFloat()
            l0.b[i] = (p and 255).toFloat()
            mr += l0.r[i]; mg += l0.g[i]; mb += l0.b[i]
        }
        val n0 = (w * h).toFloat()
        mr /= n0; mg /= n0; mb /= n0
        val levels = ArrayList<Lv>()
        levels.add(l0)
        var cur = l0
        while (cur.w > 4 && cur.h > 4) {
            val nw = (cur.w + 1) / 2
            val nh = (cur.h + 1) / 2
            val nx = Lv(nw, nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var sa = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = min(cur.w - 1, 2 * x + dx)
                    val yy = min(cur.h - 1, 2 * y + dy)
                    val k = yy * cur.w + xx
                    val a = cur.a[k]
                    sr += cur.r[k] * a; sg += cur.g[k] * a; sb += cur.b[k] * a; sa += a
                }
                val o = y * nw + x
                if (sa > 1e-4f) { nx.r[o] = sr / sa; nx.g[o] = sg / sa; nx.b[o] = sb / sa }
                nx.a[o] = min(1f, sa)
            }
            levels.add(nx)
            cur = nx
        }
        val top = levels[levels.size - 1]
        var fr = FloatArray(top.w * top.h)
        var fg = FloatArray(top.w * top.h)
        var fb = FloatArray(top.w * top.h)
        for (i in fr.indices) {
            val a = top.a[i]
            fr[i] = top.r[i] * a + mr * (1f - a)
            fg[i] = top.g[i] * a + mg * (1f - a)
            fb[i] = top.b[i] * a + mb * (1f - a)
        }
        for (li in levels.size - 2 downTo 0) {
            val lv = levels[li]
            val up = levels[li + 1]
            val nr = FloatArray(lv.w * lv.h)
            val ng = FloatArray(lv.w * lv.h)
            val nb = FloatArray(lv.w * lv.h)
            for (y in 0 until lv.h) {
                val uy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, up.h - 1f)
                val y0 = min(uy.toInt(), up.h - 1)
                val y1 = min(y0 + 1, up.h - 1)
                val ty = uy - y0
                for (x in 0 until lv.w) {
                    val ux = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, up.w - 1f)
                    val x0 = min(ux.toInt(), up.w - 1)
                    val x1 = min(x0 + 1, up.w - 1)
                    val tx = ux - x0
                    val i00 = y0 * up.w + x0
                    val i10 = y0 * up.w + x1
                    val i01 = y1 * up.w + x0
                    val i11 = y1 * up.w + x1
                    val cr = (fr[i00] * (1f - tx) + fr[i10] * tx) * (1f - ty) + (fr[i01] * (1f - tx) + fr[i11] * tx) * ty
                    val cg = (fg[i00] * (1f - tx) + fg[i10] * tx) * (1f - ty) + (fg[i01] * (1f - tx) + fg[i11] * tx) * ty
                    val cb = (fb[i00] * (1f - tx) + fb[i10] * tx) * (1f - ty) + (fb[i01] * (1f - tx) + fb[i11] * tx) * ty
                    val k = y * lv.w + x
                    val a = lv.a[k]
                    nr[k] = a * lv.r[k] + (1f - a) * cr
                    ng[k] = a * lv.g[k] + (1f - a) * cg
                    nb[k] = a * lv.b[k] + (1f - a) * cb
                }
            }
            fr = nr
            fg = ng
            fb = nb
        }
        val out = IntArray(w * h)
        for (i in out.indices) {
            out[i] = (0xFF shl 24) or (fr[i].toInt().coerceIn(0, 255) shl 16) or
                (fg[i].toInt().coerceIn(0, 255) shl 8) or fb[i].toInt().coerceIn(0, 255)
        }
        return out
    }

    // শুধু ব্যাকগ্রাউন্ডের পিক্সেল দিয়ে ব্লার (মানুষের রঙ ছড়ায় না)
    private fun discBlurW(px: IntArray, wt: IntArray, w: Int, h: Int, rad: Int): IntArray {
        val out = IntArray(w * h)
        val xs = IntArray(2 * rad + 1)
        for (i in -rad..rad) xs[i + rad] = sqrt((rad * rad - i * i).toDouble()).toInt()
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            var sw = 0
            var ur = 0
            var ug = 0
            var ub = 0
            var n = 0
            for (dy in -rad..rad) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                val dm = xs[dy + rad]
                val xa = max(0, x - dm)
                val xb = min(w - 1, x + dm)
                val row = yy * w
                for (xx in xa..xb) {
                    val p = px[row + xx]
                    val wv = wt[row + xx]
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    sr += r * wv; sg += g * wv; sb += b * wv; sw += wv
                    ur += r; ug += g; ub += b
                    n++
                }
            }
            out[y * w + x] = if (sw > 255) {
                (0xFF shl 24) or ((sr / sw) shl 16) or ((sg / sw) shl 8) or (sb / sw)
            } else {
                (0xFF shl 24) or ((ur / n) shl 16) or ((ug / n) shl 8) or (ub / n)
            }
        }
        return out
    }

    private fun discBlur(px: IntArray, w: Int, h: Int, rad: Int): IntArray {
        val out = IntArray(w * h)
        val xs = IntArray(2 * rad + 1)
        for (i in -rad..rad) xs[i + rad] = sqrt((rad * rad - i * i).toDouble()).toInt()
        parallelBands(h, 16) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (dy in -rad..rad) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    val dm = xs[dy + rad]
                    val xa = max(0, x - dm)
                    val xb = min(w - 1, x + dm)
                    val row = yy * w
                    for (xx in xa..xb) {
                        val p = px[row + xx]
                        sr += (p shr 16) and 255
                        sg += (p shr 8) and 255
                        sb += p and 255
                        n++
                    }
                }
                out[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            }
        }
        return out
    }

    private fun resizeFloat(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = min(fy.toInt(), sh - 2)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = min(fx.toInt(), sw - 2)
                val tx = fx - x0
                val a = src[y0 * sw + x0] * (1f - tx) + src[y0 * sw + x0 + 1] * tx
                val b = src[(y0 + 1) * sw + x0] * (1f - tx) + src[(y0 + 1) * sw + x0 + 1] * tx
                out[y * dw + x] = a * (1f - ty) + b * ty
            }
        }
        return out
    }

    // আসল ক্যামেরার মতো ব্লার: ফোকাস-তলের (মানুষের) কাছাকাছি জিনিস কম ঝাপসা, যত দূরে তত বেশি
    private fun depthBackground(base: Bitmap, mask: Bitmap, level: Int, depth: FloatArray): Bitmap {
        val s = 900f / max(base.width, base.height)
        val sw = max(12, (base.width * s).toInt())
        val sh = max(12, (base.height * s).toInt())
        val small = Bitmap.createScaledBitmap(base, sw, sh, true)
        val tw = max(8, sw / 3)
        val th = max(8, sh / 3)
        val third = Bitmap.createScaledBitmap(small, tw, th, true)
        small.recycle()
        val tp = IntArray(tw * th)
        third.getPixels(tp, 0, tw, 0, 0, tw, th)
        third.recycle()
        val mt = Bitmap.createScaledBitmap(mask, tw, th, true)
        val mtp = IntArray(tw * th)
        mt.getPixels(mtp, 0, tw, 0, 0, tw, th)
        mt.recycle()
        val wbg = dilatedWeights(mtp, tw, th, 2)
        val guide = FloatArray(tw * th) { lum(tp[it]) / 255f }

        // ডেপথ ম্যাপ ছবির কিনারায় বসানো
        val dUp = resizeFloat(depth, 256, 256, tw, th)
        val dRef = guidedFilter(guide, dUp, tw, th, 4, 0.001f)

        // মানুষের ডেপথ (মধ্যমান)
        val samples = ArrayList<Float>()
        var i0 = 0
        while (i0 < tw * th) {
            if ((mtp[i0] ushr 24) > 230) samples.add(dRef[i0])
            i0 += 3
        }
        val ds = if (samples.size > 20) { samples.sort(); samples[samples.size / 2] } else 0.8f

        // উজ্জ্বল আলো সামান্য বাড়ানো, তারপর মানুষের জায়গা ব্যাকগ্রাউন্ডের রঙে ভরা
        for (i in tp.indices) {
            val p = tp[i]
            var r = (p shr 16) and 255
            var g = (p shr 8) and 255
            var b = p and 255
            if (r > 238) r = min(255, r + (r - 238) * 2)
            if (g > 238) g = min(255, g + (g - 238) * 2)
            if (b > 238) b = min(255, b + (b - 238) * 2)
            tp[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val filled = fillBg(tp, wbg, tw, th)

        // কয়েকটা ঝাপসা স্তর, প্রতিটা পিক্সেলে দূরত্ব অনুযায়ী মিশিয়ে নেওয়া
        val radMax = max(3f, (3 + level * 0.12f) * 2f / 3f * 1.25f)
        val k = 5
        val layers = ArrayList<IntArray>()
        layers.add(filled)
        for (j in 1..k) layers.add(discBlur(filled, tw, th, max(1, Math.round(radMax * j / k))))
        val outPx = IntArray(tw * th)
        for (i in outPx.indices) {
            val diff = abs(dRef[i] - ds)
            val r01 = ((diff - 0.06f) / 0.5f).coerceIn(0f, 1f)
            val pos = r01 * k
            val lo = min(pos.toInt(), k - 1)
            val f = pos - lo
            val a = layers[lo][i]
            val b = layers[lo + 1][i]
            val nr = (((a shr 16) and 255) * (1f - f) + ((b shr 16) and 255) * f).toInt()
            val ng = (((a shr 8) and 255) * (1f - f) + ((b shr 8) and 255) * f).toInt()
            val nb = ((a and 255) * (1f - f) + (b and 255) * f).toInt()
            outPx[i] = (0xFF shl 24) or (nr.coerceIn(0, 255) shl 16) or (ng.coerceIn(0, 255) shl 8) or nb.coerceIn(0, 255)
        }
        val tb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        tb.setPixels(outPx, 0, tw, 0, 0, tw, th)
        val res = Bitmap.createScaledBitmap(tb, sw, sh, true)
        tb.recycle()
        return res
    }

    // লেন্সের মতো গোল ব্লার: মানুষের কাছে হালকা, দূরে বেশি। মানুষের রঙ কখনো ব্যাকগ্রাউন্ডে মেশে না
    private fun blurredBackground(base: Bitmap, mask: Bitmap, level: Int): Bitmap {
        val s = 900f / max(base.width, base.height)
        val sw = max(12, (base.width * s).toInt())
        val sh = max(12, (base.height * s).toInt())
        val small = Bitmap.createScaledBitmap(base, sw, sh, true)
        val tw = max(4, sw / 3)
        val th = max(4, sh / 3)
        val third = Bitmap.createScaledBitmap(small, tw, th, true)
        small.recycle()
        val tp = IntArray(tw * th)
        third.getPixels(tp, 0, tw, 0, 0, tw, th)
        third.recycle()

        val mt = Bitmap.createScaledBitmap(mask, tw, th, true)
        val mtp = IntArray(tw * th)
        mt.getPixels(mtp, 0, tw, 0, 0, tw, th)
        mt.recycle()
        val wbg = dilatedWeights(mtp, tw, th, 2)

        // হালকা আলো-ফুল (শুধু খুব উজ্জ্বল অংশে)
        for (i in tp.indices) {
            val p = tp[i]
            var r = (p shr 16) and 255
            var g = (p shr 8) and 255
            var b = p and 255
            if (r > 238) r = min(255, r + (r - 238) * 2)
            if (g > 238) g = min(255, g + (g - 238) * 2)
            if (b > 238) b = min(255, b + (b - 238) * 2)
            tp[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val rad = max(2, (3 + (level * 0.12f).toInt()) * 2 / 3)
        val filled = fillBg(tp, wbg, tw, th)
        val strongT = discBlur(filled, tw, th, rad)
        val mildT = discBlur(filled, tw, th, max(1, rad / 2))
        val sb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        sb.setPixels(strongT, 0, tw, 0, 0, tw, th)
        val strongBmp = Bitmap.createScaledBitmap(sb, sw, sh, true)
        sb.recycle()
        val mb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        mb.setPixels(mildT, 0, tw, 0, 0, tw, th)
        val mildBmp = Bitmap.createScaledBitmap(mb, sw, sh, true)
        mb.recycle()
        val a1 = IntArray(sw * sh)
        val b1 = IntArray(sw * sh)
        strongBmp.getPixels(a1, 0, sw, 0, 0, sw, sh)
        mildBmp.getPixels(b1, 0, sw, 0, 0, sw, sh)
        strongBmp.recycle()
        mildBmp.recycle()

        val ms = Bitmap.createScaledBitmap(mask, sw, sh, true)
        val mp = IntArray(sw * sh)
        ms.getPixels(mp, 0, sw, 0, 0, sw, sh)
        ms.recycle()
        for (i in mp.indices) {
            val a = mp[i] ushr 24
            mp[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }
        val gray = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        gray.setPixels(mp, 0, sw, 0, 0, sw, sh)
        val dist = boxBlur(gray, max(2, sw / 40), 2)
        val dp = IntArray(sw * sh)
        dist.getPixels(dp, 0, sw, 0, 0, sw, sh)

        val outPx = IntArray(sw * sh)
        for (i in outPx.indices) {
            val m = (dp[i] and 255) / 255f
            val wt = 1f - 0.45f * (m * 2.5f).coerceIn(0f, 1f)
            val s1 = a1[i]
            val m1 = b1[i]
            val r = (((s1 shr 16) and 255) * wt + ((m1 shr 16) and 255) * (1f - wt)).toInt()
            val g = (((s1 shr 8) and 255) * wt + ((m1 shr 8) and 255) * (1f - wt)).toInt()
            val b = ((s1 and 255) * wt + (m1 and 255) * (1f - wt)).toInt()
            outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val res = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        res.setPixels(outPx, 0, sw, 0, 0, sw, sh)
        return res
    }

    // ---------- ফেস স্মুদ ----------

    private fun smooth01(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun beautify(bmp: Bitmap, strength: Int) {
        val w = bmp.width
        val h = bmp.height
        val s = 640f / max(w, h)
        val small = Bitmap.createScaledBitmap(bmp, (w * s).toInt(), (h * s).toInt(), true)
        val faces = try {
            Tasks.await(faceDet.process(InputImage.fromBitmap(small, 0)))
        } catch (e: Throwable) {
            return
        }
        small.recycle()
        val k = strength / 100f
        for (f in faces) {
            val box = f.boundingBox
            val fw = box.width() / s
            val fh = box.height() / s
            val cx = box.centerX() / s
            val cy = box.centerY() / s - fh * 0.05f
            val rw = (fw * 1.25f).toInt()
            val rh = (fh * 1.45f).toInt()
            val rx = (cx - rw / 2f).toInt().coerceIn(0, w - 2)
            val ry = (cy - rh / 2f).toInt().coerceIn(0, h - 2)
            val cw = min(rw, w - rx)
            val chh = min(rh, h - ry)
            if (cw < 20 || chh < 20) continue
            val crop = Bitmap.createBitmap(bmp, rx, ry, cw, chh)
            val rad = max(2, (fw / 55f).toInt())
            val blurred = boxBlur(crop, rad, 2)
            val po = IntArray(cw * chh)
            val pb = IntArray(cw * chh)
            crop.getPixels(po, 0, cw, 0, 0, cw, chh)
            blurred.getPixels(pb, 0, cw, 0, 0, cw, chh)
            for (y in 0 until chh) for (x in 0 until cw) {
                val nx = (x - cw / 2f) / (cw / 2f)
                val ny = (y - chh / 2f) / (chh / 2f)
                val d = nx * nx + ny * ny
                if (d >= 1f) continue
                val fall = min(1f, (1f - d) * 3f)
                val i = y * cw + x
                val o = po[i]
                val r = (o shr 16) and 255
                val g = (o shr 8) and 255
                val b = o and 255
                val cb = (128 * 256 - 43 * r - 85 * g + 128 * b) shr 8
                val cr = (128 * 256 + 128 * r - 107 * g - 21 * b) shr 8
                if (cb < 77 || cb > 127 || cr < 133 || cr > 173) continue
                val bl = pb[i]
                val diff = abs(lum(o) - lum(bl)).toFloat()
                val edge = 1f - smooth01((diff - 3f) / 14f)
                val wgt = k * 0.9f * fall * edge
                val nr = (r + (((bl shr 16) and 255) - r) * wgt).toInt().coerceIn(0, 255)
                val ng = (g + (((bl shr 8) and 255) - g) * wgt).toInt().coerceIn(0, 255)
                val nb = (b + ((bl and 255) - b) * wgt).toInt().coerceIn(0, 255)
                po[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            bmp.setPixels(po, 0, cw, rx, ry, cw, chh)
            crop.recycle()
            blurred.recycle()
        }
    }

    // ---------- ফিল্টার ----------

    private fun applyFilter(bmp: Bitmap, idx: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            var r = ((p shr 16) and 255).toFloat()
            var g = ((p shr 8) and 255).toFloat()
            var b = (p and 255).toFloat()
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            when (idx) {
                1 -> { // Vivid
                    r = (l + (r - l) * 1.35f - 128f) * 1.08f + 128f
                    g = (l + (g - l) * 1.35f - 128f) * 1.08f + 128f
                    b = (l + (b - l) * 1.35f - 128f) * 1.08f + 128f
                }
                2 -> { r *= 1.07f; g *= 1.02f; b *= 0.92f } // Warm
                3 -> { r *= 0.92f; b *= 1.08f }             // Cool
                4 -> { val m = (l - 128f) * 1.1f + 128f; r = m; g = m; b = m } // Mono
                6 -> { // S25 look: হালকা ভিভিড
                    r = (l + (r - l) * 1.16f - 128f) * 1.04f + 128f + 3f
                    g = (l + (g - l) * 1.16f - 128f) * 1.04f + 128f + 3f
                    b = (l + (b - l) * 1.16f - 128f) * 1.04f + 128f + 3f
                }
                5 -> { // Film
                    r = (l * 0.5f + r * 0.5f) * 1.06f + 10f
                    g = (l * 0.5f + g * 0.5f) * 0.98f + 6f
                    b = (l * 0.5f + b * 0.5f) * 0.85f + 8f
                }
            }
            px[i] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    // ---------- JPEG ব্লক কমানো, নয়েজ কমানো, ভালো আপস্কেল ----------

    private class SrOut(val px: IntArray, val w: Int, val h: Int)

    // মানুষ কোথায় তা খুঁজে বের করা (বক্স)
    private fun personBox(px: IntArray, w: Int, h: Int): Rect? {
        return try {
            val s = min(1f, 512f / max(w, h))
            val sw = max(16, (w * s).toInt())
            val sh = max(16, (h * s).toInt())
            val sp = IntArray(sw * sh)
            for (y in 0 until sh) {
                val yy = min(h - 1, (y / s).toInt())
                for (x in 0 until sw) sp[y * sw + x] = px[yy * w + min(w - 1, (x / s).toInt())]
            }
            val sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            sb.setPixels(sp, 0, sw, 0, 0, sw, sh)
            val m = Tasks.await(segmenter.process(InputImage.fromBitmap(sb, 0)))
            sb.recycle()
            val buf = m.buffer
            val mw = m.width
            val mh = m.height
            val conf = FloatArray(mw * mh)
            for (i in conf.indices) conf[i] = buf.float
            cleanMask(conf, mw, mh)
            var x0 = mw
            var y0 = mh
            var x1 = -1
            var y1 = -1
            var cnt = 0
            for (y in 0 until mh) for (x in 0 until mw) {
                if (conf[y * mw + x] > 0.5f) {
                    cnt++
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
            if (x1 < 0 || cnt < mw * mh * 0.03f) return null
            val mxm = (x1 - x0) * 0.12f
            val mym = (y1 - y0) * 0.12f
            val fx = w.toFloat() / mw
            val fy = h.toFloat() / mh
            Rect(
                max(0, ((x0 - mxm) * fx).toInt()), max(0, ((y0 - mym) * fy).toInt()),
                min(w, ((x1 + 1 + mxm) * fx).toInt()), min(h, ((y1 + 1 + mym) * fy).toInt())
            )
        } catch (e: Throwable) {
            null
        }
    }

    // শুধু মানুষের অংশে AI, বাকিটা সাধারণ আপস্কেল (ব্যাকগ্রাউন্ড তো ব্লার হবে)
    private fun aiSubject(px: IntArray, w: Int, h: Int, box: Rect, nw: Int, nh: Int, mode: Int, sr: SuperRes, progress: (String) -> Unit): SrOut? {
        val bw = box.width()
        val bh = box.height()
        if (bw < 32 || bh < 32) return null
        val sub = IntArray(bw * bh)
        for (y in 0 until bh) System.arraycopy(px, (box.top + y) * w + box.left, sub, y * bw, bw)
        val r = aiUpscale(sub, bw, bh, mode, sr, 90, progress) ?: return null
        progress("ছবি সাজানো হচ্ছে…")
        val base = lanczos(px, w, h, nw, nh)
        val fxs = nw.toFloat() / w
        val fys = nh.toFloat() / h
        val tx0 = (box.left * fxs).toInt()
        val ty0 = (box.top * fys).toInt()
        val tw = max(2, (bw * fxs).toInt())
        val th = max(2, (bh * fys).toInt())
        val reg = if (r.w == tw && r.h == th) r.px else lanczos(r.px, r.w, r.h, tw, th)
        val feather = max(4, min(tw, th) / 20)
        for (y in 0 until th) {
            val dy = ty0 + y
            if (dy >= nh) break
            val ey = min(y, th - 1 - y)
            for (x in 0 until tw) {
                val dx = tx0 + x
                if (dx >= nw) break
                val e = min(min(x, tw - 1 - x), ey)
                val q = reg[y * tw + x]
                if (e >= feather) {
                    base[dy * nw + dx] = q
                } else {
                    val a = e / feather.toFloat()
                    val o = base[dy * nw + dx]
                    val nr = (((o shr 16) and 255) + (((q shr 16) and 255) - ((o shr 16) and 255)) * a).toInt()
                    val ng = (((o shr 8) and 255) + (((q shr 8) and 255) - ((o shr 8) and 255)) * a).toInt()
                    val nb = ((o and 255) + ((q and 255) - (o and 255)) * a).toInt()
                    base[dy * nw + dx] = (0xFF shl 24) or (nr.coerceIn(0, 255) shl 16) or
                        (ng.coerceIn(0, 255) shl 8) or nb.coerceIn(0, 255)
                }
            }
        }
        note = note + " — শুধু মানুষের অংশে"
        return SrOut(base, nw, nh)
    }

    private fun aiUpscale(px: IntArray, w: Int, h: Int, mode: Int, sr: SuperRes, maxSec: Int, progress: (String) -> Unit): SrOut? {
        var ipx = px
        var iw = w
        var ih = h
        val maxPix = 1_000_000L
        if (iw.toLong() * ih > maxPix) {
            val sc = sqrt(maxPix.toDouble() / (iw.toLong() * ih))
            val nw = max(16, (iw * sc).toInt())
            val nh = max(16, (ih * sc).toInt())
            progress("AI-এর জন্য ছবি ঠিক করা হচ্ছে…")
            ipx = lanczos(ipx, iw, ih, nw, nh)
            iw = nw
            ih = nh
        }
        val model = if (mode == 2) "sr_hq.tflite" else "sr_fast.tflite"
        val res = sr.upscale(ipx, iw, ih, model, maxSec) { d, t -> progress("AI আপস্কেল $d/$t") }
        if (res == null) {
            note = "AI আপস্কেল চলেনি বা খুব ধীর ছিল, সাধারণ আপস্কেল হয়েছে"
            return null
        }
        note = "AI আপস্কেল হয়েছে (" + (if (sr.usingGpu) "GPU" else "CPU") + ")"
        return SrOut(res, iw * 4, ih * 4)
    }

    // ছবি উজ্জ্বল করা (অন্ধকার ছবিতে বেশি)। রিটার্ন: কতটা উজ্জ্বল করা হলো (০-১)
    private fun brighten(px: IntArray, w: Int, h: Int, amount: Int, meanL: Int): Float {
        val auto = if (meanL < 95) (95 - meanL) / 95f * 0.4f else 0f
        val k = (auto + amount / 100f * 0.5f).coerceIn(0f, 1f)
        if (k < 0.02f) return 0f
        val gamma = 1f / (1f + k * 0.9f)
        val lut = IntArray(256) { (255f * (it / 255f).pow(gamma)).toInt().coerceIn(0, 255) }
        parallelBands(px.size, 200000) { a0, a1 ->
        for (i in a0 until a1) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val y = (77 * r + 150 * g + 29 * b) shr 8
            if (y < 1) continue
            var f = (lut[y] + 0.5f) / (y + 0.5f)
            if (f > 2.5f) f = 2.5f
            val mx = max(r, max(g, b))
            if (mx * f > 255f) f = 255f / mx
            val nr = min(255, (r * f).toInt())
            val ng = min(255, (g * f).toInt())
            val nb = min(255, (b * f).toInt())
            px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        }
        return k
    }

    // গ্যালারির ছবি: নয়েজ কমানো + AI আপস্কেল + উজ্জ্বল + ডিটেইলস
    fun enhance(src: Bitmap, ai: Int, bright: Int, detail: Int, nrBoost: Float, sr: SuperRes?, progress: (String) -> Unit): Bitmap {
        note = ""
        var w = src.width
        var h = src.height
        var px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        var msum = 0L
        var mcnt = 0
        var si = 0
        while (si < px.size) { msum += lum(px[si]); mcnt++; si += 37 }
        val meanL = if (mcnt > 0) (msum / mcnt).toInt() else 120
        val kb = brighten(px, w, h, bright, meanL)
        progress("নয়েজ কমানো হচ্ছে…")
        val wantAi = ai > 0 && sr != null
        var sigma = max(estimateSigma(px, w, h), 1.5f) * (1f + nrBoost * 2f).coerceIn(0.5f, 1.8f)
        if (wantAi) sigma *= 0.6f
        denoise(px, w, h, sigma)
        var upscaled = false
        if (wantAi) {
            if (w.toLong() * h <= 1_300_000L) {
                val r = aiUpscale(px, w, h, 2, sr!!, 150, progress)
                if (r != null) {
                    px = r.px
                    w = r.w
                    h = r.h
                    upscaled = true
                    if (w.toLong() * h > 16_000_000L) {
                        val sc = sqrt(16_000_000.0 / (w.toLong() * h))
                        val nw = max(2, (w * sc).toInt() and 1.inv())
                        val nh = max(2, (h * sc).toInt() and 1.inv())
                        px = lanczos(px, w, h, nw, nh)
                        w = nw
                        h = nh
                    }
                }
            } else {
                note = "ছবিটা বড় (১.৩ মেগাপিক্সেলের বেশি), তাই AI আপস্কেল বাদ। নয়েজ ও আলো ঠিক হয়েছে"
            }
        }
        progress("রং ও ডিটেইলস ঠিক করা হচ্ছে…")
        tonemap(px, w, h, true, false)
        val dk = detail / 100f
        clarity(px, w, h, dk * 0.3f * (if (upscaled) 0.6f else 1f))
        sharpen2(px, w, h, (0.4f + dk * 0.8f) * (if (upscaled) 0.5f else 1f), (0.8f * sigma + 2f).toInt().coerceIn(2, 10))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun smoothPix(l: Int, c: Int, r: Int): Int {
        val nr = ((((l shr 16) and 255) + 2 * ((c shr 16) and 255) + ((r shr 16) and 255)) shr 2)
        val ng = ((((l shr 8) and 255) + 2 * ((c shr 8) and 255) + ((r shr 8) and 255)) shr 2)
        val nb = (((l and 255) + 2 * (c and 255) + (r and 255)) shr 2)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    // ৮x৮ JPEG ব্লকের কিনারা মসৃণ করা (জুমে "ফেটে যাওয়া" ভাব কমায়)
    private fun deblock(px: IntArray, w: Int, h: Int, ox: Int, oy: Int) {
        val t = 14
        var x = (8 - (ox % 8)) % 8
        if (x < 2) x += 8
        while (x < w - 2) {
            for (y in 0 until h) {
                val i = y * w + x
                val a = px[i - 1]
                val b = px[i]
                if (abs(lum(a) - lum(b)) < t) {
                    val a2 = px[i - 2]
                    val b2 = px[i + 1]
                    px[i - 1] = smoothPix(a2, a, b)
                    px[i] = smoothPix(a, b, b2)
                }
            }
            x += 8
        }
        var y = (8 - (oy % 8)) % 8
        if (y < 2) y += 8
        while (y < h - 2) {
            for (xx in 0 until w) {
                val i = y * w + xx
                val a = px[i - w]
                val b = px[i]
                if (abs(lum(a) - lum(b)) < t) {
                    val a2 = px[i - 2 * w]
                    val b2 = px[i + w]
                    px[i - w] = smoothPix(a2, a, b)
                    px[i] = smoothPix(a, b, b2)
                }
            }
            y += 8
        }
    }

    private fun blurT1(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        parallelBands(h, 32) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var s = 0
                for (i in -r..r) s += src[row + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    dst[x * h + y] = s / div
                    s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
                }
            }
        }
    }

    private fun blurGray(a: IntArray, w: Int, h: Int, r: Int, passes: Int) {
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurT1(a, tmp, w, h, r)
            blurT1(tmp, a, h, w, r)
        }
    }

    // ছবির আসল নয়েজ কতটা তা মাপা (ব্রাইটনেস নয়েজের σ)। এর ওপর ভিত্তি করেই নয়েজ কমানো হয়
    private fun estimateSigma(px: IntArray, w: Int, h: Int): Float {
        if (w < 32 || h < 32) return 2f
        val n = 20000
        val vals = IntArray(n)
        var seed = 12345L
        for (i in 0 until n) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val rx = ((seed ushr 33) % (w - 4).toLong()).toInt() + 2
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val ry = ((seed ushr 33) % (h - 4).toLong()).toInt() + 2
            val c = lum(px[ry * w + rx])
            val sum = lum(px[ry * w + rx - 1]) + lum(px[ry * w + rx + 1]) +
                lum(px[(ry - 1) * w + rx]) + lum(px[(ry + 1) * w + rx])
            vals[i] = abs(4 * c - sum)
        }
        vals.sort()
        val p25 = vals[n / 4] / 4f
        return (p25 * 2.8f).coerceIn(0.6f, 14f)
    }

    private fun denoise(px: IntArray, w: Int, h: Int, sigma: Float) {
        if (w < 16 || h < 16) return
        val s = sigma.coerceIn(0.6f, 14f)
        chromaDenoise(px, w, h, s)
        if (s >= 1.8f) lumaDenoise(px, w, h, s)
        if (s > 6f) lumaDenoise(px, w, h, s * 0.8f)
    }

    private fun chromaDenoise(px: IntArray, w: Int, h: Int, s: Float) {
        val cw = w / 2
        val ch = h / 2
        val cb = IntArray(cw * ch)
        val cr = IntArray(cw * ch)
        parallelBands(ch, 32) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until cw) {
                var sb = 0
                var sr = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val p = px[(2 * y + dy) * w + 2 * x + dx]
                    val yy = lum(p)
                    sb += (p and 255) - yy
                    sr += ((p shr 16) and 255) - yy
                }
                cb[y * cw + x] = sb shr 2
                cr[y * cw + x] = sr shr 2
            }
        }
        val rc = (1 + (s / 2f).toInt()).coerceIn(2, 6)
        blurGray(cb, cw, ch, rc, 2)
        blurGray(cr, cw, ch, rc, 2)
        val wFlat = ((0.35f + 0.55f * min(s / 10f, 1f)) * 256f).toInt()
        val wEdge = (0.3f * 256f).toInt()
        parallelBands(h, 32) { y0, y1 ->
            for (y in y0 until y1) {
                val cy = min(y / 2, ch - 1)
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    val p = px[i]
                    val yc = lum(p)
                    val g = abs(lum(px[i + 1]) - lum(px[i - 1]))
                    val wc = if (g > 30) wEdge else wFlat
                    val ci = cy * cw + min(x / 2, cw - 1)
                    val cbo = (p and 255) - yc
                    val cro = ((p shr 16) and 255) - yc
                    val cbn = cbo + (((cb[ci] - cbo) * wc) shr 8)
                    val crn = cro + (((cr[ci] - cro) * wc) shr 8)
                    val r = (yc + crn).coerceIn(0, 255)
                    val b = (yc + cbn).coerceIn(0, 255)
                    val gg = (((yc shl 8) - 77 * r - 29 * b) / 150).coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
                }
            }
        }
    }

    private fun lumaDenoise(px: IntArray, w: Int, h: Int, s: Float) {
        val thr = (1.6f * s + 1.5f).toInt().coerceIn(3, 22)
        parallelBands(h, 64) { y0, y1 ->
            var prevY = IntArray(w)
            var curY = IntArray(w)
            var nextY = IntArray(w)
            val ys = max(0, y0 - 1)
            for (x in 0 until w) {
                curY[x] = lum(px[y0 * w + x])
                prevY[x] = lum(px[ys * w + x])
            }
            for (y in y0 until y1) {
                val ny = if (y + 1 < h) y + 1 else y
                for (x in 0 until w) nextY[x] = lum(px[ny * w + x])
                val row = y * w
                for (x in 1 until w - 1) {
                    val yc = curY[x]
                    var sum = yc * 2
                    var n = 2
                    var v = prevY[x - 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    v = prevY[x]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    v = prevY[x + 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    v = curY[x - 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    v = curY[x + 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    v = nextY[x - 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    v = nextY[x]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    v = nextY[x + 1]; if (abs(v - yc) <= thr) { sum += v; n++ }
                    val d = sum / n - yc
                    if (d != 0) {
                        val p = px[row + x]
                        val r = (((p shr 16) and 255) + d).coerceIn(0, 255)
                        val g = (((p shr 8) and 255) + d).coerceIn(0, 255)
                        val b = ((p and 255) + d).coerceIn(0, 255)
                        px[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                val t = prevY
                prevY = curY
                curY = nextY
                nextY = t
            }
        }
    }

    // ছবির "পপ": বড় আকারের স্থানীয় কনট্রাস্ট, ডিটেইলস ফুটে ওঠে
    private fun clarity(px: IntArray, w: Int, h: Int, k: Float) {
        if (k < 0.02f || w < 64 || h < 64) return
        val gw = max(16, w / 8)
        val gh = max(16, h / 8)
        val grid = lumaGrid(px, w, h, gw, gh, 0, 0)
        blurF(grid, gw, gh, 2, 1)
        parallelBands(h, 32) { y0, y1 ->
            val bil = Bil(w, h, gw, gh)
            for (y in y0 until y1) {
                bil.row(y)
                val row = y * w
                for (x in 0 until w) {
                    val p = px[row + x]
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    val yy = (77 * r + 150 * g + 29 * b) shr 8
                    val d = ((yy - bil.at(grid, x)) * k).toInt().coerceIn(-14, 14)
                    if (d != 0) {
                        px[row + x] = (0xFF shl 24) or ((r + d).coerceIn(0, 255) shl 16) or
                            ((g + d).coerceIn(0, 255) shl 8) or (b + d).coerceIn(0, 255)
                    }
                }
            }
        }
    }

    // ধারালো করা (৩x৩), নয়েজের মাপ অনুযায়ী ছোট দানা বাদ দিয়ে
    private fun sharpen2(px: IntArray, w: Int, h: Int, amt: Float, core: Int) {
        val k = (amt * 256f).toInt()
        parallelBands(h, 64) { y0, y1 ->
            var prev = IntArray(w)
            var cur = IntArray(w)
            var next = IntArray(w)
            System.arraycopy(px, max(0, y0 - 1) * w, prev, 0, w)
            System.arraycopy(px, y0 * w, cur, 0, w)
            for (y in y0 until y1) {
                val ny = if (y + 1 < h) y + 1 else y
                System.arraycopy(px, ny * w, next, 0, w)
                val base = y * w
                for (x in 1 until w - 1) {
                    val c = cur[x]
                    val a1 = prev[x - 1]; val a2 = prev[x]; val a3 = prev[x + 1]
                    val b1 = cur[x - 1]; val b3 = cur[x + 1]
                    val c1 = next[x - 1]; val c2 = next[x]; val c3 = next[x + 1]
                    val ar = (((a1 shr 16) and 255) + ((a2 shr 16) and 255) + ((a3 shr 16) and 255) +
                        ((b1 shr 16) and 255) + ((b3 shr 16) and 255) +
                        ((c1 shr 16) and 255) + ((c2 shr 16) and 255) + ((c3 shr 16) and 255)) shr 3
                    val ag = (((a1 shr 8) and 255) + ((a2 shr 8) and 255) + ((a3 shr 8) and 255) +
                        ((b1 shr 8) and 255) + ((b3 shr 8) and 255) +
                        ((c1 shr 8) and 255) + ((c2 shr 8) and 255) + ((c3 shr 8) and 255)) shr 3
                    val ab = ((a1 and 255) + (a2 and 255) + (a3 and 255) + (b1 and 255) + (b3 and 255) +
                        (c1 and 255) + (c2 and 255) + (c3 and 255)) shr 3
                    val nr = sh2((c shr 16) and 255, ar, k, core)
                    val ng = sh2((c shr 8) and 255, ag, k, core)
                    val nb = sh2(c and 255, ab, k, core)
                    px[base + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
                val t = prev
                prev = cur
                cur = next
                next = t
            }
        }
    }

    private fun sh2(v: Int, avg: Int, k: Int, core: Int): Int {
        val d = v - avg
        val ad = abs(d)
        if (ad <= core) return v
        val dd = min(((ad - core) * k) shr 9, 24)
        return (if (d > 0) v + dd else v - dd).coerceIn(0, 255)
    }

    // ---------- সুপার-রেজোলিউশন জুম (Pixel-এর Super Res Zoom-এর মতো) ----------
    // অনেক ছবির প্রতিটার সূক্ষ্ম সরণ (হাতের কাঁপুনি) মেপে, সবগুলো একটা বড় গ্রিডে বসিয়ে আসল ডিটেইলস বের করা

    private fun lumaF(px: IntArray): FloatArray {
        val o = FloatArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            o[i] = (77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) / 256f
        }
        return o
    }

    private fun box3(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                tmp[r + x] = (src[r + max(0, x - 1)] + src[r + x] + src[r + min(w - 1, x + 1)]) / 3f
            }
        }
        for (y in 0 until h) {
            val y0 = max(0, y - 1) * w
            val y1 = y * w
            val y2 = min(h - 1, y + 1) * w
            for (x in 0 until w) out[y1 + x] = (tmp[y0 + x] + tmp[y1 + x] + tmp[y2 + x]) / 3f
        }
        return out
    }

    // Lucas-Kanade: পূর্ণসংখ্যার সরণ থেকে শুরু করে ভগ্নাংশ পিক্সেল পর্যন্ত নির্ভুল সরণ
    private fun refineShift(rs: FloatArray, cs: FloatArray, w: Int, h: Int, t0x: Float, t0y: Float): FloatArray {
        var tx = t0x
        var ty = t0y
        val margin = 16
        val step = 2
        var resid = 0f
        for (iter in 0 until 8) {
            var a11 = 0.0
            var a12 = 0.0
            var a22 = 0.0
            var b1 = 0.0
            var b2 = 0.0
            var sse = 0.0
            var n = 0
            var y = margin
            while (y < h - margin) {
                var x = margin
                while (x < w - margin) {
                    val fx = x + tx
                    val fy = y + ty
                    val x0 = floor(fx).toInt()
                    val y0 = floor(fy).toInt()
                    if (x0 < 1 || y0 < 1 || x0 >= w - 2 || y0 >= h - 2) { x += step; continue }
                    val ax = fx - x0
                    val ay = fy - y0
                    val i = y0 * w + x0
                    val v00 = cs[i]
                    val v10 = cs[i + 1]
                    val v01 = cs[i + w]
                    val v11 = cs[i + w + 1]
                    val wv = (v00 * (1f - ax) + v10 * ax) * (1f - ay) + (v01 * (1f - ax) + v11 * ax) * ay
                    val gx = (v10 - v00) * (1f - ay) + (v11 - v01) * ay
                    val gy = (v01 - v00) * (1f - ax) + (v11 - v10) * ax
                    val e = rs[y * w + x] - wv
                    a11 += gx * gx
                    a12 += gx * gy
                    a22 += gy * gy
                    b1 += gx * e
                    b2 += gy * e
                    sse += e * e
                    n++
                    x += step
                }
                y += step
            }
            if (n < 200) break
            val det = a11 * a22 - a12 * a12 + 1e-6
            val dx = (a22 * b1 - a12 * b2) / det
            val dy = (a11 * b2 - a12 * b1) / det
            tx += dx.toFloat()
            ty += dy.toFloat()
            resid = sqrt(sse / n).toFloat()
            if (abs(dx) < 0.005 && abs(dy) < 0.005) break
        }
        return floatArrayOf(tx, ty, resid)
    }

    private fun sampleBil(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = floor(fx).toInt().coerceIn(0, w - 2)
        val y0 = floor(fy).toInt().coerceIn(0, h - 2)
        val ax = (fx - x0).coerceIn(0f, 1f)
        val ay = (fy - y0).coerceIn(0f, 1f)
        val i = y0 * w + x0
        val p00 = px[i]
        val p10 = px[i + 1]
        val p01 = px[i + w]
        val p11 = px[i + w + 1]
        var out = 0xFF shl 24
        var sh = 16
        while (sh >= 0) {
            val a = (p00 shr sh) and 255
            val b = (p10 shr sh) and 255
            val c = (p01 shr sh) and 255
            val d = (p11 shr sh) and 255
            val v = (a * (1f - ax) + b * ax) * (1f - ay) + (c * (1f - ax) + d * ax) * ay
            out = out or ((v + 0.5f).toInt().coerceIn(0, 255) shl sh)
            sh -= 8
        }
        return out
    }

    private fun splatFrame(
        px: IntArray, w: Int, h: Int, gw: Int, gh: Int, g: Float, tx: Float, ty: Float,
        wt: Array<FloatArray>, q: Int,
        accR: FloatArray, accG: FloatArray, accB: FloatArray, accW: FloatArray,
        refL: FloatArray, curL: FloatArray?, ghostThr: Float
    ) {
        parallelBands(gh, 24) { g0, g1 ->
        for (y in 0 until h) {
            val yy = (y - ty + 0.5f) * g - 0.5f
            val yf = floor(yy)
            val yi = yf.toInt()
            if (yi + 2 < g0 || yi - 1 >= g1) continue
            val wy = wt[((yy - yf) * q).toInt().coerceIn(0, q - 1)]
            for (x in 0 until w) {
                val i = y * w + x
                if (curL != null) {
                    val rx = x - tx
                    val ry = y - ty
                    val x0 = floor(rx).toInt()
                    val y0 = floor(ry).toInt()
                    if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) continue
                    val ax = rx - x0
                    val ay = ry - y0
                    val j = y0 * w + x0
                    val rv = (refL[j] * (1f - ax) + refL[j + 1] * ax) * (1f - ay) +
                        (refL[j + w] * (1f - ax) + refL[j + w + 1] * ax) * ay
                    if (abs(curL[i] - rv) > ghostThr) continue
                }
                val xx = (x - tx + 0.5f) * g - 0.5f
                val xf = floor(xx)
                val xi = xf.toInt()
                val wx = wt[((xx - xf) * q).toInt().coerceIn(0, q - 1)]
                val p = px[i]
                val r = ((p shr 16) and 255).toFloat()
                val gg = ((p shr 8) and 255).toFloat()
                val b = (p and 255).toFloat()
                for (a in 0 until 4) {
                    val gy = yi - 1 + a
                    if (gy < max(0, g0) || gy >= min(gh, g1)) continue
                    val wyv = wy[a]
                    val row = gy * gw
                    for (c in 0 until 4) {
                        val gx = xi - 1 + c
                        if (gx < 0 || gx >= gw) continue
                        val wv = wyv * wx[c]
                        val k = row + gx
                        accR[k] += r * wv
                        accG[k] += gg * wv
                        accB[k] += b * wv
                        accW[k] += wv
                    }
                }
            }
        }
        }
    }

    private fun window(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, cw: Int, ch: Int): IntArray {
        val out = IntArray(cw * ch)
        for (y in 0 until ch) {
            val sy = (y0 + y).coerceIn(0, h - 1)
            for (x in 0 until cw) out[y * cw + x] = px[sy * w + (x0 + x).coerceIn(0, w - 1)]
        }
        return out
    }

    private fun superResolve(shots: List<Shot>, rect: Rect, sample: Int, forceG: Float): Merged {
        if (shots.size < 3) return decode(shots[0], rect, sample)
        val dec = BitmapRegionDecoder.newInstance(shots[0].jpeg, 0, shots[0].jpeg.size, false)
        val fw = dec.width
        val fh = dec.height
        dec.recycle()
        val cw = rect.width() / sample
        val ch = rect.height() / sample
        // অনেক বড় জুমে ক্রপ খুব ছোট, তাই ছবি মেলাতে মাঝের বড় অংশ ব্যবহার করি
        var roi = rect
        if (sample == 1 && rect.width() < fw / 3.2f) {
            val rw = (fw / 3.2f).toInt() and 1.inv()
            val rh = (fh / 3.2f).toInt() and 1.inv()
            val rx = ((fw - rw) / 2) and 1.inv()
            val ry = ((fh - rh) / 2) and 1.inv()
            roi = Rect(rx, ry, rx + rw, ry + rh)
        }
        val ref = decode(shots[0], roi, sample)
        val w1 = ref.w
        val h1 = ref.h
        if (w1 < 96 || h1 < 96 || cw < 16 || ch < 16) return decode(shots[0], rect, sample)
        val cx0 = (rect.left - roi.left) / sample
        val cy0 = (rect.top - roi.top) / sample
        val refCrop = window(ref.px, w1, h1, cx0, cy0, cw, ch)
        val g = if (forceG > 0f) forceG else min(2f, sqrt(8_000_000f / (cw.toFloat() * ch)))
        if (forceG == 0f && g < 1.15f) return merge(shots, rect, sample, 1, 100)
        val gw = max(2, (cw * g).toInt())
        val gh = max(2, (ch * g).toInt())
        val refL = lumaF(ref.px)
        val refS = box3(refL, w1, h1)
        val refCropL = lumaF(refCrop)
        val cf = if (min(w1, h1) >= 600) 8 else 4
        val ff = max(2, cf / 2)
        val rg = gradSmall(ref.px, w1, h1, cf)
        val rg2 = gradSmall(ref.px, w1, h1, ff)
        val accR = FloatArray(gw * gh)
        val accG = FloatArray(gw * gh)
        val accB = FloatArray(gw * gh)
        val accW = FloatArray(gw * gh)
        val q = 16
        val sigma = max(0.5f, 0.3f * g)
        val wt = Array(q) { qi ->
            FloatArray(4) { t ->
                val d = (t - 1) - qi / q.toFloat()
                exp(-(d * d) / (2f * sigma * sigma))
            }
        }
        splatFrame(refCrop, cw, ch, gw, gh, g, 0f, 0f, wt, q, accR, accG, accB, accW, refCropL, null, 0f)
        var used = 1
        for (k in 1 until shots.size) {
            val cur = try { decode(shots[k], roi, sample) } catch (e: Throwable) { continue }
            if (cur.w != w1 || cur.h != h1) continue
            val cg = gradSmall(cur.px, w1, h1, cf)
            val s1 = findShift(rg, cg, 16, 0, 0)
            val ratio = cf / ff
            val cg2 = gradSmall(cur.px, w1, h1, ff)
            val s2 = findShift(rg2, cg2, 3, s1[0] * ratio, s1[1] * ratio)
            val curS = box3(lumaF(cur.px), w1, h1)
            val r = refineShift(refS, curS, w1, h1, (s2[0] * ff).toFloat(), (s2[1] * ff).toFloat())
            if (r[2] > 14f || abs(r[0]) > 40f || abs(r[1]) > 40f) continue   // ছবি মেলেনি (নড়ে গেছে)
            val ix = Math.round(r[0])
            val iy = Math.round(r[1])
            val curCrop = window(cur.px, w1, h1, cx0 + ix, cy0 + iy, cw, ch)
            val curCropL = lumaF(curCrop)
            splatFrame(curCrop, cw, ch, gw, gh, g, r[0] - ix, r[1] - iy, wt, q, accR, accG, accB, accW, refCropL, curCropL, 30f)
            used++
        }
        if (used < 2) {
            val one = Merged(refCrop, cw, ch)
            one.used = 1
            return one
        }
        val out = IntArray(gw * gh)
        for (gy in 0 until gh) {
            val ry = (gy + 0.5f) / g - 0.5f
            for (gx in 0 until gw) {
                val k = gy * gw + gx
                val wv = accW[k]
                if (wv >= 0.6f) {
                    out[k] = (0xFF shl 24) or
                        ((accR[k] / wv + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((accG[k] / wv + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                        (accB[k] / wv + 0.5f).toInt().coerceIn(0, 255)
                } else {
                    val rx = (gx + 0.5f) / g - 0.5f
                    val base = sampleBil(refCrop, cw, ch, rx, ry)
                    if (wv > 0.05f) {
                        val a = wv / 0.6f
                        val sr = (accR[k] / wv).coerceIn(0f, 255f)
                        val sg = (accG[k] / wv).coerceIn(0f, 255f)
                        val sb = (accB[k] / wv).coerceIn(0f, 255f)
                        val nr = (((base shr 16) and 255) * (1f - a) + sr * a).toInt().coerceIn(0, 255)
                        val ng = (((base shr 8) and 255) * (1f - a) + sg * a).toInt().coerceIn(0, 255)
                        val nb = ((base and 255) * (1f - a) + sb * a).toInt().coerceIn(0, 255)
                        out[k] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    } else {
                        out[k] = base
                    }
                }
            }
        }
        val res = Merged(out, gw, gh)
        res.scale = g
        res.used = used
        return res
    }

    // ---------- নতুন HDR: মূল ছবির ডিটেইলস রেখে আলো-ছায়া ঠিক করা ----------

    private fun lumaLow(px: IntArray, w: Int, h: Int, lf: Int, dx: Int, dy: Int): FloatArray {
        val sw = w / lf
        val sh = h / lf
        val out = FloatArray(sw * sh)
        val hf = lf / 2
        for (y in 0 until sh) for (x in 0 until sw) {
            var s = 0
            for (oy in 0..1) for (ox in 0..1) {
                val xx = (x * lf + ox * hf + dx).coerceIn(0, w - 1)
                val yy = (y * lf + oy * hf + dy).coerceIn(0, h - 1)
                s += lum(px[yy * w + xx])
            }
            out[y * sw + x] = s / 4f
        }
        return out
    }

    private fun blurTF(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val div = (2 * r + 1).toFloat()
        parallelBands(h, 32) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var s = 0f
                for (i in -r..r) s += src[row + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    dst[x * h + y] = s / div
                    s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
                }
            }
        }
    }

    private fun blurF(a: FloatArray, w: Int, h: Int, r: Int, passes: Int) {
        val tmp = FloatArray(w * h)
        repeat(passes) {
            blurTF(a, tmp, w, h, r)
            blurTF(tmp, a, h, w, r)
        }
    }

    private fun mergeHdr(shots: List<Shot>, rect: Rect, sample: Int): Merged {
        val ref = decode(shots[0], rect, sample)
        if (shots.size < 2) return ref
        val w = ref.w
        val h = ref.h
        val lf = if (min(w, h) >= 800) 8 else 4
        val sw = w / lf
        val sh = h / lf
        if (sw < 8 || sh < 8) return ref
        val cf = if (min(w, h) >= 600) 8 else 4
        val ff = max(2, cf / 2)
        val rg = gradSmall(ref.px, w, h, cf)
        val rg2 = gradSmall(ref.px, w, h, ff)
        val lr = lumaLow(ref.px, w, h, lf, 0, 0)
        val shiftX = IntArray(shots.size)
        val shiftY = IntArray(shots.size)
        val okf = BooleanArray(shots.size)
        val lows = arrayOfNulls<FloatArray>(shots.size)
        for (k in 1 until shots.size) {
            val cur = try { decode(shots[k], rect, sample) } catch (e: Throwable) { continue }
            if (cur.w != w || cur.h != h) continue
            val cg = gradSmall(cur.px, w, h, cf)
            val s1 = findShift(rg, cg, 16, 0, 0)
            val ratio = cf / ff
            val cg2 = gradSmall(cur.px, w, h, ff)
            val s2 = findShift(rg2, cg2, 3, s1[0] * ratio, s1[1] * ratio)
            shiftX[k] = s2[0] * ff
            shiftY[k] = s2[1] * ff
            // ছবি না মিললে (হাত বেশি নড়লে) এই ফ্রেম বাদ
            if (s1[2] < 850) {
                okf[k] = true
                lows[k] = lumaLow(cur.px, w, h, lf, shiftX[k], shiftY[k])
            }
        }
        if (!okf.any { it }) return ref

        val n = sw * sh
        val frames = ArrayList<FloatArray>()
        frames.add(lr)
        for (k in 1 until shots.size) { val l = lows[k]; if (l != null) frames.add(l) }
        val ld = if (shots.size > 1) lows[1] else null
        val lb = if (shots.size > 2) lows[2] else null
        val gR = FloatArray(n)
        val gD = FloatArray(n) { 1f }
        val gB = FloatArray(n) { 1f }
        for (i in 0 until n) {
            var num = 0f
            var den = 0f
            for (fr in frames) {
                val v = fr[i]
                val d = (v - 128f) / 58f
                val wgt = exp(-(d * d)) + 0.02f
                num += wgt * v
                den += wgt
            }
            val fused = num / den
            val r0 = max(lr[i], 4f)
            val t = r0 + (fused - r0) * 0.65f
            gR[i] = (t / r0).coerceIn(0.8f, 1.6f)
            if (ld != null) gD[i] = (t / max(ld[i], 4f)).coerceIn(0.4f, 3f)
            if (lb != null) gB[i] = (t / max(lb[i], 4f)).coerceIn(0.4f, 3f)
        }
        blurF(gR, sw, sh, 2, 2)
        blurF(gD, sw, sh, 2, 2)
        blurF(gB, sw, sh, 2, 2)

        val px = ref.px
        val bil = Bil(w, h, sw, sh)
        // ধাপ ১: আলো-ছায়া ঠিক করা + অতি-উজ্জ্বল জায়গায় কম-আলোর ছবির ডিটেইলস
        var dark: Merged? = if (shots.size > 1 && okf[1]) decode(shots[1], rect, sample) else null
        for (y in 0 until h) {
            bil.row(y)
            val row = y * w
            for (x in 0 until w) {
                val o = px[row + x]
                val r0 = (o shr 16) and 255
                val g0 = (o shr 8) and 255
                val b0 = o and 255
                val gain = bil.at(gR, x)
                var nr = r0 * gain
                var ng = g0 * gain
                var nb = b0 * gain
                val d = dark
                if (d != null) {
                    val mx = max(r0, max(g0, b0))
                    if (mx > 238) {
                        val c = smooth01((mx - 238f) / 14f)
                        val xx = (x + shiftX[1]).coerceIn(0, w - 1)
                        val yy = (y + shiftY[1]).coerceIn(0, h - 1)
                        val q = d.px[yy * w + xx]
                        val gd = bil.at(gD, x)
                        nr += (((q shr 16) and 255) * gd - nr) * c
                        ng += (((q shr 8) and 255) * gd - ng) * c
                        nb += ((q and 255) * gd - nb) * c
                    }
                }
                px[row + x] = (0xFF shl 24) or (nr.toInt().coerceIn(0, 255) shl 16) or
                    (ng.toInt().coerceIn(0, 255) shl 8) or nb.toInt().coerceIn(0, 255)
            }
        }
        dark = null
        // ধাপ ২: ছায়ার জায়গায় বেশি-আলোর ছবির পরিষ্কার ডিটেইলস
        val bright: Merged? = if (shots.size > 2 && okf[2]) decode(shots[2], rect, sample) else null
        if (bright != null) {
            for (y in 0 until h) {
                bil.row(y)
                val row = y * w
                for (x in 0 until w) {
                    val o = px[row + x]
                    val l = lum(o)
                    if (l < 55) {
                        val sWt = smooth01((55f - l) / 30f)
                        val xx = (x + shiftX[2]).coerceIn(0, w - 1)
                        val yy = (y + shiftY[2]).coerceIn(0, h - 1)
                        val q = bright.px[yy * w + xx]
                        val gb = bil.at(gB, x)
                        val r0 = (o shr 16) and 255
                        val g0 = (o shr 8) and 255
                        val b0 = o and 255
                        val nr = r0 + (((q shr 16) and 255) * gb - r0) * sWt
                        val ng = g0 + (((q shr 8) and 255) * gb - g0) * sWt
                        val nb = b0 + ((q and 255) * gb - b0) * sWt
                        px[row + x] = (0xFF shl 24) or (nr.toInt().coerceIn(0, 255) shl 16) or
                            (ng.toInt().coerceIn(0, 255) shl 8) or nb.toInt().coerceIn(0, 255)
                    }
                }
            }
        }
        ref.hdrOk = true
        return ref
    }

    private fun lanczosW(x: Float): Float {
        if (x == 0f) return 1f
        if (abs(x) >= 3f) return 0f
        val a = (Math.PI * x).toFloat()
        return (3f * sin(a) * sin(a / 3f)) / (a * a)
    }

    private class Taps(val idx: IntArray, val wt: FloatArray)

    private fun makeTaps(nIn: Int, nOut: Int): Taps {
        val scale = nOut.toFloat() / nIn
        val idx = IntArray(nOut * 6)
        val wt = FloatArray(nOut * 6)
        for (i in 0 until nOut) {
            val c = (i + 0.5f) / scale - 0.5f
            val left = floor(c).toInt() - 2
            var sum = 0f
            for (t in 0 until 6) {
                val sIdx = left + t
                val wv = lanczosW(c - sIdx)
                idx[i * 6 + t] = sIdx.coerceIn(0, nIn - 1)
                wt[i * 6 + t] = wv
                sum += wv
            }
            for (t in 0 until 6) wt[i * 6 + t] /= sum
        }
        return Taps(idx, wt)
    }

    // Lanczos আপস্কেল: সাধারণ বড় করার চেয়ে অনেক ধারালো, ব্লক ছাড়া
    private fun lanczos(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val tx = makeTaps(w, nw)
        val ty = makeTaps(h, nh)
        val tmp = IntArray(nw * h)
        parallelBands(h, 32) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until nw) {
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    for (t in 0 until 6) {
                        val p = src[row + tx.idx[x * 6 + t]]
                        val wv = tx.wt[x * 6 + t]
                        r += ((p shr 16) and 255) * wv
                        g += ((p shr 8) and 255) * wv
                        b += (p and 255) * wv
                    }
                    tmp[y * nw + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
                }
            }
        }
        val out = IntArray(nw * nh)
        parallelBands(nh, 32) { y0, y1 ->
            val ro = IntArray(6)
            for (y in y0 until y1) {
                for (t in 0 until 6) ro[t] = ty.idx[y * 6 + t] * nw
                for (x in 0 until nw) {
                    var r = 0f
                    var g = 0f
                    var b = 0f
                    for (t in 0 until 6) {
                        val p = tmp[ro[t] + x]
                        val wv = ty.wt[y * 6 + t]
                        r += ((p shr 16) and 255) * wv
                        g += ((p shr 8) and 255) * wv
                        b += (p and 255) * wv
                    }
                    out[y * nw + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    // ---------- ব্লার সহায়ক ----------

    fun boxBlur(bmp: Bitmap, r: Int, passes: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurT(px, tmp, w, h, r)
            blurT(tmp, px, h, w, r)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurT(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        parallelBands(h, 32) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -r..r) {
                    val p = src[row + i.coerceIn(0, w - 1)]
                    sr += (p shr 16) and 255
                    sg += (p shr 8) and 255
                    sb += p and 255
                }
                for (x in 0 until w) {
                    dst[x * h + y] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                    val a = src[row + (x + r + 1).coerceAtMost(w - 1)]
                    val d = src[row + (x - r).coerceAtLeast(0)]
                    sr += ((a shr 16) and 255) - ((d shr 16) and 255)
                    sg += ((a shr 8) and 255) - ((d shr 8) and 255)
                    sb += (a and 255) - (d and 255)
                }
            }
        }
    }
}
