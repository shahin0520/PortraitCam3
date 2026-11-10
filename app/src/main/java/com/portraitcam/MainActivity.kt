package com.portraitcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var zoomRow: LinearLayout
    private lateinit var blurRow: LinearLayout
    private lateinit var seekBlur: SeekBar
    private lateinit var btnFlash: TextView
    private lateinit var btnFlip: TextView
    private lateinit var tvPhoto: TextView
    private lateinit var tvPortrait: TextView
    private lateinit var shutter: View
    private lateinit var thumb: ImageView

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var portrait = false
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var lastUri: Uri? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val zoomBtns = ArrayList<Pair<TextView, Float>>()
    private var zoomObserver: Observer<ZoomState>? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private val yellow = Color.parseColor("#FFD54F")

    private val perms: Array<String> =
        if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.CAMERA)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCam()) startCamera() else toast("Camera permission needed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        zoomRow = findViewById(R.id.zoomRow)
        blurRow = findViewById(R.id.blurRow)
        seekBlur = findViewById(R.id.seekBlur)
        btnFlash = findViewById(R.id.btnFlash)
        btnFlip = findViewById(R.id.btnFlip)
        tvPhoto = findViewById(R.id.tvPhoto)
        tvPortrait = findViewById(R.id.tvPortrait)
        shutter = findViewById(R.id.shutter)
        thumb = findViewById(R.id.thumb)

        thumb.background = oval("#33FFFFFF")
        thumb.clipToOutline = true
        thumb.outlineProvider = ViewOutlineProvider.BACKGROUND
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP

        tvPhoto.setOnClickListener { setMode(false) }
        tvPortrait.setOnClickListener { setMode(true) }
        btnFlip.setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bind()
        }
        btnFlash.setOnClickListener {
            flash = when (flash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flash
            btnFlash.text = "⚡ " + when (flash) {
                ImageCapture.FLASH_MODE_AUTO -> "AUTO"
                ImageCapture.FLASH_MODE_ON -> "ON"
                else -> "OFF"
            }
        }
        shutter.setOnClickListener { shoot() }
        thumb.setOnClickListener {
            lastUri?.let { u -> startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }
        setupTouch()
        setMode(false)

        if (hasCam()) startCamera() else permLauncher.launch(perms)
    }

    private fun hasCam() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun supports(sel: CameraSelector, key: CameraCharacteristics.Key<IntArray>, v: Int): Boolean {
        return try {
            val info = sel.filter(provider!!.availableCameraInfos).firstOrNull() ?: return false
            Camera2CameraInfo.from(info).getCameraCharacteristic(key)?.contains(v) == true
        } catch (e: Throwable) { false }
    }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll()
        val sel = CameraSelector.Builder().requireLensFacing(lens).build()
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val prevSel = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
        // highest resolution -> digital zoom crops a big sensor image, not a small one
        val capSel = ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
        val prev = Preview.Builder().setResolutionSelector(prevSel).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val icb = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(capSel)
            .setFlashMode(flash)
        // Only ask for HQ edge/noise-reduction if this (possibly LIMITED) device lists support
        try {
            val ext = Camera2Interop.Extender(icb)
            if (supports(sel, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_HIGH_QUALITY))
                ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            if (supports(sel, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))
                ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        } catch (_: Throwable) { }
        val ic = icb.build()
        imageCapture = ic
        try {
            camera = p.bindToLifecycle(this, sel, prev, ic)
        } catch (e: Exception) {
            toast("Camera error: " + e.message); return
        }
        zoomObserver?.let { zoomLive?.removeObserver(it) }
        zoomBtns.clear(); zoomRow.removeAllViews()
        val obs = Observer<ZoomState> { z -> onZoom(z) }
        zoomObserver = obs
        zoomLive = camera!!.cameraInfo.zoomState
        zoomLive!!.observe(this, obs)
    }

    // ---------- zoom ----------
    private fun buildZoom(min: Float, max: Float) {
        val stops = ArrayList<Float>()
        if (min < 0.95f) stops.add(min)
        stops.add(1f)
        for (r in listOf(2f, 3f, 5f, 10f)) if (r <= max + 0.01f) stops.add(r)
        for (r in stops.take(5)) {
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.textSize = 13f
            tv.setTypeface(null, Typeface.BOLD)
            tv.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).also { it.setMargins(dp(2), 0, dp(2), 0) }
            tv.setOnClickListener { camera?.cameraControl?.setZoomRatio(r) }
            zoomRow.addView(tv)
            zoomBtns.add(Pair(tv, r))
        }
    }

    private fun onZoom(z: ZoomState) {
        if (zoomBtns.isEmpty()) buildZoom(z.minZoomRatio, z.maxZoomRatio)
        val cur = z.zoomRatio
        val closest = zoomBtns.minByOrNull { abs(it.second - cur) }
        for ((tv, r) in zoomBtns) {
            val on = tv === closest?.first
            tv.text = fmt(if (on) cur else r)
            tv.setTextColor(if (on) yellow else Color.WHITE)
            tv.background = oval(if (on) "#99000000" else "#00000000")
        }
    }

    private fun fmt(r: Float): String =
        (if (abs(r - r.roundToInt()) < 0.05f) r.roundToInt().toString() else String.format(Locale.US, "%.1f", r)) + "x"

    private fun setupTouch() {
        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value ?: return true
                camera?.cameraControl?.setZoomRatio(z.zoomRatio * d.scaleFactor)
                return true
            }
        })
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focus(e.x, e.y); return true }
        })
        previewView.setOnTouchListener { _, e ->
            sd.onTouchEvent(e)
            if (!sd.isInProgress) gd.onTouchEvent(e)
            true
        }
    }

    private fun focus(x: Float, y: Float) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
    }

    // ---------- mode ----------
    private fun setMode(p: Boolean) {
        portrait = p
        blurRow.visibility = if (p) View.VISIBLE else View.GONE
        tvPhoto.setTextColor(if (!p) yellow else Color.WHITE)
        tvPortrait.setTextColor(if (p) yellow else Color.WHITE)
        tvPhoto.setTypeface(null, if (!p) Typeface.BOLD else Typeface.NORMAL)
        tvPortrait.setTypeface(null, if (p) Typeface.BOLD else Typeface.NORMAL)
    }

    // ---------- capture ----------
    private fun shoot() {
        val ic = imageCapture ?: return
        shutter.isEnabled = false
        if (!portrait) {
            val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PortraitCam")
            }
            val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build()
            ic.takePicture(opts, exec, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) { done(r.savedUri) }
                override fun onError(e: ImageCaptureException) { toast("Error: " + e.message); done(null) }
            })
        } else {
            val mirror = lens == CameraSelector.LENS_FACING_FRONT
            val strength = seekBlur.progress
            ic.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                        val rot = image.imageInfo.rotationDegrees
                        image.close()
                        toast("Processing…")
                        val o = Portrait.process(this@MainActivity, bytes, rot, mirror, strength)
                        if (!o.blurred) toast("No person found - saved normal photo")
                        done(o.uri)
                    } catch (e: Throwable) {
                        toast("Error: " + e.message); done(null)
                    }
                }
                override fun onError(e: ImageCaptureException) { toast("Error: " + e.message); done(null) }
            })
        }
    }

    private fun done(uri: Uri?) {
        val bm = if (uri != null) thumbOf(uri) else null
        runOnUiThread {
            shutter.isEnabled = true
            if (uri != null) { lastUri = uri; if (bm != null) thumb.setImageBitmap(bm) }
        }
    }

    private fun thumbOf(uri: Uri): Bitmap? {
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 8 }
            val b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
            val deg = contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90; 3 -> 180; 8 -> 270; else -> 0
                }
            } ?: 0
            if (deg == 0) return b
            return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        } catch (e: Exception) { return null }
    }

    // ---------- helpers ----------
    private fun oval(hex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    override fun onDestroy() { super.onDestroy(); exec.shutdown() }
}
