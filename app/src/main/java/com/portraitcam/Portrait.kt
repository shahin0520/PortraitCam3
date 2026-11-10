package com.portraitcam

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Portrait processing: person segmentation (ML Kit) + background blur + light enhancement. */
object Portrait {
    class Out(val uri: Uri?, val blurred: Boolean)

    fun process(ctx: Context, jpeg: ByteArray, rot: Int, mirror: Boolean, strength: Int): Out {
        // 1) Decode at full resolution (only halve if > ~4600px so memory stays safe)
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bo)
        var sample = 1
        while (maxOf(bo.outWidth, bo.outHeight) / sample > 4600) sample *= 2
        val dec = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
        val full = if (rot != 0 || mirror) {
            val m = Matrix()
            if (rot != 0) m.postRotate(rot.toFloat())
            if (mirror) m.postScale(-1f, 1f)
            Bitmap.createBitmap(dec, 0, 0, dec.width, dec.height, m, true).also { if (it !== dec) dec.recycle() }
        } else dec
        val w = full.width
        val h = full.height

        // 2) Small copy for segmentation + blurred background
        val sc = 640f / maxOf(w, h)
        val small = Bitmap.createScaledBitmap(full, (w * sc).toInt().coerceAtLeast(1), (h * sc).toInt().coerceAtLeast(1), true)
        val mask = segment(small)
        if (mask == null) {            // no person found -> save normally
            val uri = save(ctx, full, "IMG")
            return Out(uri, false)
        }

        // 3) Blur background (3-pass box blur ~ gaussian)
        val sw = small.width
        val sh = small.height
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        blur(px, sw, sh, (3 + strength * 0.17f).toInt())
        val bg = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)

        // 4) Compose: blurred bg + sharp full-res person through the soft mask
        val cm = ColorMatrix().apply { setSaturation(1.1f) }
        val k = 1.06f
        val t = (-0.5f * k + 0.5f) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val enh = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        val dst = Rect(0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(bg, null, dst, enh)
        val layer = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        c.drawBitmap(full, 0f, 0f, enh)
        val pm = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        c.drawBitmap(mask, null, dst, pm)
        c.restoreToCount(layer)

        val uri = save(ctx, out, "PORTRAIT")
        full.recycle(); out.recycle(); small.recycle(); bg.recycle(); mask.recycle()
        return Out(uri, true)
    }

    private fun segment(bm: Bitmap): Bitmap? {
        val seg = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask().build())
        try {
            val r = Tasks.await(seg.process(InputImage.fromBitmap(bm, 0)))
            val mw = r.width
            val mh = r.height
            val buf = r.buffer
            buf.rewind()
            val px = IntArray(mw * mh)
            var cnt = 0
            for (i in px.indices) {
                val v = ((buf.float - 0.3f) / 0.4f).coerceIn(0f, 1f)
                val a = v * v * (3f - 2f * v)           // smoothstep -> cleaner edge
                if (a > 0.5f) cnt++
                px[i] = (a * 255f).toInt() shl 24
            }
            if (cnt < px.size * 0.01f) return null
            blur(px, mw, mh, 1)                        // feather the edge
            return Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            return null
        } finally {
            seg.close()
        }
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
        r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (Build.VERSION.SDK_INT >= 29) {
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); r.update(uri, cv, null, null)
        }
        return uri
    }
}
