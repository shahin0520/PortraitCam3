package com.portraitcam

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Full photo pipeline: detail/denoise, HDR tone map, look, face smoothing, portrait blur. */
object Pipeline {
    class Look(val name: String, val sat: Float, val con: Float, val rm: Float, val bm: Float)
    val LOOKS = listOf(
        Look("Natural", 1.08f, 1.04f, 1f, 1f), Look("Vivid", 1.3f, 1.1f, 1f, 1f),
        Look("Warm", 1.1f, 1.04f, 1.07f, 0.92f), Look("Cool", 1.05f, 1.04f, 0.94f, 1.07f),
        Look("B&W", 0f, 1.12f, 1f, 1f))

    class Opts(val portrait: Boolean, val blur: Int, val smooth: Int, val hdr: Boolean,
               val zoom: Float, val look: Int, val mirror: Boolean)
    class Out(val uri: Uri?, val note: String)

    fun process(ctx: Context, jpeg: ByteArray, rot: Int, o: Opts): Out {
        val full = decode(jpeg, rot, o.mirror)
        val w = full.width
        val h = full.height
        val sc = 640f / max(w, h)
        val sw = max(2, (w * sc).toInt())
        val sh = max(2, (h * sc).toInt())
        val small = Bitmap.createScaledBitmap(full, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        val lum = FloatArray(sw * sh) { val p = sp[it]
            (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)) / 255f }

        val gain = if (o.hdr) toneGain(lum, sw, sh) else null
        val mask = if (o.portrait) personMask(small, lum, sw, sh) else null
        val faces = if (o.smooth > 0) detectFaces(small) else emptyList()
        small.recycle()

        val proc = enhance(full, o, gain, sw, sh)
        full.recycle()
        val fs = w.toFloat() / sw
        for (f in faces) smoothFace(proc, Rect((f.left * fs).toInt(), (f.top * fs).toInt(), (f.right * fs).toInt(), (f.bottom * fs).toInt()), o.smooth)

        val result = if (mask != null) compose(proc, mask, sw, sh, o.blur) else proc
        val uri = save(ctx, result, if (mask != null) "PORTRAIT" else "IMG")
        if (result !== proc) proc.recycle()
        result.recycle()
        val note = if (o.portrait && mask == null) "No person found - blur skipped" else ""
        return Out(uri, note)
    }

    private fun decode(jpeg: ByteArray, rot: Int, mirror: Boolean): Bitmap {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bo)
        var sample = 1
        while (max(bo.outWidth, bo.outHeight) / sample > 4600) sample *= 2
        val dec = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (rot == 0 && !mirror) return dec
        val m = Matrix()
        if (rot != 0) m.postRotate(rot.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(dec, 0, 0, dec.width, dec.height, m, true).also { if (it !== dec) dec.recycle() }
    }

    // ---------- HDR-style tone map (lift shadows, tame highlights) ----------
    private fun toneGain(l: FloatArray, sw: Int, sh: Int): FloatArray {
        val lw = boxF(boxF(l, sw, sh, 10), sw, sh, 10)
        return FloatArray(l.size) {
            val v = lw[it]
            val s = (1f - v / 0.55f).coerceIn(0f, 1f)
            val hi = ((v - 0.6f) / 0.4f).coerceIn(0f, 1f)
            1f + 0.55f * s * s - 0.3f * hi * hi
        }
    }

