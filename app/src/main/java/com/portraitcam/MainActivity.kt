package com.portraitcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private val YELLOW = 0xFFFFD60A.toInt()
    private val DIM = 0x99FFFFFF.toInt()
    private val filterNames = listOf("Original", "Vivid", "Warm", "Cool", "Mono", "Film", "S25")

    // ---- views ----
    private lateinit var preview: PreviewView
    private lateinit var previewHolder: View
    private var vz = 1f                 // ভার্চুয়াল জুম (১x থেকে ১০০x)
    private lateinit var ivFreeze: ImageView
    private lateinit var grid: View
    private lateinit var icSettings: IconView
    private lateinit var icFlash: IconView
    private lateinit var icTimer: IconView
    private lateinit var icRatio: IconView
    private lateinit var icGrid: IconView
    private lateinit var icFilter: IconView
    private lateinit var icFlip: IconView
    private lateinit var tvHdr: TextView
    private lateinit var tvNr: TextView
    private lateinit var tvAi: TextView
    private lateinit var tvTele: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvEnh: TextView
    private lateinit var tvLook: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvClose: TextView
    private lateinit var tvBusy: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvJobs: TextView
    private lateinit var tvNight: TextView
    private lateinit var tvPhoto: TextView
    private lateinit var tvPortrait: TextView
    private lateinit var gallery: ImageView
    private lateinit var brightSeek: SeekBar
    private lateinit var detailSeek: SeekBar
    private lateinit var beautySeek: SeekBar
    private lateinit var blurSeek: SeekBar
    private lateinit var blurRow: View
    private lateinit var shutter: View
    private lateinit var sheet: View
    private lateinit var chips: List<Pair<TextView, Float>>

    // ---- camera + settings state ----
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var mode = 1                 // 0 NIGHT, 1 PHOTO, 2 PORTRAIT
    private var hdrMode = 1              // 0 OFF, 1 AUTO, 2 ON
    private var nrMode = 1               // 0 OFF, 1 ON, 2 MAX
    private var zoomEngine = 1           // 0 PLAIN, 1 SR (multi-frame super-res), 2 AI
    private var teleOn = true
    private var gridOn = false
    private var timerSec = 0
    private var filterIdx = 0
    private var procMode = 0             // 0 শুধু প্রসেস, 1 প্রসেস + ওরিজিনাল, 2 শুধু ওরিজিনাল
    private var busy = false
    private var lastUri: Uri? = null

    private val camExec = Executors.newSingleThreadExecutor()
    private val quickExec = Executors.newSingleThreadExecutor()
    private var look = 1                 // 0 NATURAL, 1 S25, 2 CRISP
    private var speed = 0                // 0 FAST (১টা ছবি, দ্রুত), 1 QUALITY (কয়েকটা ছবি মিলিয়ে)
    private val procExec = Executors.newSingleThreadExecutor()
    private val miscExec = Executors.newSingleThreadExecutor()
    private val jobsPending = AtomicInteger(0)
    @Volatile private var jobStatus = ""
    private val main = Handler(Looper.getMainLooper())
    private val sr by lazy { SuperRes(this) }
    private val depthEst by lazy { DepthEst(this) }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { if (provider == null) startCamera() }
        else Toast.makeText(this, "ক্যামেরা পারমিশন লাগবে", Toast.LENGTH_LONG).show()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) enhanceUri(uri)
    }

    private fun cameraGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initUi()
        } catch (e: Throwable) {
            showFatal(e)
        }
    }

    private fun showFatal(e: Throwable) {
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 12f
        tv.setPadding(24, 64, 24, 24)
        tv.setTextIsSelectable(true)
        tv.text = "অ্যাপ চালু করতে সমস্যা হয়েছে। এই লেখার স্ক্রিনশট পাঠাও:\n\n" + android.util.Log.getStackTraceString(e)
        val sc = android.widget.ScrollView(this)
        sc.setBackgroundColor(Color.BLACK)
        sc.addView(tv)
        setContentView(sc)
    }

    private fun initUi() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        previewHolder = findViewById(R.id.previewHolder)
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FIT_START
        ivFreeze = findViewById(R.id.ivFreeze)
        grid = findViewById(R.id.grid)
        icSettings = findViewById(R.id.icSettings)
        icFlash = findViewById(R.id.icFlash)
        icTimer = findViewById(R.id.icTimer)
        icRatio = findViewById(R.id.icRatio)
        icGrid = findViewById(R.id.icGrid)
        icFilter = findViewById(R.id.icFilter)
        icFlip = findViewById(R.id.icFlip)
        tvHdr = findViewById(R.id.tvHdr)
        tvNr = findViewById(R.id.tvNr)
        tvAi = findViewById(R.id.tvAi)
        tvTele = findViewById(R.id.tvTele)
        tvInfo = findViewById(R.id.tvInfo)
        tvEnh = findViewById(R.id.tvEnh)
        tvLook = findViewById(R.id.tvLook)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvClose = findViewById(R.id.tvClose)
        tvBusy = findViewById(R.id.tvBusy)
        tvCount = findViewById(R.id.tvCount)
        tvJobs = findViewById(R.id.tvJobs)
        tvNight = findViewById(R.id.tvNight)
        tvPhoto = findViewById(R.id.tvPhoto)
        tvPortrait = findViewById(R.id.tvPortrait)
        gallery = findViewById(R.id.gallery)
        brightSeek = findViewById(R.id.brightSeek)
        detailSeek = findViewById(R.id.detailSeek)
        beautySeek = findViewById(R.id.beautySeek)
        blurSeek = findViewById(R.id.blurSeek)
        blurRow = findViewById(R.id.blurRow)
        shutter = findViewById(R.id.shutter)
        sheet = findViewById(R.id.sheet)

        icSettings.kind = IconView.Kind.SETTINGS
        icFlash.kind = IconView.Kind.FLASH
        icTimer.kind = IconView.Kind.TIMER
        icRatio.kind = IconView.Kind.RATIO
        icGrid.kind = IconView.Kind.GRID
        icFilter.kind = IconView.Kind.FILTER
        icFlip.kind = IconView.Kind.FLIP

        gallery.clipToOutline = true
        gallery.outlineProvider = ViewOutlineProvider.BACKGROUND

        // প্রিভিউ ৩:৪, গোল কোনা (S24-এর মতো)
        preview.post {
            val h4 = preview.width * 4 / 3
            for (v in listOf<View>(previewHolder, ivFreeze, grid)) {
                val lp = v.layoutParams
                lp.height = h4
                v.layoutParams = lp
            }
            val rad = 22f * resources.displayMetrics.density
            val op = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, rad)
                }
            }
            previewHolder.outlineProvider = op
            previewHolder.clipToOutline = true
            ivFreeze.outlineProvider = op
            ivFreeze.clipToOutline = true
        }

        chips = listOf(
            findViewById<TextView>(R.id.z05) to 0.5f,
            findViewById<TextView>(R.id.z1) to 1f,
            findViewById<TextView>(R.id.z2) to 2f,
            findViewById<TextView>(R.id.z3) to 3f,
            findViewById<TextView>(R.id.z5) to 10f,
        )
        chips.forEach { (v, r) -> v.setOnClickListener { setZoom(r) } }

        tvNight.setOnClickListener { setMode(0) }
        tvPhoto.setOnClickListener { setMode(1) }
        tvPortrait.setOnClickListener { setMode(2) }

        icSettings.setOnClickListener { sheet.visibility = View.VISIBLE }
        tvClose.setOnClickListener { sheet.visibility = View.GONE }
        sheet.setOnClickListener { sheet.visibility = View.GONE }

        icFlash.setOnClickListener {
            flash = when (flash) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flash
            updateTop()
        }
        icTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            updateTop()
        }
        icRatio.setOnClickListener {
            Toast.makeText(this, "ছবির মাপ ৩:৪ (পুরো সেন্সর)", Toast.LENGTH_SHORT).show()
        }
        icGrid.setOnClickListener { gridOn = !gridOn; updateTop() }
        icFilter.setOnClickListener { filterIdx = (filterIdx + 1) % filterNames.size; updateTop() }
        icFlip.setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bind()
        }
        tvHdr.setOnClickListener { hdrMode = (hdrMode + 1) % 3; updateTop() }
        tvNr.setOnClickListener { nrMode = (nrMode + 1) % 3; updateTop() }
        tvAi.setOnClickListener { zoomEngine = (zoomEngine + 1) % 3; updateTop() }
        tvTele.setOnClickListener { teleOn = !teleOn; updateTop() }
        tvLook.setOnClickListener { look = (look + 1) % 3; applyLook(); updateTop() }
        tvSpeed.setOnClickListener { speed = 1 - speed; updateTop(); bind() }
        tvInfo.setOnClickListener { procMode = (procMode + 1) % 3; updateTop() }
        tvEnh.setOnClickListener {
            sheet.visibility = View.GONE
            pickImage.launch("image/*")
        }
        shutter.setOnClickListener { onShutter() }
        gallery.setOnClickListener {
            lastUri?.let {
                val type = contentResolver.getType(it) ?: "image/*"
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(it, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }

        val sgd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(vz * d.scaleFactor)
                return true
            }
        })
        var downX = 0f
        var multi = false
        preview.setOnTouchListener { v, e ->
            sgd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; multi = false }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_UP -> {
                    if (!multi && !sgd.isInProgress) {
                        val dx = e.x - downX
                        if (abs(dx) > 220f) {
                            setMode((mode + (if (dx < 0) 1 else -1)).coerceIn(0, 2))
                        } else if (e.eventTime - e.downTime < 250) {
                            val pt = preview.meteringPointFactory.createPoint(e.x, e.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                            v.performClick()
                        }
                    }
                }
            }
            true
        }

        applyLook()
        updateTop()
        updateModeUi()
        miscExec.execute { Pipeline.warmUp() }
        if (cameraGranted()) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (sheet.visibility == View.VISIBLE) sheet.visibility = View.GONE else super.onBackPressed()
    }

    // ---------- UI ----------

    private fun setMode(m: Int) {
        if (m == mode) return
        mode = m
        updateModeUi()
    }

    private fun updateModeUi() {
        tvNight.setTextColor(if (mode == 0) Color.WHITE else DIM)
        tvPhoto.setTextColor(if (mode == 1) Color.WHITE else DIM)
        tvPortrait.setTextColor(if (mode == 2) Color.WHITE else DIM)
        tvNight.setBackgroundResource(if (mode == 0) R.drawable.mode_active else 0)
        tvPhoto.setBackgroundResource(if (mode == 1) R.drawable.mode_active else 0)
        tvPortrait.setBackgroundResource(if (mode == 2) R.drawable.mode_active else 0)
        blurRow.visibility = if (mode == 2) View.VISIBLE else View.GONE
    }

    private fun tint(v: TextView, on: Boolean) = v.setTextColor(if (on) YELLOW else Color.WHITE)

    private fun updateTop() {
        icFlash.caption = when (flash) {
            ImageCapture.FLASH_MODE_AUTO -> "Auto"
            ImageCapture.FLASH_MODE_ON -> "On"
            else -> "Off"
        }
        icFlash.active = flash != ImageCapture.FLASH_MODE_OFF
        icTimer.caption = if (timerSec == 0) "Off" else "${timerSec}s"
        icTimer.active = timerSec != 0
        icGrid.caption = if (gridOn) "On" else "Off"
        icGrid.active = gridOn
        icFilter.caption = filterNames[filterIdx]
        icFilter.active = filterIdx != 0
        tvHdr.text = "HDR\n" + listOf("OFF", "AUTO", "ON")[hdrMode]
        tvNr.text = "NOISE\n" + listOf("OFF", "ON", "MAX")[nrMode]
        tvAi.text = "ZOOM\n" + listOf("PLAIN", "SUPER-RES", "AI")[zoomEngine]
        tvTele.text = "TELE LOOK\n" + (if (teleOn) "ON" else "OFF")
        tvInfo.text = "ORIGINAL\n" + listOf("OFF", "SAVE", "ONLY")[procMode]
        tvEnh.text = "ENHANCE\nGALLERY"
        tvLook.text = "LOOK\n" + listOf("NATURAL", "S25", "CRISP")[look]
        tvSpeed.text = "SPEED\n" + (if (speed == 0) "FAST" else "QUALITY")
        tint(tvHdr, hdrMode != 0)
        tint(tvNr, nrMode != 0)
        tint(tvAi, zoomEngine != 0)
        tint(tvTele, teleOn)
        tint(tvInfo, procMode != 0)
        tint(tvEnh, false)
        tint(tvLook, look != 0)
        tint(tvSpeed, speed == 1)
        grid.visibility = if (gridOn) View.VISIBLE else View.GONE
    }

    private fun applyLook() {
        when (look) {
            0 -> { brightSeek.progress = 0; detailSeek.progress = 40; filterIdx = 0 }
            1 -> { brightSeek.progress = 12; detailSeek.progress = 55; filterIdx = 6 }
            else -> { brightSeek.progress = 0; detailSeek.progress = 80; filterIdx = 0 }
        }
    }

    private fun fmt(r: Float): String {
        if (r >= 10f) return "${r.toInt()}x"
        val t = String.format(Locale.US, "%.1f", r)
        return (if (t.endsWith(".0")) t.dropLast(2) else t) + "x"
    }

    private fun updateZoomUi(ratio: Float) {
        var best: TextView? = null
        var bd = 99f
        chips.forEach { (v, r) ->
            if (v.visibility == View.VISIBLE) {
                val d = abs(ratio - r)
                if (d < bd) { bd = d; best = v }
            }
        }
        chips.forEach { (v, r) ->
            val on = v === best
            v.setBackgroundResource(if (on) R.drawable.zoom_active else 0)
            v.setTextColor(Color.WHITE)
            v.text = if (on) fmt(ratio) else if (r < 1f) ".5" else "${r.toInt()}x"
            v.textSize = if (on) 14f else 13f
        }
    }

    private fun setBusy(msg: String?) {
        if (msg == null) {
            tvBusy.visibility = View.GONE
        } else {
            tvBusy.text = msg
            tvBusy.visibility = View.VISIBLE
        }
    }

    private fun updateJobsUi() {
        val n = jobsPending.get()
        if (n <= 0) {
            tvJobs.visibility = View.GONE
        } else {
            tvJobs.text = "⏳ প্রসেস চলছে ($n)" + (if (jobStatus.isNotEmpty()) "\n$jobStatus" else "")
            tvJobs.visibility = View.VISIBLE
        }
    }

    // ---------- Camera ----------

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val sel = CameraSelector.Builder().requireLensFacing(lens).build()
        try {
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val ic = ImageCapture.Builder()
                .setFlashMode(flash)
                .setCaptureMode(if (speed == 0) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(4640, 3480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .build()
            imageCapture = ic
            p.unbindAll()
            camera = p.bindToLifecycle(this, sel, pv, ic)
        } catch (e: Throwable) {
            Toast.makeText(this, "ক্যামেরা চালু হয়নি: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        preview.post {
            val z = camera?.cameraInfo?.zoomState?.value
            val mn = z?.minZoomRatio ?: 1f
            vz = 1f
            preview.scaleX = 1f
            preview.scaleY = 1f
            chips.forEach { (v, r) ->
                v.visibility = if (r < 1f && mn >= 0.9f) View.GONE else View.VISIBLE
            }
            updateZoomUi(1f)
        }
    }

    // Hardware zoom-এর পর সীমিত digital crop; অতিরিক্ত zoom ছবির detail নষ্ট করে।
    private fun applyZoom() {
        val z = camera?.cameraInfo?.zoomState?.value
        val mn = z?.minZoomRatio ?: 1f
        val mx = z?.maxZoomRatio ?: 1f
        val camZ = vz.coerceIn(mn, mx)
        camera?.cameraControl?.setZoomRatio(camZ)
        val extra = if (vz > camZ) vz / camZ else 1f
        preview.scaleX = extra
        preview.scaleY = extra
        updateZoomUi(vz)
    }

    private fun setZoom(r: Float) {
        val z = camera?.cameraInfo?.zoomState?.value
        vz = r.coerceIn(z?.minZoomRatio ?: 1f, 10f)
        applyZoom()
    }

    // ---------- ছবি তোলা ----------

    private fun onShutter() {
        if (busy) return
        if (jobsPending.get() >= 3) {
            Toast.makeText(this, "আগের ছবিগুলো প্রসেস হচ্ছে, একটু অপেক্ষা করো", Toast.LENGTH_SHORT).show()
            return
        }
        if (timerSec > 0) {
            busy = true
            countdown(timerSec)
        } else {
            startCapture()
        }
    }

    private fun countdown(n: Int) {
        if (n <= 0) {
            tvCount.visibility = View.GONE
            busy = false
            startCapture()
            return
        }
        tvCount.visibility = View.VISIBLE
        tvCount.text = n.toString()
        main.postDelayed({ countdown(n - 1) }, 1000)
    }

    private fun fail(msg: String?) {
        busy = false
        setBusy(null)
        Toast.makeText(this, "ছবি তোলা যায়নি: $msg", Toast.LENGTH_LONG).show()
    }

    // দৃশ্যের গড় আলো আর অন্ধকার/উজ্জ্বল অংশের শতাংশ
    private fun sceneStats(): Triple<Int, Float, Float>? {
        return try {
            val b = preview.bitmap ?: return null
            val sw = b.width
            val rh = min(b.height, sw * 4 / 3)
            var s = 0L
            var dk = 0
            var br = 0
            var n = 0
            for (yy in 0 until 32) for (xx in 0 until 32) {
                val p = b.getPixel(xx * sw / 32, yy * rh / 32)
                val l = ((((p shr 16) and 255) * 77) + (((p shr 8) and 255) * 150) + ((p and 255) * 29)) shr 8
                s += l
                if (l < 35) dk++
                if (l > 225) br++
                n++
            }
            b.recycle()
            Triple((s / n).toInt(), dk / n.toFloat(), br / n.toFloat())
        } catch (e: Throwable) {
            null
        }
    }

    private fun startCapture() {
        val cam = camera
        val ic = imageCapture
        if (cam == null || ic == null) return
        busy = true
        setBusy("ছবি তোলা হচ্ছে… ফোন স্থির রাখো")
        shutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(80)
        }
        val zoom = vz
        val exp = cam.cameraInfo.exposureState
        val step = try { exp.exposureCompensationStep.toFloat() } catch (e: Throwable) { 0f }
        val rng = exp.exposureCompensationRange
        val maxIdx = if (exp.isExposureCompensationSupported && step > 0f) min(rng.upper, -rng.lower) else 0
        val twoEv = if (maxIdx > 0) min(maxIdx, Math.round(2f / step)) else 0
        val oneEv = if (maxIdx > 0) min(maxIdx, Math.round(1f / step)) else 0
        val flashUsed = flash != ImageCapture.FLASH_MODE_OFF
        val stats = if (!flashUsed) sceneStats() else null
        val dark = stats != null && mode != 0 && stats.first < 70
        val hdrWanted = hdrMode != 0 && mode != 0 &&
            (hdrMode == 2 || (stats != null && stats.second + stats.third > 0.14f))

        var evs = listOf(0)
        var merge = 0
        val fast = speed == 0
        if (!flashUsed) {
            if (mode == 0) {
                evs = List(if (fast) 4 else 6) { oneEv }
                merge = 1
            } else if (zoom > 1.4f && zoomEngine != 0) {
                // সুপার-রেজোলিউশন জুম: কয়েকটা ছবি, হাতের কাঁপুনি কাজে লাগানো
                if (zoom > 3.5f) {
                    evs = List(if (fast) (if (nrMode == 2) 8 else 6) else (if (nrMode == 2) 12 else 10)) { 0 }
                    merge = 3
                } else if (zoomEngine == 1) {
                    evs = List(if (fast) 4 else (if (nrMode == 2) 8 else 6)) { 0 }
                    merge = 3
                } else {
                    evs = List(if (fast) 4 else 6) { 0 }
                    merge = 4
                }
            } else if (fast) {
                // দ্রুত মোড: একটাই ছবি (HDR হলে আলো-ছায়া সফটওয়্যারে ঠিক হয়)
            } else if (dark) {
                evs = List(4) { 0 }
                merge = 1
            } else if (hdrWanted && twoEv >= 1) {
                evs = listOf(0, -twoEv, twoEv)
                merge = 2
            } else if (zoom > 1.3f) {
                evs = List(if (nrMode == 2) 6 else 4) { 0 }
                merge = 1
            } else if (nrMode == 2 || (nrMode == 1 && !(stats != null && stats.first >= 110))) {
                evs = List(if (nrMode == 2) 5 else 3) { 0 }
                merge = 1
            }
        }
        val pm = procMode
        if (pm == 2) {
            evs = listOf(0)
            merge = 0
        }
        val soft = zoom > 1.05f && pm != 2
        val params = Params(
            front = lens == CameraSelector.LENS_FACING_FRONT,
            zoom = zoom,
            softZoom = soft,
            merge = merge,
            hdrSingle = hdrWanted && merge != 2,
            night = mode == 0,
            portrait = mode == 2,
            blur = (blurSeek.progress + (if (teleOn && soft && mode == 2) ((zoom - 1f) * 8f).toInt().coerceAtMost(16) else 0)).coerceAtMost(100),
            tele = teleOn,
            beauty = if (mode == 0) 0 else beautySeek.progress,
            filter = filterIdx,
            ai = zoomEngine,
            bright = brightSeek.progress,
            detail = detailSeek.progress,
            nrBoost = when (nrMode) { 0 -> -0.15f; 2 -> 0.25f; else -> 0f },
            dark = dark,
            native = false
        )

        if (soft) {
            val fb = try { preview.bitmap } catch (e: Throwable) { null }
            if (fb != null) {
                ivFreeze.setImageBitmap(fb)
                ivFreeze.visibility = View.VISIBLE
            }
        }
        val shots = ArrayList<Shot>()
        val mainEx = ContextCompat.getMainExecutor(this)

        fun restore() {
            try { cam.cameraControl.setExposureCompensationIndex(0) } catch (e: Throwable) { }
            if (soft) applyZoom()
            ivFreeze.visibility = View.GONE
        }

        fun next(i: Int) {
            if (i >= evs.size) {
                restore()
                setBusy(null)
                busy = false
                if (pm == 2) {
                    submitOriginalOnly(shots)
                } else {
                    if (pm == 1) submitOriginal(shots)
                    submitJob(shots, params)
                }
                return
            }
            val setEv = if (evs[i] != 0 || merge == 2) {
                try { cam.cameraControl.setExposureCompensationIndex(evs[i]) } catch (e: Throwable) { null }
            } else null
            val go = Runnable {
                ic.takePicture(camExec, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            shots.add(Shot(bytes, image.imageInfo.rotationDegrees))
                        } finally {
                            image.close()
                        }
                        runOnUiThread {
                            if (i + 1 < evs.size) setBusy("ছবি ${i + 2}/${evs.size} … স্থির রাখো")
                            next(i + 1)
                        }
                    }

                    override fun onError(e: ImageCaptureException) {
                        runOnUiThread { restore(); fail(e.message) }
                    }
                })
            }
            val delay = if (merge == 2) 300L else 30L
            if (setEv != null) setEv.addListener({ main.postDelayed(go, delay) }, mainEx)
            else main.postDelayed(go, delay)
        }

        if (soft) {
            val zf = try { cam.cameraControl.setZoomRatio(1f) } catch (e: Throwable) { null }
            if (zf != null) zf.addListener({ main.postDelayed({ next(0) }, 120) }, mainEx)
            else next(0)
        } else {
            next(0)
        }
    }

    // ---------- ওরিজিনাল (কোনো প্রসেস ছাড়া ক্যামেরার ছবি) ----------

    private fun saveOriginalBytes(s: Shot): Uri {
        val name = "ORIG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PortraitCam/Original")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("সেভ করা যায়নি")
        contentResolver.openOutputStream(uri)!!.use { it.write(s.jpeg) }
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val ex = ExifInterface(pfd.fileDescriptor)
                ex.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    when (s.rot) { 90 -> "6"; 180 -> "3"; 270 -> "8"; else -> "1" }
                )
                ex.saveAttributes()
            }
        } catch (e: Throwable) { }
        v.clear()
        v.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        return uri
    }

    private fun submitOriginal(shots: List<Shot>) {
        val s = shots.firstOrNull() ?: return
        camExec.execute { try { saveOriginalBytes(s) } catch (e: Throwable) { } }
    }

    private fun submitOriginalOnly(shots: List<Shot>) {
        val s = shots.firstOrNull() ?: return
        camExec.execute {
            try {
                val uri = saveOriginalBytes(s)
                val o = BitmapFactory.Options().apply { inSampleSize = 16 }
                var th = BitmapFactory.decodeByteArray(s.jpeg, 0, s.jpeg.size, o)
                if (th != null && s.rot != 0) {
                    val mx = Matrix().apply { postRotate(s.rot.toFloat()) }
                    th = Bitmap.createBitmap(th, 0, 0, th.width, th.height, mx, true)
                }
                runOnUiThread {
                    lastUri = uri
                    if (th != null) gallery.setImageBitmap(th)
                    Toast.makeText(this, "ওরিজিনাল ছবি সেভ হয়েছে (প্রসেস ছাড়া)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "সেভ হয়নি: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- ব্যাকগ্রাউন্ডে প্রসেস (এর মধ্যেই আবার ছবি তোলা যায়) ----------

    private fun submitJob(shots: List<Shot>, p: Params) {
        jobsPending.incrementAndGet()
        updateJobsUi()
        val quick = java.util.concurrent.CompletableFuture<Uri?>()
        // ১) ছবি সাথে সাথেই গ্যালারিতে (দ্রুত সংস্করণ)
        quickExec.execute {
            try {
                val pr = quickSave(shots[0], p)
                quick.complete(pr.first)
                runOnUiThread {
                    lastUri = pr.first
                    if (pr.second != null) gallery.setImageBitmap(pr.second)
                    Toast.makeText(this, "ছবি সেভ হয়েছে — পেছনে উন্নত করা হচ্ছে…", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                quick.complete(null)
            }
        }
        // ২) পেছনে সেরা মানের প্রসেস, শেষে ওই ফাইলের জায়গায় বসবে
        procExec.execute { runProcess(shots, p, quick) }
    }

    private fun quickSave(s: Shot, p: Params): Pair<Uri, Bitmap?> {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PortraitCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("সেভ করা যায়নি")
        var thumb: Bitmap? = null
        if (!p.softZoom && !p.front) {
            contentResolver.openOutputStream(uri)!!.use { it.write(s.jpeg) }
            try {
                contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val ex = ExifInterface(pfd.fileDescriptor)
                    ex.setAttribute(ExifInterface.TAG_ORIENTATION, when (s.rot) { 90 -> "6"; 180 -> "3"; 270 -> "8"; else -> "1" })
                    ex.saveAttributes()
                }
            } catch (e: Throwable) { }
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = 16 }
                var tb = BitmapFactory.decodeByteArray(s.jpeg, 0, s.jpeg.size, o)
                if (tb != null && s.rot != 0) {
                    val mx = Matrix().apply { postRotate(s.rot.toFloat()) }
                    tb = Bitmap.createBitmap(tb, 0, 0, tb.width, tb.height, mx, true)
                }
                thumb = tb
            } catch (e: Throwable) { }
        } else {
            val dec = BitmapRegionDecoder.newInstance(s.jpeg, 0, s.jpeg.size, false)
            val fw = dec.width
            val fh = dec.height
            val z = if (p.softZoom) p.zoom else 1f
            val cw = max(32, (fw / z).toInt() and 1.inv())
            val ch = max(32, (fh / z).toInt() and 1.inv())
            val x0 = ((fw - cw) / 2) and 1.inv()
            val y0 = ((fh - ch) / 2) and 1.inv()
            var sample = 1
            while ((cw / sample).toLong() * (ch / sample) > 6_000_000L) sample *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = dec.decodeRegion(Rect(x0, y0, x0 + cw, y0 + ch), o)
            dec.recycle()
            val mx = Matrix().apply {
                postRotate(s.rot.toFloat())
                if (p.front) postScale(-1f, 1f)
            }
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
            if (rot !== bmp) bmp.recycle()
            bmp = rot
            val ls = max(bmp.width, bmp.height)
            if (z > 1.05f && ls < 2400) {
                val sc = 2400f / ls
                val up = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
                bmp.recycle()
                bmp = up
            }
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            thumb = Bitmap.createScaledBitmap(bmp, 160, max(1, 160 * bmp.height / bmp.width), true)
            bmp.recycle()
        }
        v.clear()
        v.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        return Pair(uri, thumb)
    }

    private fun runProcess(shots: List<Shot>, p: Params, quick: java.util.concurrent.CompletableFuture<Uri?>) {
        try {
            val (bmp, noPerson) = Pipeline.run(shots, p, sr, depthEst) { msg ->
                jobStatus = msg
                runOnUiThread { updateJobsUi() }
            }
            val quickUri = try { quick.get(30, TimeUnit.SECONDS) } catch (e: Throwable) { null }
            val uri = if (quickUri != null) {
                contentResolver.openOutputStream(quickUri, "wt")!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
                quickUri
            } else save(bmp)
            val th = Bitmap.createScaledBitmap(bmp, 160, max(1, 160 * bmp.height / bmp.width), true)
            bmp.recycle()
            val note = Pipeline.note
            val diag = Pipeline.diag
            runOnUiThread {
                lastUri = uri
                gallery.setImageBitmap(th)
                Toast.makeText(
                    this,
                    (if (noPerson) "মানুষ পাওয়া যায়নি, সাধারণ প্রসেস হয়েছে" else "✔ ছবি উন্নত হয়েছে") +
                        (if (note.isNotEmpty()) "\n" + note else "") +
                        (if (diag.isNotEmpty()) "\n" + diag else ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Throwable) {
            val msg = if (e is OutOfMemoryError) "মেমোরি কম। ZOOM-কে PLAIN করে দেখো" else (e.message ?: "অজানা এরর")
            runOnUiThread { Toast.makeText(this, "এরর: $msg", Toast.LENGTH_LONG).show() }
        } finally {
            jobsPending.decrementAndGet()
            jobStatus = ""
            runOnUiThread { updateJobsUi() }
        }
    }

    // ---------- গ্যালারির ছবি ভালো করা ----------

    private fun decodeUri(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
            ?: throw IllegalStateException("ছবি পড়া যায়নি")
        val orient = try {
            contentResolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Throwable) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    private fun enhanceUri(uri: Uri) {
        if (jobsPending.get() >= 3) {
            Toast.makeText(this, "আগের ছবিগুলো প্রসেস হচ্ছে, একটু অপেক্ষা করো", Toast.LENGTH_SHORT).show()
            return
        }
        jobsPending.incrementAndGet()
        jobStatus = "ছবি পড়া হচ্ছে…"
        updateJobsUi()
        val ai = if (zoomEngine == 0) 0 else 2
        val bright = brightSeek.progress
        val detail = detailSeek.progress
        val boost = when (nrMode) { 0 -> -0.15f; 2 -> 0.25f; else -> 0f }
        procExec.execute {
            try {
                val src = decodeUri(uri)
                val inW = src.width
                val inH = src.height
                val out = Pipeline.enhance(src, ai, bright, detail, boost, sr) { msg ->
                    jobStatus = msg
                    runOnUiThread { updateJobsUi() }
                }
                val saved = save(out, "ENH_")
                val th = Bitmap.createScaledBitmap(out, 160, max(1, 160 * out.height / out.width), true)
                val note = Pipeline.note
                val info = "${inW}x${inH} → ${out.width}x${out.height}"
                out.recycle()
                runOnUiThread {
                    lastUri = saved
                    gallery.setImageBitmap(th)
                    Toast.makeText(this, "ভালো করা ছবি সেভ হয়েছে ($info)" + (if (note.isNotEmpty()) "\n$note" else ""), Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                val msg = if (e is OutOfMemoryError) "মেমোরি কম" else (e.message ?: "অজানা এরর")
                runOnUiThread { Toast.makeText(this, "এরর: $msg", Toast.LENGTH_LONG).show() }
            } finally {
                jobsPending.decrementAndGet()
                jobStatus = ""
                runOnUiThread { updateJobsUi() }
            }
        }
    }

    private fun save(bmp: Bitmap, prefix: String = "IMG_"): Uri {
        val name = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PortraitCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("সেভ করা যায়নি")
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        v.clear()
        v.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
        return uri
    }
}