    // ---------- detail, denoise, tone, look (full resolution, in bands to save memory) ----------
    private fun enhance(full: Bitmap, o: Opts, gain: FloatArray?, gw: Int, gh: Int): Bitmap {
        val w = full.width
        val h = full.height
        val half = Bitmap.createScaledBitmap(full, max(1, w / 2), max(1, h / 2), true)
        val low = Bitmap.createScaledBitmap(half, w, h, true)
        half.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val z = (o.zoom - 1f).coerceAtLeast(0f)
        val amt = (0.6f + 0.45f * z + if (o.hdr) 0.25f else 0f).coerceAtMost(2.2f)   // sharpen strength grows with zoom
        val th = 3f + 2.5f * z                                                       // noise threshold: don't sharpen noise
        val k = (0.35f + 0.08f * z).coerceAtMost(0.7f)                               // colour-noise smoothing
        val lk = LOOKS[o.look.coerceIn(0, LOOKS.size - 1)]
        val gx0 = IntArray(w)
        val gfx = FloatArray(w)
        if (gain != null) for (x in 0 until w) {
            val fx = x * (gw - 1f) / max(1, w - 1)
            val i = min(fx.toInt(), gw - 2).coerceAtLeast(0)
            gx0[x] = i; gfx[x] = fx - i
        }
        val band = 96
        val a = IntArray(w * band)
        val b = IntArray(w * band)
        var y0 = 0
        while (y0 < h) {
            val bh = min(band, h - y0)
            full.getPixels(a, 0, w, 0, y0, w, bh)
            low.getPixels(b, 0, w, 0, y0, w, bh)
            for (yy in 0 until bh) {
                var g0 = 0; var fy = 0f
                if (gain != null) {
                    val fyy = (y0 + yy) * (gh - 1f) / max(1, h - 1)
                    g0 = min(fyy.toInt(), gh - 2).coerceAtLeast(0); fy = fyy - g0
                }
                for (x in 0 until w) {
                    val i = yy * w + x
                    val p = a[i]; val l = b[i]
                    val r = ((p shr 16) and 255).toFloat(); val g = ((p shr 8) and 255).toFloat(); val bl = (p and 255).toFloat()
                    val lr = ((l shr 16) and 255).toFloat(); val lg = ((l shr 8) and 255).toFloat(); val lb = (l and 255).toFloat()
                    val yo = 0.299f * r + 0.587f * g + 0.114f * bl
                    val yl = 0.299f * lr + 0.587f * lg + 0.114f * lb
                    val d = yo - yl
                    val core = (abs(d) / (2f * th)).coerceIn(0.25f, 1f)
                    val ys = yo + d * amt * core
                    var gn = 1f
                    if (gain != null) {
                        val ix = gx0[x]; val fx = gfx[x]
                        val t0 = gain[g0 * gw + ix] * (1 - fx) + gain[g0 * gw + ix + 1] * fx
                        val t1 = gain[(g0 + 1) * gw + ix] * (1 - fx) + gain[(g0 + 1) * gw + ix + 1] * fx
                        gn = t0 * (1 - fy) + t1 * fy
                    }
                    var rr = (ys + (r - yo) * (1 - k) + (lr - yl) * k) * gn
                    var gg = (ys + (g - yo) * (1 - k) + (lg - yl) * k) * gn
                    var bb = (ys + (bl - yo) * (1 - k) + (lb - yl) * k) * gn
                    val gray = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    rr = gray + (rr - gray) * lk.sat; gg = gray + (gg - gray) * lk.sat; bb = gray + (bb - gray) * lk.sat
                    rr = ((rr - 128f) * lk.con + 128f) * lk.rm
                    gg = (gg - 128f) * lk.con + 128f
                    bb = ((bb - 128f) * lk.con + 128f) * lk.bm
                    a[i] = (0xFF shl 24) or (rr.toInt().coerceIn(0, 255) shl 16) or
                            (gg.toInt().coerceIn(0, 255) shl 8) or bb.toInt().coerceIn(0, 255)
                }
            }
            out.setPixels(a, 0, w, 0, y0, w, bh)
            y0 += bh
        }
        low.recycle()
        return out
    }

    // ---------- face smoothing: only skin-coloured, low-contrast areas inside detected faces ----------
    private fun detectFaces(small: Bitmap): List<Rect> {
        return try {
            val det = FaceDetection.getClient(FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
            val r = Tasks.await(det.process(InputImage.fromBitmap(small, 0)))
            det.close()
            r.map { it.boundingBox }
        } catch (e: Exception) { emptyList() }
    }

    private fun smoothFace(bm: Bitmap, f: Rect, strength: Int) {
        val pad = (f.width() * 0.15f).toInt()
        val x0 = (f.left - pad).coerceAtLeast(0); val y0 = (f.top - pad).coerceAtLeast(0)
        val x1 = (f.right + pad).coerceAtMost(bm.width); val y1 = (f.bottom + pad).coerceAtMost(bm.height)
        val rw = x1 - x0; val rh = y1 - y0
        if (rw < 16 || rh < 16) return
        val px = IntArray(rw * rh)
        bm.getPixels(px, 0, rw, x0, y0, rw, rh)
        val low = px.copyOf()
        blur(low, rw, rh, max(2, (f.width() * 0.012f).toInt()))
        val cx = rw / 2f; val cy = rh / 2f; val amt = strength / 100f
        for (y in 0 until rh) for (x in 0 until rw) {
            val i = y * rw + x
            val p = px[i]; val l = low[i]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val cb = 128f - 0.169f * r - 0.331f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.419f * g - 0.081f * b
            if (cb < 77f || cb > 127f || cr < 133f || cr > 173f) continue
            val ex = (x - cx) / cx; val ey = (y - cy) / cy
            val d = ex * ex + ey * ey
            if (d >= 1f) continue
            val fall = min(1f - d, 0.5f) * 2f
            val lr = (l shr 16) and 255; val lg = (l shr 8) and 255; val lb = l and 255
            val diff = (abs(r - lr) + abs(g - lg) + abs(b - lb)) / 3f
            val t = amt * 0.85f * exp(-(diff * diff) / (2f * 14f * 14f)) * fall
            val nr = (r + (lr - r) * t).toInt(); val ng = (g + (lg - g) * t).toInt(); val nb = (b + (lb - b) * t).toInt()
            px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        bm.setPixels(px, 0, rw, x0, y0, rw, rh)
    }

    // ---------- portrait: clean mask, edge-snap, distance-based blur ----------
    private fun personMask(bm: Bitmap, lum: FloatArray, sw: Int, sh: Int): FloatArray? {
        val seg = Segmentation.getClient(SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build())
        try {
            val r = Tasks.await(seg.process(InputImage.fromBitmap(bm, 0)))
            val mw = r.width; val mh = r.height
            val buf = r.buffer; buf.rewind()
            val conf = FloatArray(mw * mh) { buf.float }
            val m = cleanup(conf, mw, mh) ?: return null
            val pm = FloatArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                val fx = x * (mw - 1f) / (sw - 1f); val fy = y * (mh - 1f) / (sh - 1f)
                val x0 = min(fx.toInt(), mw - 2); val y0 = min(fy.toInt(), mh - 2)
                val tx = fx - x0; val ty = fy - y0
                val a = m[y0 * mw + x0] * (1 - tx) + m[y0 * mw + x0 + 1] * tx
                val b = m[(y0 + 1) * mw + x0] * (1 - tx) + m[(y0 + 1) * mw + x0 + 1] * tx
                pm[y * sw + x] = a * (1 - ty) + b * ty
            }
            val q = guided(lum, pm, sw, sh, 6, 0.0025f)       // snap edges to real image edges
            for (i in q.indices) q[i] = ((q[i] - 0.5f) * 1.7f + 0.5f).coerceIn(0f, 1f)
            return q
        } catch (e: Exception) {
            return null
        } finally { seg.close() }
    }

    /** Keep real person blobs, drop stray background islands, fill small holes inside the person. */
    private fun cleanup(conf: FloatArray, w: Int, h: Int): FloatArray? {
        val n = w * h
        val bin = BooleanArray(n) { conf[it] > 0.5f }
        val q = IntArray(n)
        val lab = IntArray(n)
        val areas = ArrayList<Int>(); areas.add(0)
        for (p in 0 until n) if (bin[p] && lab[p] == 0) areas.add(flood(p, w, h, { bin[it] }, lab, areas.size, q))
        val maxA = areas.maxOrNull() ?: 0
        if (maxA < n * 0.01f) return null
        val keep = BooleanArray(areas.size) { it > 0 && areas[it] >= maxA * 0.12f }
        val lab2 = IntArray(n)
        for (p in 0 until n) {
            val x = p % w; val y = p / w
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && !bin[p] && lab2[p] == 0) flood(p, w, h, { !bin[it] }, lab2, 1, q)
        }
        val hole = BooleanArray(n)
        for (p in 0 until n) if (!bin[p] && lab2[p] == 0) {
            val cnt = flood(p, w, h, { !bin[it] }, lab2, 2, q)
            if (cnt < n * 0.03f) for (i in 0 until cnt) hole[q[i]] = true
        }
        val out = FloatArray(n)
        for (p in 0 until n) {
            if (hole[p]) { out[p] = 1f; continue }
            val t = ((conf[p] - 0.2f) / 0.4f).coerceIn(0f, 1f)
            val v = t * t * (3f - 2f * t)
            if (bin[p]) { if (keep[lab[p]]) out[p] = v }
            else {
                val x = p % w; val y = p / w
                val nb = (x > 0 && bin[p - 1] && keep[lab[p - 1]]) || (x < w - 1 && bin[p + 1] && keep[lab[p + 1]]) ||
                        (y > 0 && bin[p - w] && keep[lab[p - w]]) || (y < h - 1 && bin[p + w] && keep[lab[p + w]])
                if (nb) out[p] = v
            }
        }
        return out
    }

    private fun flood(s: Int, w: Int, h: Int, same: (Int) -> Boolean, lab: IntArray, id: Int, q: IntArray): Int {
        var head = 0; var tail = 0
        q[tail++] = s; lab[s] = id
        while (head < tail) {
            val p = q[head++]
            val x = p % w; val y = p / w
            if (x > 0) { val t = p - 1; if (lab[t] == 0 && same(t)) { lab[t] = id; q[tail++] = t } }
            if (x < w - 1) { val t = p + 1; if (lab[t] == 0 && same(t)) { lab[t] = id; q[tail++] = t } }
            if (y > 0) { val t = p - w; if (lab[t] == 0 && same(t)) { lab[t] = id; q[tail++] = t } }
            if (y < h - 1) { val t = p + w; if (lab[t] == 0 && same(t)) { lab[t] = id; q[tail++] = t } }
        }
        return tail
    }

    private fun compose(proc: Bitmap, m: FloatArray, sw: Int, sh: Int, strength: Int): Bitmap {
        val w = proc.width; val h = proc.height
        val sm = Bitmap.createScaledBitmap(proc, sw, sh, true)
        val sp = IntArray(sw * sh)
        sm.getPixels(sp, 0, sw, 0, 0, sw, sh)
        sm.recycle()
        val strong = sp.copyOf(); val mild = sp.copyOf()
        val rs = (5 + strength * 0.25f).toInt()
        blur(strong, sw, sh, rs)
        blur(mild, sw, sh, max(2, rs / 3))
        val rad = max(4, sw / 16)
        val prox = boxF(boxF(m, sw, sh, rad), sw, sh, rad)      // how close each pixel is to the person
        for (i in strong.indices) {
            val t = (prox[i] * 2.2f).coerceIn(0f, 1f)             // near subject -> gentle blur, far -> strong
            val s = strong[i]; val d = mild[i]
            val r = (((s shr 16) and 255) * (1 - t) + ((d shr 16) and 255) * t).toInt()
            val g = (((s shr 8) and 255) * (1 - t) + ((d shr 8) and 255) * t).toInt()
            val b = ((s and 255) * (1 - t) + (d and 255) * t).toInt()
            strong[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bg = Bitmap.createBitmap(strong, sw, sh, Bitmap.Config.ARGB_8888)
        val mp = IntArray(sw * sh) { (m[it] * 255f).toInt() shl 24 }
        val mask = Bitmap.createBitmap(mp, sw, sh, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val dst = Rect(0, 0, w, h)
        c.drawBitmap(bg, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        val layer = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        c.drawBitmap(proc, 0f, 0f, null)
        c.drawBitmap(mask, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.restoreToCount(layer)
        bg.recycle(); mask.recycle()
        return out
    }

    // ---------- filters ----------
    private fun boxF(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size); val out = FloatArray(src.size)
        val div = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val b = y * w
            var s = 0f
            for (k in -r..r) s += src[b + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[b + x] = s / div
                s += src[b + (x + r + 1).coerceIn(0, w - 1)] - src[b + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / div
                s += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    private fun guided(i: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val mI = boxF(i, w, h, r); val mP = boxF(p, w, h, r)
        val cII = boxF(FloatArray(i.size) { i[it] * i[it] }, w, h, r)
        val cIP = boxF(FloatArray(i.size) { i[it] * p[it] }, w, h, r)
        val a = FloatArray(i.size); val b = FloatArray(i.size)
        for (k in i.indices) {
            val v = cII[k] - mI[k] * mI[k]; val c = cIP[k] - mI[k] * mP[k]
            a[k] = c / (v + eps); b[k] = mP[k] - a[k] * mI[k]
        }
        val mA = boxF(a, w, h, r); val mB = boxF(b, w, h, r)
        return FloatArray(i.size) { (mA[it] * i[it] + mB[it]).coerceIn(0f, 1f) }
    }

    private fun blur(px: IntArray, w: Int, h: Int, r: Int) {
        if (r < 1) return
        val tmp = IntArray(px.size)
        repeat(3) {
            for (y in 0 until h) line(px, tmp, y * w, 1, w, r)
            for (x in 0 until w) line(tmp, px, x, w, h, r)
        }
    }

    private fun line(s: IntArray, d: IntArray, st: Int, step: Int, n: Int, r: Int) {
        val div = 2 * r + 1
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (k in -r..r) {
            val p = s[st + k.coerceIn(0, n - 1) * step]
            sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
        }
        for (i in 0 until n) {
            d[st + i * step] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val o = s[st + (i - r).coerceIn(0, n - 1) * step]
            val a = s[st + (i + r + 1).coerceIn(0, n - 1) * step]
            sa += (a ushr 24) - (o ushr 24)
            sr += ((a shr 16) and 255) - ((o shr 16) and 255)
            sg += ((a shr 8) and 255) - ((o shr 8) and 255)
            sb += (a and 255) - (o and 255)
        }
    }

    fun save(ctx: Context, bmp: Bitmap, prefix: String): Uri? {
        val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PortraitCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 97, it) }
        if (Build.VERSION.SDK_INT >= 29) {
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); r.update(uri, cv, null, null)
        }
        return uri
    }
}
